package com.pocketlinux;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * Copied, never linked, and only what was chosen by hand. The computer gets something it can read
 * for ever, the cloud keeps the original, and nothing inside Linux is given standing access to
 * anything: the picker is Android's, and no program in the container can drive it.
 */
final class CloudFiles {

    static final int REQUEST_PICK = 47;

    /** Where a chosen file lands: an ordinary folder, outside the phone-storage mount. */
    static final String GUEST_FOLDER = "home/coder/Cloud";

    /**
     * Free space a copy is not allowed to eat into.
     *
     * The computer's own Storage window tells the owner that below 500 MB free Android starts
     * clearing app caches by itself, so a copy that fills the phone to the last byte would break
     * the desktop it was meant to feed. The picker offers every file on Drive, a 4 GB video
     * included, and this used to run with no check at all.
     */
    private static final long KEEP_FREE = 500L * 1000 * 1000;

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
     * A copy that lasts more than a moment shows a notification with a Stop button, the way
     * set-up shows its progress; a small file is over before a notification could be read, so
     * none appears for one.
     *
     * @return the names that arrived, with a line naming anything that did not, or null when
     *         nothing arrived at all
     */
    static String copyIn(Context context, List<Uri> chosen) {
        if (chosen.isEmpty()) return null;
        File cloud = folder(context);
        List<String> arrived = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        String blocked = null;
        Copy copy = new Copy(context, chosen.size());
        try {
            for (int at = 0; at < chosen.size(); at++) {
                if (copy.stopped()) {
                    // Named, not dropped in silence: the owner chose these files and has to be
                    // able to see which of them never left the cloud app.
                    int left = chosen.size() - at;
                    missed.add(left == 1 ? "1 more file (stopped)" : left + " more files (stopped)");
                    break;
                }
                Uri one = chosen.get(at);
                String name = displayName(context, one);
                long size = sizeOf(context, one);
                long room = roomFor(cloud);
                if (size > room) {
                    missed.add(name + " (needs " + DeviceProbe.formatBytes(size)
                            + ", room for " + DeviceProbe.formatBytes(room) + ")");
                    if (blocked == null) {
                        blocked = name + " needs " + DeviceProbe.formatBytes(size) + " and this "
                                + "phone has room for " + DeviceProbe.formatBytes(room)
                                + ". It was not brought in.";
                    }
                    continue;
                }
                File part = null;
                boolean full = false;
                try {
                    File target = freeName(cloud, name);
                    // Read first, and into a temporary name. An unreadable document used to leave a
                    // 0-byte file with the real name behind, and a copy that broke off half way (a
                    // Drive file that stopped syncing, a grant that lapsed) left a truncated one --
                    // which an AI app then attached as if it were whole.
                    try (InputStream in = context.getContentResolver().openInputStream(one)) {
                        if (in == null) {
                            missed.add(name + " (could not be read)");
                            continue;
                        }
                        part = new File(cloud, target.getName() + ".part");
                        try (OutputStream out = new FileOutputStream(part)) {
                            byte[] buffer = new byte[64 * 1024];
                            long written = 0;
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                if (copy.stopped()) break;
                                // Also measured while it runs, not only before it starts: a cloud
                                // app is free to report no size at all, or a smaller one than it
                                // then sends.
                                if (written + read > room) {
                                    full = true;
                                    break;
                                }
                                out.write(buffer, 0, read);
                                written += read;
                                copy.moved(name, at + 1, written, size);
                            }
                        }
                    }
                    if (full || copy.stopped()) {
                        if (part != null) part.delete();
                        part = null;
                        missed.add(name + (full ? " (the phone ran out of room)" : " (stopped)"));
                        if (blocked == null) {
                            blocked = full
                                    ? "The phone ran out of room, so " + name + " was not brought in."
                                    : "Stopped. " + name + " was not brought in.";
                        }
                        continue;
                    }
                    if (!part.renameTo(target)) {
                        part.delete();
                        part = null;
                        missed.add(name + " (could not be saved)");
                        if (blocked == null) {
                            blocked = name + " reached the phone but could not be saved into the "
                                    + "Cloud folder.";
                        }
                        continue;
                    }
                    part = null;
                    // Readable by the container, which runs as its own user inside PRoot.
                    target.setReadable(true, false);
                    arrived.add(target.getName());
                } catch (Throwable refused) {
                    if (part != null) part.delete();
                    missed.add(name + " (could not be read)");
                    // Deliberately everything. openInputStream ends up inside a provider written by
                    // someone else -- a cloud app, an OEM's file provider -- and a stale document
                    // comes back as whatever that provider felt like throwing: IllegalArgumentException
                    // for an unknown URI, UnsupportedOperationException for one that cannot be opened.
                    // One file that cannot be read must not take the screen down with it, and the
                    // loop is written to carry on to the next.
                }
            }
        } finally {
            copy.close();
        }
        if (arrived.isEmpty()) {
            // The screen that asked for the files says a file could not be read when nothing comes
            // back. When the real reason was space, or the owner's own Stop, that has to be said
            // here or they are sent hunting a sync problem that is not there.
            if (blocked != null) say(context, blocked);
            return null;
        }
        String said = String.join(", ", arrived);
        if (!missed.isEmpty()) said += "\n\nNot brought in: " + String.join("; ", missed) + ".";
        return said;
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
        if (name.length() > 120) name = name.substring(name.length() - 120);
        // The leading dot stays. Stripping it turned .env into env and .bashrc into bashrc, and
        // the owner was then shown the stripped name as the file that had arrived -- for a config
        // file handed to an AI coding app, that is no longer the file it is looking for. What the
        // stripping had to stop is a name that is nothing but dots: "." is the Cloud folder itself
        // and ".." is the folder above it, so a copy under either name would land on neither.
        if (name.isEmpty() || onlyDots(name)) name = "file";
        return name;
    }

    private static boolean onlyDots(String name) {
        for (int at = 0; at < name.length(); at++) {
            if (name.charAt(at) != '.') return false;
        }
        return true;
    }

    /** What the provider says the document weighs, or -1 when it will not say. */
    private static long sizeOf(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver()
                .query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getColumnCount() > 0
                    && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Throwable unknown) {
            // The same providers displayName deals with: a cloud app may answer nothing at all.
        }
        return -1;
    }

    /** How many bytes a copy may still use on this phone, keeping KEEP_FREE back. */
    private static long roomFor(File folder) {
        try {
            long free = new StatFs(folder.getAbsolutePath()).getAvailableBytes();
            return Math.max(0, free - KEEP_FREE);
        } catch (Throwable unmeasured) {
            // A phone that will not answer statfs is not a reason to refuse the file; the copy
            // then behaves as it did before there was a check, and stops on the write that fails.
            return Long.MAX_VALUE;
        }
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
        // Five hundred copies of one name: still not the plain name, which is the one file this
        // method exists to leave alone.
        long stamp = System.currentTimeMillis();
        while (new File(folder, stem + " (" + stamp + ")" + extension).exists()) stamp++;
        return new File(folder, stem + " (" + stamp + ")" + extension);
    }

    /** A line the owner must not miss, from whatever thread the copy is running on. */
    private static void say(Context context, String words) {
        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(app, words, Toast.LENGTH_LONG).show());
    }

    /**
     * The progress notification for one run of copyIn, and the Stop button that ends it.
     *
     * The same shape set-up uses: a title, a line of detail, a bar and Stop. It is posted only
     * after a copy has been running for a moment, because a small document is finished before a
     * notification could be read, and it is taken down again the moment the copy ends.
     */
    private static final class Copy {
        private static final int NOTIFICATION = 2308;
        /**
         * The app's one notification category, created at start-up by LinuxService.
         *
         * Named here rather than owned here: a category of its own would put a second row in the
         * phone's notification settings for something that is over in a minute.
         */
        private static final String CHANNEL = "pocketdesk_linux";
        private static final String STOP = "com.pocketlinux.action.STOP_CLOUD_COPY";
        private static final long SHOW_AFTER_MS = 1200;
        private static final long REDRAW_MS = 500;

        private final Context context;
        private final int files;
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final long startedAt = SystemClock.elapsedRealtime();
        private BroadcastReceiver stopButton;
        private boolean showing;
        private long drawnAt;

        Copy(Context context, int files) {
            this.context = context.getApplicationContext();
            this.files = files;
            BroadcastReceiver listening = new BroadcastReceiver() {
                @Override public void onReceive(Context from, Intent intent) { stopped.set(true); }
            };
            try {
                IntentFilter filter = new IntentFilter(STOP);
                if (Build.VERSION.SDK_INT >= 33) {
                    this.context.registerReceiver(listening, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    this.context.registerReceiver(listening, filter);
                }
                stopButton = listening;
            } catch (Throwable noReceiver) {
                // Without the receiver the button would do nothing, so the notification below
                // leaves it off rather than showing a Stop that does not stop.
                stopButton = null;
            }
        }

        boolean stopped() { return stopped.get(); }

        /** Called as the bytes move; redraws at most twice a second. */
        void moved(String name, int position, long written, long size) {
            long now = SystemClock.elapsedRealtime();
            if (!showing && now - startedAt < SHOW_AFTER_MS) return;
            if (showing && now - drawnAt < REDRAW_MS) return;
            showing = true;
            drawnAt = now;
            String detail = size > 0
                    ? name + " · " + DeviceProbe.formatBytes(written)
                            + " of " + DeviceProbe.formatBytes(size)
                    : name + " · " + DeviceProbe.formatBytes(written) + " so far";
            String title = files == 1
                    ? "Bringing the file in"
                    : "Bringing file " + position + " of " + files + " in";
            draw(title, detail, size > 0 ? (int) (written * 100 / size) : -1);
        }

        void close() {
            if (stopButton != null) {
                try { context.unregisterReceiver(stopButton); } catch (Throwable ignored) {}
                stopButton = null;
            }
            if (!showing) return;
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(NOTIFICATION);
        }

        private void draw(String title, String detail, int percent) {
            NotificationManager manager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(detail)
                    .setStyle(new Notification.BigTextStyle().bigText(detail))
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_PROGRESS);
            if (percent >= 0) builder.setProgress(100, Math.min(100, percent), false);
            else builder.setProgress(0, 0, true);
            if (stopButton != null) {
                PendingIntent stop = PendingIntent.getBroadcast(context, 3,
                        new Intent(STOP).setPackage(context.getPackageName()),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                builder.addAction(new Notification.Action.Builder(null, "Stop", stop).build());
            }
            // A phone with notifications turned off simply shows nothing here; the copy itself is
            // not held up by it.
            try { manager.notify(NOTIFICATION, builder.build()); } catch (Throwable ignored) {}
        }
    }
}
