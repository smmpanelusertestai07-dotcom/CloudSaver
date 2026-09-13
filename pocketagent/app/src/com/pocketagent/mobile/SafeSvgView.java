package com.pocketagent.mobile;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Sealed, noninteractive renderer for SafeSvg's normalized shapes, never raw SVG or HTML. */
final class SafeSvgView extends WebView {
    SafeSvgView(Context context,String normalized){
        super(context);
        setBackgroundColor(Color.WHITE);setFocusable(false);setFocusableInTouchMode(false);
        WebSettings settings=getSettings();settings.setJavaScriptEnabled(false);settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);settings.setBlockNetworkImage(true);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setGeolocationEnabled(false);settings.setSaveFormData(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportZoom(false);settings.setBuiltInZoomControls(false);settings.setDisplayZoomControls(false);
        if(Build.VERSION.SDK_INT>=26)settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this,false);
        setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(PermissionRequest request){request.deny();}});
        setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return blocked();}
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,String url){return blocked();}
        });
        setOnLongClickListener(view->true);setLongClickable(false);setOnTouchListener((view,event)->true);
        String html="<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; media-src 'none'; connect-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'\"><style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#ffffff}svg{display:block;width:100%;height:100%}</style></head><body>"+normalized+"</body></html>";
        loadDataWithBaseURL("https://pocketagent-svg.invalid/",html,"text/html","UTF-8",null);
    }
    private static WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",java.util.Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    void release(){stopLoading();onPause();removeAllViews();destroy();}
}
