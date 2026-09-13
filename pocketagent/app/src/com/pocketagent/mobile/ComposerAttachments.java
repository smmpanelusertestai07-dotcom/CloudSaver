package com.pocketagent.mobile;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** App-private attachment cards. Only the selected project files are opened, on a worker. */
final class ComposerAttachments extends HorizontalScrollView {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 2, 20,
            TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(20));
    private final Activity activity;
    private final BooleanSupplier current;
    private final Consumer<String> remove;
    private final LinearLayout row;
    private final ArrayList<Card> cards = new ArrayList<>();
    private String signature = "";
    private boolean editable = true, released;
    private int generation;

    ComposerAttachments(Activity activity, BooleanSupplier current, Consumer<String> onRemove) {
        super(activity); this.activity = activity; this.current = current; this.remove = onRemove;
        setHorizontalScrollBarEnabled(false); setFillViewport(false); setClipToPadding(false);
        row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(2), 0, dp(4)); addView(row, new LayoutParams(-2, -2));
        setVisibility(GONE); setContentDescription("Attachments");
    }

    /** Removing an attachment only removes it from this draft; the private project copy remains. */
    void update(File project, JSONArray imagePaths, List<String> filePaths, String extraPath, boolean editable) {
        this.editable = editable;
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (imagePaths != null) for (int i=0; i<imagePaths.length(); i++) add(paths, imagePaths.optString(i));
        if (filePaths != null) for (String path : filePaths) add(paths, path);
        add(paths, extraPath);
        String next = (project == null ? "" : project.getAbsolutePath()) + "\n" + paths.toString();
        if (!next.equals(signature)) {
            clear(); released = false; signature = next;
            if (project != null) for (String path : paths) {
                Card card = new Card(project, path, generation); cards.add(card);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(108), dp(112));
                params.rightMargin = dp(8); row.addView(card, params);
            }
        }
        for (Card card : cards) { card.close.setEnabled(editable); card.close.setAlpha(editable ? 1f : .4f); }
        setVisibility(cards.isEmpty() ? GONE : VISIBLE);
    }
    void clear() {
        generation++; for (Card card : cards) card.dispose(); cards.clear(); row.removeAllViews();
        signature = ""; setVisibility(GONE);
    }
    void release() { clear(); released = true; }
    private static void add(LinkedHashSet<String> paths, String path) { if (path != null && !path.isEmpty() && paths.size()<17) paths.add(path); }
    private boolean valid(int owner) { return !released && generation == owner && !activity.isFinishing() && !activity.isDestroyed() && !AppLock.isLocked(activity) && current.getAsBoolean(); }
    private int dp(int value) { return Ui.dp(activity, value); }

    private final class Card extends FrameLayout {
        final File project; final String path; final int owner;
        final ImageView image; final ImageButton close; final TextView name, kind;
        Bitmap bitmap; Future<?> task; boolean detached; int loadGeneration;
        Card(File project, String path, int owner) {
            super(activity); this.project = project; this.path = path; this.owner = owner;
            setBackground(DeskStyle.field(activity)); setClipToOutline(true);
            String filename = new File(path).getName().replaceFirst("^[0-9a-f]{24}-", "");
            setContentDescription("Preview " + filename); setFocusable(true); setClickable(true);
            LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL); body.setGravity(Gravity.CENTER);
            body.setPadding(dp(8), dp(7), dp(8), dp(7)); addView(body, new LayoutParams(-1, -1));
            image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            image.setImageDrawable(DeskStyle.icon(activity, "file", DeskStyle.MUTED));
            image.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            body.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
            name = Ui.text(activity, filename, 11, DeskStyle.TEXT); name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); name.setGravity(Gravity.CENTER);
            body.addView(name, new LinearLayout.LayoutParams(-1, -2));
            kind = Ui.text(activity, extension(filename), 10, DeskStyle.MUTED); kind.setGravity(Gravity.CENTER);
            body.addView(kind, new LinearLayout.LayoutParams(-1, -2));
            close = new ImageButton(activity); close.setImageDrawable(DeskStyle.icon(activity, "close", DeskStyle.TEXT));
            close.setBackground(DeskStyle.plain(activity)); close.setPadding(dp(13), dp(13), dp(13), dp(13));
            close.setContentDescription("Remove " + filename); close.setTooltipText("Remove attachment");
            LayoutParams closeParams = new LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END); addView(close, closeParams);
            close.setOnClickListener(v -> { if (valid(owner) && editable) remove.accept(path); });
            setOnClickListener(v -> { if (valid(owner)) WorkspaceMedia.openPreview(activity, project, path); });
            addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                public void onViewAttachedToWindow(View view) { detached = false; load(); }
                public void onViewDetachedFromWindow(View view) { detached = true; dispose(); }
            });
        }
        void load() {
            if (!valid(owner) || bitmap != null || task != null && !task.isDone()) return;
            final int ticket = ++loadGeneration;
            try { task = WORKER.submit(() -> {
                Bitmap decoded = null;
                try {
                    JSONObject info = WorkspaceMedia.describe(activity, project, path);
                    if ("image".equals(info.optString("kind"))) try { decoded = WorkspaceMedia.thumbnail(activity, project, path, 256); } catch (IOException ignored) { }
                    final Bitmap preview = decoded;
                    activity.runOnUiThread(() -> {
                        if (!valid(owner) || detached || ticket != loadGeneration) { if (preview != null) preview.recycle(); return; }
                        bitmap = preview;
                        if (preview != null) { image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setImageBitmap(preview); }
                        else image.setImageDrawable(DeskStyle.icon(activity, "video".equals(info.optString("kind")) ? "video" : "file", DeskStyle.MUTED));
                        kind.setText(extension(name.getText().toString()) + " · " + size(info.optLong("size")));
                    });
                } catch (IOException | RuntimeException error) {
                    if (decoded != null) decoded.recycle();
                    activity.runOnUiThread(() -> { if (valid(owner) && !detached && ticket == loadGeneration) kind.setText("Unavailable"); });
                }
            }); } catch (RejectedExecutionException busy) { postDelayed(() -> { if (valid(owner) && !detached) load(); }, 400); }
        }
        void dispose() { loadGeneration++; if (task != null) task.cancel(true); task = null; WORKER.purge(); image.setImageDrawable(null); if (bitmap != null) { bitmap.recycle(); bitmap = null; } }
    }
    private static String extension(String name) { int dot=name.lastIndexOf('.'); return dot>0 && name.length()-dot<=9 ? name.substring(dot+1).toUpperCase(java.util.Locale.ROOT) : "FILE"; }
    private static String size(long bytes) { return bytes>=1024*1024 ? String.format(java.util.Locale.ROOT,"%.1f MB",bytes/(1024d*1024)) : bytes>=1024 ? String.format(java.util.Locale.ROOT,"%.0f KB",bytes/1024d) : bytes+" B"; }
}
