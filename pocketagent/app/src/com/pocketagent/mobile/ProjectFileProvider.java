package com.pocketagent.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import org.json.JSONObject;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Non-exported provider; Android grants one immutable snapshot URI only after explicit Share. */
public final class ProjectFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private String checkedToken(Uri uri) throws IOException {
        if (getContext() == null || uri == null) throw new IOException("Invalid shared file URI.");
        return WorkspaceMediaPaths.shareToken(getContext().getPackageName() + ".projectfiles", uri.toString());
    }

    private JSONObject metadata(Uri uri) throws IOException {
        String token = checkedToken(uri);
        File body = WorkspaceMedia.sharedFile(getContext(), token, false);
        File info = WorkspaceMedia.sharedFile(getContext(), token, true);
        try {
            JSONObject result = new JSONObject(new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8));
            result.put("size", body.length());
            return result;
        } catch (org.json.JSONException e) { throw new IOException("Shared file metadata is unavailable.", e); }
    }

    @Override public String getType(Uri uri) {
        try { return metadata(uri).optString("mime", "application/octet-stream"); }
        catch (IOException e) { return "application/octet-stream"; }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String[] requested = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        if (requested.length > 20) throw new IllegalArgumentException("Too many columns.");
        MatrixCursor cursor = new MatrixCursor(requested, 1);
        try {
            JSONObject info = metadata(uri);
            Object[] row = new Object[requested.length];
            for (int i = 0; i < requested.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(requested[i])) row[i] = info.optString("name", "attachment");
                else if (OpenableColumns.SIZE.equals(requested[i])) row[i] = info.optLong("size");
            }
            cursor.addRow(row);
        } catch (IOException ignored) { }
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Shared project files are read-only.");
        try {
            String token = checkedToken(uri);
            WorkspaceMedia.sharedFile(getContext(), token, true);
            return ParcelFileDescriptor.open(WorkspaceMedia.sharedFile(getContext(), token, false), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException | SecurityException e) {
            throw new FileNotFoundException("This shared file is unavailable. Share it again from PocketAgent.");
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only shared files."); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read-only shared files."); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read-only shared files."); }
}
