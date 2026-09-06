package com.pocketlinux;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Files from the phone -- including the ones that are not on the phone at all.
 *
 * Google Drive, OneDrive, Dropbox and the rest are not folders. Android exposes them as document
 * providers behind content:// addresses, with no path anywhere on the filesystem, so there is
 * nothing for the container to mount and no amount of permission would make one appear as a
 * folder. What DOES reach them is Android's own document picker: it lists every cloud app
 * installed on the phone beside the phone's own storage, and hands back whatever is chosen --
 * fetching it from the cloud first if it has to.
 *
 * So that is what this does. The chosen files are copied into the computer's Cloud folder, which
 * is an ordinary Linux folder in every Open dialog's sidebar. ChatGPT's "attach a file", Claude's
 * upload, Cursor's open, the browser's file field: all of them see it, because by then it is
 * simply a file on the computer.
 *
 * Copied, never linked, and one file at a time, chosen by hand. The computer gets something it
 * can read for ever, the cloud keeps the original, and nothing inside Linux is given standing
 * access to anything: the picker is Android's, and no program in the container can drive it.
 */
final class CloudFiles {

    static final int REQUEST_PICK = 47;

    /** Where a chosen file lands: an ordinary folder, outside the phone-storage mount. */
    static final String GUEST_FOLDER = "home/coder/Cloud";

    private CloudFiles() { }

    static File folder(Context context) {
        File cloud = new File(ContainerRuntime.rootfs(context), GUEST_FOLDER);
        if (!cloud.exists()) cloud.mkdirs();
        return cloud;
    }

    /** Opens Android's document picker: phone storage and every cloud app, in one list. */
    static boolean pick(Activity activity) {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            activity.startActivityForResult(pick, REQUEST_PICK);
            return true;
        } catch (Throwable noPicker) {
            return false;
        }
    }

    /** Every file in a picker result or a share, in the order they were chosen. */
    static List<Uri> urisOf(Intent data) {
        List<Uri> chosen = new ArrayList<>();
        if (data == null) return chosen;
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int at = 0; at < clip.getItemCount(); at++) {
                Uri one = clip.getItemAt(at).getUri();
                if (one != null) chosen.add(one);
            }
        }
        if (chosen.isEmpty() && data.getData() != null) chosen.add(data.getData());
        if (chosen.isEmpty()) {
            Object extra = data.getParcelableExtra(Intent.EXTRA_STREAM);
            if (extra instanceof Uri) chosen.add((Uri) extra);
            ArrayList<Uri> many = data.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) {
                for (Uri one : many) {
                    if (one != null) chosen.add(one);
                }
            }
        }
        return chosen;
    }

    /**
     * Copies the chosen files in and returns what to tell the owner.
     *
     * @return the names that arrived, or null when nothing could be read
     */
    static String copyIn(Context context, List<Uri> chosen) {
        if (chosen.isEmpty()) return null;
        File cloud = folder(context);
        List<String> arrived = new ArrayList<>();
        for (Uri one : chosen) {
            try {
                File target = freeName(cloud, displayName(context, one));
                try (InputStream in = context.getContentResolver().openInputStream(one);
                     OutputStream out = new FileOutputStream(target)) {
                    if (in == null) continue;
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
                // Readable by the container, which runs as its own user inside PRoot.
                target.setReadable(true, false);
                arrived.add(target.getName());
            } catch (IOException | SecurityException | IllegalStateException refused) {
                // One file that cannot be read must not stop the rest: a cloud app can revoke a
                // grant, and a file still syncing can be offered before it has finished arriving.
            }
        }
        return arrived.isEmpty() ? null : String.join(", ", arrived);
    }

    /** The name the picker shows, cleaned of anything that is not a file name. */
    private static String displayName(Context context, Uri uri) {
        String name = null;
        try (Cursor cursor = context.getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getColumnCount() > 0) {
                name = cursor.getString(0);
            }
        } catch (Throwable unreadable) {
            name = null;
        }
        if (name == null || name.trim().isEmpty()) name = uri.getLastPathSegment();
        if (name == null) name = "file";
        name = name.replace('/', '-').replace('\\', '-').trim();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.isEmpty()) name = "file";
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    /** A name nothing else is using, so a second copy never replaces the first. */
    private static File freeName(File folder, String name) {
        File plain = new File(folder, name);
        if (!plain.exists()) return plain;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; n < 500; n++) {
            File tried = new File(folder, stem + " (" + n + ")" + extension);
            if (!tried.exists()) return tried;
        }
        return plain;
    }
}
