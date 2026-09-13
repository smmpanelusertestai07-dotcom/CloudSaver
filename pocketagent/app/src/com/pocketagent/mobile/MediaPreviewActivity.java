package com.pocketagent.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native, local-only image/audio/video preview. Playback always starts with a deliberate tap. */
public final class MediaPreviewActivity extends Activity {
    static final String EXTRA_PROJECT = "media_project", EXTRA_PATH = "media_path", EXTRA_SHARED = "media_shared";
    private static final int SAVE = 842;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FrameLayout lockRoot, canvas;
    private LinearLayout root, controls;
    private TextView title, status, time;
    private Button play, save, share;
    private SeekBar seek;
    private File project;
    private String relative;
    private JSONObject info;
    private Bitmap bitmap;
    private String svgMarkup;
    private SafeSvgView svgView;
    private VideoView video;
    private MediaPlayer audio;
    private Uri playbackUri, pendingSave, shared;
    private boolean loaded, resumed, preparing, seeking;
    private int playbackGeneration;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            String cached = getIntent().getStringExtra(EXTRA_SHARED);
            if (cached != null) {
                WorkspaceMediaPaths.shareToken(getPackageName() + ".projectfiles", cached);
                shared = Uri.parse(cached); // File validation/migration runs in load(), off the UI thread.
            } else {
            String name = AgentProtocol.project(getIntent().getStringExtra(EXTRA_PROJECT));
            project = WorkspaceTools.safeChild(ContainerRuntime.workspaceRoot(this), name);
            WorkspaceTools.guestPath(this, project);
            relative = getIntent().getStringExtra(EXTRA_PATH);
            WorkspaceMediaPaths.file(project, relative);
            }
        } catch (IOException | RuntimeException bad) { finish(); return; }
        if (state != null && state.getString("pending_save") != null) pendingSave = Uri.parse(state.getString("pending_save"));
        build();
    }

    private void build() {
        AppLock.applyWindowSecurity(this);
        getWindow().setStatusBarColor(DeskStyle.BG); getWindow().setNavigationBarColor(DeskStyle.BG);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true); root.setBackground(DeskStyle.background(this));
        root.setPadding(dp(16), dp(8), dp(16), dp(12));
        lockRoot = new FrameLayout(this); lockRoot.addView(root, new FrameLayout.LayoutParams(-1, -1)); setContentView(lockRoot);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("");back.setContentDescription("Back");back.setPadding(dp(12),dp(12),dp(12),dp(12));back.setCompoundDrawablesWithIntrinsicBounds(DeskStyle.icon(this,"chevron_left",DeskStyle.TEXT),null,null,null);back.setOnClickListener(v -> finish());top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        title = Ui.text(this, "File", 18, DeskStyle.TEXT); title.setMaxLines(2); title.setPadding(dp(12),0,0,0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(top);
        status = Ui.text(this, "Opening file…", 13, DeskStyle.MUTED); status.setPadding(0,dp(14),0,dp(14)); root.addView(status);
        canvas = new FrameLayout(this); canvas.setBackground(DeskStyle.card(this));
        root.addView(canvas, new LinearLayout.LayoutParams(-1,0,1));
        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); controls.setVisibility(View.GONE);
        play = button("Play"); play.setOnClickListener(v -> togglePlayback()); controls.addView(play, new LinearLayout.LayoutParams(-1,dp(48)));
        seek = new SeekBar(this); seek.setMax(1000); seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean user) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (!AppLock.isLocked(MediaPreviewActivity.this)) {
                    try {
                        if (video != null && video.getDuration() > 0) video.seekTo((int)((long)video.getDuration() * bar.getProgress() / 1000));
                        else if (audio != null && audio.getDuration() > 0) audio.seekTo((int)((long)audio.getDuration() * bar.getProgress() / 1000));
                    } catch (IllegalStateException ignored) { }
                }
                seeking = false;
            }
        }); controls.addView(seek);
        time = Ui.text(this,"0:00",12,DeskStyle.MUTED); time.setGravity(Gravity.CENTER); controls.addView(time); root.addView(controls);
        LinearLayout actions = new LinearLayout(this); actions.setPadding(0,dp(12),0,0);
        save = button("Save copy"); save.setEnabled(false); save.setOnClickListener(v -> chooseSave());
        share = button("Share"); share.setEnabled(false); share.setOnClickListener(v -> shareCopy());
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0,dp(50),1); left.rightMargin = dp(8);
        actions.addView(save,left); actions.addView(share,new LinearLayout.LayoutParams(0,dp(50),1)); root.addView(actions);
    }

    private void load() {
        if (loaded || AppLock.isLocked(this)) return;
        loaded = true;
        worker.execute(() -> {
            try {
                JSONObject metadata = shared != null ? WorkspaceMedia.describeShared(this,shared) : WorkspaceMedia.describe(this,project,relative);
                Bitmap image = null;
                if (metadata.optString("kind").equals("image")) {
                    try { image = shared != null ? WorkspaceMedia.thumbnailShared(this,shared,2048) : WorkspaceMedia.thumbnail(this,project,relative,2048); }
                    catch (IOException ignored) { }
                }
                String svg = null;
                if (WorkspaceMedia.isSvg(metadata)) {
                    try { svg = shared != null ? WorkspaceMedia.svgShared(this, shared) : WorkspaceMedia.svgProject(this, project, relative); }
                    catch (IOException ignored) { }
                }
                final String preparedSvg = svg;
                final Bitmap decoded = image;
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) { if(decoded != null) decoded.recycle(); return; }
                    info = metadata; bitmap = decoded; svgMarkup = preparedSvg; render();
                });
            } catch (IOException | RuntimeException e) { fail(e); }
        });
    }

    private void render() {
        if (info == null) return;
        title.setText(info.optString("name"));
        status.setText(info.optString("mime") + " · " + humanSize(info.optLong("size")) + (shared != null ? "\n" + info.optString("sourceHost") : "\n" + relative));
        releaseSvg(); canvas.removeAllViews();
        String kind = info.optString("kind");
        if (svgMarkup != null && resumed && !AppLock.isLocked(this)) {
            try { svgView = new SafeSvgView(this, svgMarkup); canvas.addView(svgView, new FrameLayout.LayoutParams(-1,-1)); }
            catch (RuntimeException unavailable) { svgView=null; TextView fallback=Ui.text(this,"SVG preview needs Android System WebView. You can save or share this image.",16,DeskStyle.MUTED);fallback.setPadding(dp(20),dp(20),dp(20),dp(20));canvas.addView(fallback,new FrameLayout.LayoutParams(-1,-1)); }
        } else if (bitmap != null) {
            ZoomImage image = new ZoomImage(); image.setContentDescription("Image preview. Pinch to zoom.");
            canvas.addView(image,new FrameLayout.LayoutParams(-1,-1)); image.setImageBitmap(bitmap);
        } else if (kind.equals("audio") || kind.equals("video")) {
            TextView placeholder = Ui.text(this, kind.equals("audio") ? "Audio file\nTap Play to listen" : "Video file\nTap Play to watch",20,DeskStyle.TEXT);
            placeholder.setGravity(Gravity.CENTER); canvas.addView(placeholder,new FrameLayout.LayoutParams(-1,-1)); controls.setVisibility(View.VISIBLE);
        } else {
            TextView placeholder = Ui.text(this, kind.equals("image")
                    ? "This image cannot be previewed on this phone.\nYou can save or share the file."
                    : "File ready\nSave a copy or share it with an app you choose.",17,DeskStyle.MUTED);
            placeholder.setGravity(Gravity.CENTER); placeholder.setPadding(dp(24),dp(24),dp(24),dp(24));
            canvas.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
        }
        boolean bounded = info.optLong("size") <= WorkspaceMedia.MAX_FILE_BYTES;
        save.setEnabled(bounded); share.setEnabled(bounded); play.setEnabled(bounded);
    }

    private void togglePlayback() {
        if (info == null || preparing || AppLock.isLocked(this)) return;
        try {
            if (video != null) { if(video.isPlaying()) {video.pause(); play.setText("Play");} else {video.start(); play.setText("Pause"); tick();} return; }
            if (audio != null) { if(audio.isPlaying()) {audio.pause(); play.setText("Play");} else {audio.start(); play.setText("Pause"); tick();} return; }
        } catch (IllegalStateException failed) { stopPlayback(); }
        preparing = true; play.setEnabled(false); play.setText("Preparing…");
        final int generation = ++playbackGeneration;
        worker.execute(() -> {
            try {
                Uri local = playbackUri != null ? playbackUri : shared != null ? shared : WorkspaceMedia.contentUri(this,project,relative);
                main.post(() -> {
                    playbackUri = local;
                    if (generation != playbackGeneration) return;
                    if (!resumed || isDestroyed() || AppLock.isLocked(this)) { preparing=false; play.setEnabled(true); play.setText("Play"); return; }
                    startPlayer(local,generation);
                });
            } catch (IOException | RuntimeException error) { main.post(() -> { stopPlayback(); fail(error); }); }
        });
    }

    private void startPlayer(Uri local, int generation) {
        if (info.optString("kind").equals("video")) {
            video = new VideoView(this); canvas.removeAllViews();
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER); canvas.addView(video,params);
            video.setOnPreparedListener(player -> {
                if (generation != playbackGeneration) return;
                preparing=false; play.setEnabled(true);
                if (resumed && !AppLock.isLocked(this) && video != null) {video.start(); play.setText("Pause"); tick();}
            });
            video.setOnCompletionListener(player -> {play.setText("Replay"); seek.setProgress(1000);});
            video.setOnErrorListener((player,what,extra) -> {stopPlayback(); status.setText("Android cannot play this video format. Save it or open it with Share."); return true;});
            video.setVideoURI(local);
        } else {
            try {
                audio = new MediaPlayer();
                audio.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                audio.setDataSource(this,local);
                audio.setOnPreparedListener(player -> {if(generation != playbackGeneration || audio != player) return; preparing=false; play.setEnabled(true); if(resumed && !AppLock.isLocked(this)) {player.start(); play.setText("Pause"); tick();}});
                audio.setOnCompletionListener(player -> {play.setText("Replay"); seek.setProgress(1000);});
                audio.setOnErrorListener((player,what,extra) -> {stopPlayback(); status.setText("Android cannot play this audio format. Save it or open it with Share."); return true;});
                audio.prepareAsync();
            } catch (IOException | RuntimeException failed) {stopPlayback(); fail(failed);}
        }
    }

    private void tick() {
        main.removeCallbacks(progress);
        if (resumed) main.postDelayed(progress,300);
    }
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            try {
                int duration = video != null ? video.getDuration() : audio != null ? audio.getDuration() : 0;
                int position = video != null ? video.getCurrentPosition() : audio != null ? audio.getCurrentPosition() : 0;
                if (duration > 0 && !seeking) seek.setProgress((int)((long)position * 1000 / duration));
                time.setText(clock(position) + " / " + clock(duration));
                if (resumed && ((video != null && video.isPlaying()) || (audio != null && audio.isPlaying()))) tick();
            } catch (IllegalStateException ignored) { }
        }
    };

    private void stopPlayback() {
        playbackGeneration++;
        main.removeCallbacks(progress);
        if (video != null) {video.stopPlayback(); canvas.removeView(video); video=null;}
        if (audio != null) {audio.release(); audio=null;}
        preparing=false;
        if (play != null) {play.setEnabled(true); play.setText("Play");}
    }

    private void chooseSave() {
        if(info == null || AppLock.isLocked(this)) return;
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(info.optString("mime","application/octet-stream"))
                    .putExtra(Intent.EXTRA_TITLE,WorkspaceMediaPaths.safeName(info.optString("name"))),SAVE);
        } catch (RuntimeException missing) {fail(new IOException("No Android save picker is available."));}
    }

    private void shareCopy() {
        if(info == null || AppLock.isLocked(this)) return;
        share.setEnabled(false); status.setText("Preparing a read-only copy to share…");
        worker.execute(() -> {
            try {
                Uri uri = shared != null ? WorkspaceMedia.shareCopy(this,shared) : WorkspaceMedia.contentUri(this,project,relative);
                main.post(() -> {
                    if (isDestroyed()) return;
                    share.setEnabled(true);
                    if (!resumed || AppLock.isLocked(this)) {status.setText("Copy prepared. Tap Share after unlocking."); return;}
                    Intent intent = new Intent(Intent.ACTION_SEND).setType(info.optString("mime","application/octet-stream"))
                            .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.setClipData(ClipData.newUri(getContentResolver(),info.optString("name"),uri));
                    try {startActivity(Intent.createChooser(intent,"Share project file"));}
                    catch (RuntimeException unavailable) {fail(new IOException("No app can receive this file."));}
                });
            } catch (IOException | RuntimeException error) {main.post(() -> {share.setEnabled(true); fail(error);});}
        });
    }

    private void finishSave() {
        if(pendingSave == null || AppLock.isLocked(this)) return;
        Uri target = pendingSave; pendingSave=null; status.setText("Saving selected file…"); save.setEnabled(false);
        worker.execute(() -> {
            try {if (shared != null) WorkspaceMedia.saveShared(this,shared,target); else WorkspaceMedia.saveTo(this,project,relative,target); main.post(() -> {if(!isDestroyed()) {status.setText("Copy saved to your selected destination."); save.setEnabled(true);}});}
            catch (IOException | RuntimeException error) {main.post(() -> {if(!isDestroyed()) save.setEnabled(true); fail(error);});}
        });
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        if(AppLock.handleResult(this,lockRoot,request,result,this::unlocked)) return;
        super.onActivityResult(request,result,data);
        if(request==SAVE && result==RESULT_OK && data!=null) {pendingSave=data.getData(); if(resumed) finishSave();}
    }
    @Override protected void onSaveInstanceState(Bundle state) {super.onSaveInstanceState(state); if(pendingSave!=null) state.putString("pending_save",pendingSave.toString());}
    @Override protected void onResume() {
        super.onResume(); resumed=true; AppLock.applyWindowSecurity(this);
        if(lockRoot==null) return;
        if(AppLock.isLocked(this)) {root.setVisibility(View.INVISIBLE); AppLock.show(this,lockRoot,this::unlocked);} else unlocked();
    }
    private void unlocked() {if(AppLock.isLocked(this) || root==null) return; root.setVisibility(View.VISIBLE); load(); if(svgMarkup != null && svgView == null) render(); finishSave();}
    @Override protected void onPause() {resumed=false; releaseSvg(); stopPlayback(); super.onPause();}
    @Override protected void onDestroy() {releaseSvg(); stopPlayback(); worker.shutdownNow(); main.removeCallbacksAndMessages(null); if(bitmap!=null) bitmap.recycle(); super.onDestroy();}

    private void releaseSvg() {if(svgView != null) {SafeSvgView previous=svgView;svgView=null;if(canvas!=null)canvas.removeView(previous);previous.release();}}

    private void fail(Throwable error) {main.post(() -> {if(status!=null && !isDestroyed()) status.setText(error.getMessage()==null ? "This file operation could not finish." : error.getMessage());});}
    private Button button(String text) {Button button=new Button(this); button.setText(text); button.setAllCaps(false); button.setTextColor(DeskStyle.ACCENT); button.setTextSize(14); button.setMinWidth(0); button.setBackground(DeskStyle.field(this)); return button;}
    private int dp(float value) {return Ui.dp(this,value);}
    private static String humanSize(long size) {return size>=1024*1024 ? String.format(Locale.ROOT,"%.1f MB",size/(1024.0*1024)) : size>=1024 ? String.format(Locale.ROOT,"%.1f KB",size/1024.0) : size+" bytes";}
    private static String clock(int millis) {int seconds=Math.max(0,millis)/1000; return String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}

    private final class ZoomImage extends ImageView {
        private final Matrix matrix = new Matrix();
        private final ScaleGestureDetector pinch;
        private float zoom=1, x, y;
        ZoomImage() {
            super(MediaPreviewActivity.this); setScaleType(ScaleType.MATRIX);
            pinch=new ScaleGestureDetector(MediaPreviewActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    float next=Math.max(1,Math.min(5,zoom*detector.getScaleFactor()));
                    matrix.postScale(next/zoom,next/zoom,detector.getFocusX(),detector.getFocusY()); zoom=next; setImageMatrix(matrix); return true;
                }
            });
        }
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
            super.onSizeChanged(w,h,oldw,oldh); if(bitmap==null) return;
            float scale=Math.min((float)w/bitmap.getWidth(),(float)h/bitmap.getHeight());
            matrix.reset(); matrix.postScale(scale,scale); matrix.postTranslate((w-bitmap.getWidth()*scale)/2,(h-bitmap.getHeight()*scale)/2); zoom=1; setImageMatrix(matrix);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if(AppLock.isLocked(MediaPreviewActivity.this)) return false;
            pinch.onTouchEvent(event);
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE && !pinch.isInProgress() && zoom>1) {matrix.postTranslate(event.getX()-x,event.getY()-y); setImageMatrix(matrix);}
            x=event.getX(); y=event.getY();
            if(event.getActionMasked()==MotionEvent.ACTION_UP) performClick();
            return true;
        }
        @Override public boolean performClick() {super.performClick(); return true;}
    }
}
