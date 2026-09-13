package com.pocketagent.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/** Native, cached-first media. Visible image references load automatically using public HTTPS only. */
final class RemoteMediaCards {
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(2,2,20,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(8));
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final LinkedHashMap<String,Uri> CACHE=new LinkedHashMap<String,Uri>(24,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Uri> entry){return size()>24;}
    };
    private static final LinkedHashMap<String,Boolean> KNOWN=new LinkedHashMap<String,Boolean>(32,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Boolean> entry){return size()>32;}
    };
    static int append(Activity activity,LinearLayout parent,String markdown,BooleanSupplier current){return append(activity,parent,markdown,null,current);}
    static int append(Activity activity,LinearLayout parent,String markdown,JSONArray media,BooleanSupplier current){
        int cachedCount=0;java.util.HashSet<String> cachedUrls=new java.util.HashSet<>();
        if(media!=null)for(int i=0;i<media.length()&&cachedCount<4;i++) {
            JSONObject item=media.optJSONObject(i);if(item==null||item.optString("cachedUri").isEmpty())continue;
            try {String raw=item.optString("cachedUri");WorkspaceMediaPaths.shareToken(activity.getPackageName()+".projectfiles",raw);
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=Ui.dp(activity,10);
                parent.addView(new CachedCard(activity,Uri.parse(raw),current),params);cachedCount++;if(!item.optString("url").isEmpty())cachedUrls.add(item.optString("url"));
            }catch(java.io.IOException ignored){}
        }
        List<RemoteMediaPolicy.Link> links=new ArrayList<>(RemoteMediaPolicy.links(markdown));
        if(media!=null)for(int i=0;i<media.length()&&links.size()<4;i++){
            JSONObject item=media.optJSONObject(i);if(item==null||cachedUrls.contains(item.optString("url")))continue;
            try{RemoteMediaPolicy.Link link=new RemoteMediaPolicy.Link(item.optString("url"),item.optString("name"),"image".equals(item.optString("kind"))||item.optString("mime").startsWith("image/"));boolean duplicate=false;for(RemoteMediaPolicy.Link existing:links)if(existing.url.equals(link.url))duplicate=true;if(!duplicate)links.add(link);}catch(java.io.IOException ignored){}
        }
        int remoteCount=0;for(RemoteMediaPolicy.Link link:links){if(remoteCount+cachedCount>=4)break;if(cachedUrls.contains(link.url))continue;remoteCount++;KNOWN.put(link.url,true);Card card=new Card(activity,link,current);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=Ui.dp(activity,10);parent.addView(card,params);}
        return remoteCount+cachedCount;
    }
    static boolean openLink(Activity activity,String url){
        if(!RemoteMediaPolicy.directFile(url)&&!KNOWN.containsKey(url))return false;
        if(AppLock.isLocked(activity))return true;
        try{
            RemoteMediaPolicy.Link link=new RemoteMediaPolicy.Link(url,null);
            LinearLayout body=new LinearLayout(activity);body.setPadding(Ui.dp(activity,16),0,Ui.dp(activity,16),Ui.dp(activity,12));
            AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("File preview").setView(body).setNegativeButton("Close",null).create();
            body.addView(new Card(activity,link,()->dialog.isShowing()&&!AppLock.isLocked(activity)),new LinearLayout.LayoutParams(-1,-2));dialog.show();
            if(dialog.getWindow()!=null)dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            android.app.Application.ActivityLifecycleCallbacks lifecycle=new android.app.Application.ActivityLifecycleCallbacks(){
                public void onActivityCreated(Activity owner,android.os.Bundle state){}
                public void onActivityStarted(Activity owner){}
                public void onActivityResumed(Activity owner){}
                public void onActivityPaused(Activity owner){if(owner==activity)dialog.dismiss();}
                public void onActivityStopped(Activity owner){}
                public void onActivitySaveInstanceState(Activity owner,android.os.Bundle state){}
                public void onActivityDestroyed(Activity owner){if(owner==activity)dialog.dismiss();}
            };
            activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
            dialog.setOnDismissListener(d->activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle));
        }catch(java.io.IOException ignored){}
        return true;
    }
    private static final class Card extends LinearLayout {
        final Activity activity;final RemoteMediaPolicy.Link link;final BooleanSupplier current;
        final TextView detail;final Button action;final ImageView image;
        Uri loaded;Bitmap bitmap;SafeSvgView svgView;Future<?> task;boolean detached,attempted,retryQueued;int loadGeneration;
        final android.view.ViewTreeObserver.OnPreDrawListener visibleCheck=()->{maybeLoad();return true;};
        Card(Activity activity,RemoteMediaPolicy.Link link,BooleanSupplier current){
            super(activity);this.activity=activity;this.link=link;this.current=current;
            setOrientation(VERTICAL);setPadding(dp(12),dp(10),dp(12),dp(8));setBackground(DeskStyle.field(activity));
            TextView title=Ui.text(activity,link.name,14,DeskStyle.TEXT);title.setMaxLines(2);addView(title);
            detail=Ui.text(activity,link.host+(link.image?" · Image":" · File"),12,DeskStyle.MUTED);detail.setPadding(0,dp(5),0,dp(5));addView(detail);
            image=new ImageView(activity);image.setVisibility(link.image?VISIBLE:GONE);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("Image preview: "+link.name);addView(image,new LayoutParams(-1,dp(190)));
            action=new Button(activity);action.setText(link.image?"Loading image…":"Open file");action.setAllCaps(false);action.setTextSize(13);action.setTextColor(DeskStyle.TEXT);action.setBackground(DeskStyle.plain(activity));addView(action,new LayoutParams(-1,dp(48)));
            action.setOnClickListener(v->{if(!valid())return;if(loaded!=null){WorkspaceMedia.openSharedPreview(activity,loaded);return;}load(true);});
            if(link.image)action.setVisibility(GONE);
            image.setOnClickListener(v->{if(valid()&&loaded!=null)WorkspaceMedia.openSharedPreview(activity,loaded);});
            addOnAttachStateChangeListener(new OnAttachStateChangeListener(){
                @Override public void onViewAttachedToWindow(View view){detached=false;getViewTreeObserver().addOnPreDrawListener(visibleCheck);maybeLoad();}
                @Override public void onViewDetachedFromWindow(View view){detached=true;loadGeneration++;attempted=false;getViewTreeObserver().removeOnPreDrawListener(visibleCheck);if(task!=null)task.cancel(true);task=null;WORKER.purge();image.setImageDrawable(null);if(bitmap!=null){bitmap.recycle();bitmap=null;}if(svgView!=null){SafeSvgView previous=svgView;svgView=null;removeView(previous);previous.release();}}
            });
        }
        boolean valid(){return !detached&&!activity.isFinishing()&&!activity.isDestroyed()&&!AppLock.isLocked(activity)&&current.getAsBoolean();}
        void maybeLoad(){
            if(!valid())return;
            if(!getGlobalVisibleRect(new Rect())){
                if(bitmap!=null||svgView!=null){image.setImageDrawable(null);if(bitmap!=null){bitmap.recycle();bitmap=null;}
                    if(svgView!=null){SafeSvgView old=svgView;svgView=null;removeView(old);old.release();image.setVisibility(VISIBLE);}
                    attempted=false;}
                return;
            }
            if(!attempted)load(link.image);
        }
        void load(boolean network){
            if(!valid()||task!=null&&!task.isDone())return;
            attempted=true;final int ticket=++loadGeneration;
            action.setEnabled(false);action.setText(network?"Loading…":"Opening…");
            // Automatic image requests use the visible source host, without cookies, referrers or account tokens.
            Uri cached=CACHE.get(link.url);
            try{task=WORKER.submit(()->{
                try{
                    Uri uri=cached;JSONObject info=null;
                    if(uri!=null)try{info=WorkspaceMedia.describeShared(activity,uri);}catch(java.io.IOException expired){uri=null;}
                    if(uri==null){uri=WorkspaceMedia.savedRemote(activity,link.url);if(uri!=null)info=WorkspaceMedia.describeShared(activity,uri);}
                    if(uri==null&&!network){MAIN.post(()->{if(valid()&&ticket==loadGeneration){action.setEnabled(true);action.setText("Open file");detail.setText(link.host);}});return;}
                    if(uri==null){uri=link.image?WorkspaceMedia.remoteImageCopy(activity,link.url):WorkspaceMedia.remoteCopy(activity,link.url);info=WorkspaceMedia.describeShared(activity,uri);}
                    if(link.image&&!"image".equals(info.optString("kind")))throw new java.io.IOException("This link is not an image.");
                    Bitmap decoded=null;if("image".equals(info.optString("kind")))try{decoded=WorkspaceMedia.thumbnailShared(activity,uri,768);}catch(java.io.IOException ignored){}
                    String svg=null;if(WorkspaceMedia.isSvg(info))try{svg=WorkspaceMedia.svgShared(activity,uri);}catch(java.io.IOException ignored){}
                    final String svgMarkup=svg;
                    final Uri ready=uri;final JSONObject metadata=info;final Bitmap preview=decoded;
                    MAIN.post(()->{if(!valid()||ticket!=loadGeneration){if(preview!=null)preview.recycle();return;}loaded=ready;CACHE.put(link.url,ready);bitmap=preview;
                        if(preview!=null){image.setImageBitmap(preview);image.setVisibility(VISIBLE);}
                        else if(svgMarkup!=null){try{svgView=new SafeSvgView(activity,svgMarkup);image.setVisibility(GONE);svgView.setContentDescription("Image preview: "+link.name);svgView.setOnClickListener(v->{if(valid()&&loaded!=null)WorkspaceMedia.openSharedPreview(activity,loaded);});addView(svgView,indexOfChild(action),new LayoutParams(-1,dp(190)));}catch(RuntimeException unavailable){svgView=null;}}
                        String kind=metadata.optString("kind");detail.setText(link.host+" · "+humanSize(metadata.optLong("size")));
                        action.setText(kind.equals("video")||kind.equals("audio")?"Play":"Open file");action.setEnabled(true);
                        action.setVisibility(preview!=null||svgView!=null?GONE:VISIBLE);
                    });
                }catch(java.io.IOException|RuntimeException error){MAIN.post(()->{if(!valid()||ticket!=loadGeneration)return;CACHE.remove(link.url);detail.setText(error.getMessage()==null?"Preview unavailable.":error.getMessage());action.setEnabled(true);action.setText("Retry");action.setVisibility(VISIBLE);});}
            });}catch(RejectedExecutionException busy){
                attempted=false;task=null;
                if(!retryQueued){retryQueued=true;MAIN.postDelayed(()->{retryQueued=false;maybeLoad();},500);}
            }
        }
        private int dp(int value){return Ui.dp(activity,value);}
    }
    private static final class CachedCard extends LinearLayout {
        final Activity activity;final Uri uri;final BooleanSupplier current;final ImageView image;final TextView detail;final Button open;
        Bitmap bitmap;Future<?> task;boolean detached,attempted,retryQueued;int generation;
        final android.view.ViewTreeObserver.OnPreDrawListener visibleCheck=()->{maybeLoad();return true;};
        CachedCard(Activity activity,Uri uri,BooleanSupplier current){
            super(activity);this.activity=activity;this.uri=uri;this.current=current;setOrientation(VERTICAL);int pad=Ui.dp(activity,12);setPadding(pad,pad,pad,pad);setBackground(DeskStyle.field(activity));
            detail=Ui.text(activity,"Image",13,DeskStyle.MUTED);addView(detail);
            image=new ImageView(activity);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("Agent image output");addView(image,new LayoutParams(-1,Ui.dp(activity,190)));
            open=new Button(activity);open.setText("Open image");open.setAllCaps(false);open.setTextColor(DeskStyle.TEXT);open.setBackground(DeskStyle.plain(activity));addView(open,new LayoutParams(-1,Ui.dp(activity,48)));open.setVisibility(GONE);
            open.setOnClickListener(v->{if(valid())WorkspaceMedia.openSharedPreview(activity,uri);});image.setOnClickListener(v->{if(valid())WorkspaceMedia.openSharedPreview(activity,uri);});
            addOnAttachStateChangeListener(new OnAttachStateChangeListener(){
                public void onViewAttachedToWindow(View view){detached=false;getViewTreeObserver().addOnPreDrawListener(visibleCheck);maybeLoad();}
                public void onViewDetachedFromWindow(View view){detached=true;generation++;attempted=false;getViewTreeObserver().removeOnPreDrawListener(visibleCheck);if(task!=null)task.cancel(true);task=null;WORKER.purge();image.setImageDrawable(null);if(bitmap!=null){bitmap.recycle();bitmap=null;}}
            });
        }
        boolean valid(){return !detached&&!activity.isFinishing()&&!activity.isDestroyed()&&!AppLock.isLocked(activity)&&current.getAsBoolean();}
        void maybeLoad(){if(!valid())return;if(!getGlobalVisibleRect(new Rect())){if(bitmap!=null){image.setImageDrawable(null);bitmap.recycle();bitmap=null;attempted=false;}return;}if(!attempted)load();}
        void load(){if(!valid()||task!=null&&!task.isDone())return;attempted=true;final int ticket=++generation;try{task=WORKER.submit(()->{try{
            JSONObject info=WorkspaceMedia.describeShared(activity,uri);Bitmap decoded=WorkspaceMedia.thumbnailShared(activity,uri,768);
            MAIN.post(()->{if(!valid()||ticket!=generation){decoded.recycle();return;}bitmap=decoded;image.setImageBitmap(decoded);image.setVisibility(VISIBLE);detail.setText(info.optString("name")+" · "+humanSize(info.optLong("size")));});
        }catch(java.io.IOException|RuntimeException error){MAIN.post(()->{if(valid()&&ticket==generation){detail.setText("Image unavailable. Ask the agent to save a copy in the project.");image.setVisibility(GONE);open.setEnabled(false);}});}});}catch(RejectedExecutionException busy){attempted=false;task=null;if(!retryQueued){retryQueued=true;MAIN.postDelayed(()->{retryQueued=false;maybeLoad();},500);}}}
    }
    private static String humanSize(long size){return size>=1024*1024?String.format(java.util.Locale.ROOT,"%.1f MB",size/(1024.0*1024)):size>=1024?String.format(java.util.Locale.ROOT,"%.0f KB",size/1024.0):size+" B";}
    private RemoteMediaCards(){}
}
