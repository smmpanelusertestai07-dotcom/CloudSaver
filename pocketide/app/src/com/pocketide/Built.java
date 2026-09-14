package com.pocketide;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Hands an APK built inside Linux to Android's own installer, and nothing else to anyone.
 *
 * This is the last step of "the phone is the test device". A Gradle build in the workspace
 * writes its APK under ~/projects, which is a directory inside this app's private storage
 * that no other app -- the installer included -- can open. A content provider is how Android
 * lets one app show one file to another for one purpose, and that is the whole of what this
 * one does.
 *
 * Three things hold it to that:
 *
 *   It is not exported. Nothing can ask it for a file. The only way to a file through it is a
 *   URI this app hands out with a read grant attached, which the installer receives and can
 *   use once.
 *
 *   It serves only files that end in .apk, and only from under ~/projects. The path is
 *   canonicalised before that check, so a URI carrying ".." cannot walk out of the directory
 *   and a symlink an agent might have made cannot lead out of it either.
 *
 *   It opens read-only. There is no write path, no delete, no insert, and no query beyond the
 *   name and size the installer shows on its own screen.
 *
 * The installer then does what it does for any APK from any app: asks the owner, on Android's
 * own screen, and refuses until "install unknown apps" has been allowed for PocketIDE. This
 * app cannot install anything by itself, and does not try to.
 */
public final class Built extends ContentProvider {

    static final String AUTHORITY = "com.pocketide.built";
    static final String MIME = "application/vnd.android.package-archive";

    /** The URI the installer is handed for a file under ~/projects, or null if it is not one. */
    static Uri uriFor(android.content.Context context, File apk) {
        File root = Workspace.projects(context);
        try {
            String rootPath = root.getCanonicalPath();
            String path = apk.getCanonicalPath();
            if (!path.startsWith(rootPath + File.separator) || !path.endsWith(".apk")) return null;
            String relative = path.substring(rootPath.length() + 1);
            Uri.Builder uri = new Uri.Builder().scheme("content").authority(AUTHORITY);
            for (String segment : relative.split("/")) uri.appendPath(segment);
            return uri.build();
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** The file a URI names, only when it is an APK under ~/projects. */
    private File resolve(Uri uri) throws FileNotFoundException {
        File root = Workspace.projects(getContext());
        File candidate = new File(root, uri.getPath() == null ? "" : uri.getPath());
        try {
            String rootPath = root.getCanonicalPath();
            String path = candidate.getCanonicalPath();
            if (!path.startsWith(rootPath + File.separator)) throw new FileNotFoundException();
            if (!path.endsWith(".apk")) throw new FileNotFoundException();
            File file = new File(path);
            if (!file.isFile()) throw new FileNotFoundException();
            return file;
        } catch (IOException unreadable) {
            throw new FileNotFoundException();
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return MIME; }

    /** The name and size the installer shows; nothing else is answerable. */
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = resolve(uri);
        } catch (FileNotFoundException missing) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{file.getName(), file.length()});
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
