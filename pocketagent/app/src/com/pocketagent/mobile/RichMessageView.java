package com.pocketagent.mobile;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Mount once for a completed response. Streaming keeps its existing lightweight TextView. */
final class RichMessageView extends LinearLayout {
    interface Actions {
        void copy(String exactCode);
        void save(String suggestedName,String mimeType,String exactCode);
    }
    RichMessageView(Context context,String markdown,boolean dark,ChatMarkdown.Open links,Actions actions) {
        super(context);setOrientation(VERTICAL);
        int index=0;
        for(MarkdownBlocks.Block block:MarkdownBlocks.parse(markdown)) {
            if(block.code)addCode(context,block,++index,dark,actions);
            else if(!block.text.trim().isEmpty()) {
                TextView prose=Ui.text(context,ChatMarkdown.prose(trimBoundaryNewlines(block.text),Ui.field(dark),Ui.accent(dark),links),16,Ui.text(dark));
                prose.setTextIsSelectable(true);prose.setMovementMethod(LinkMovementMethod.getInstance());
                prose.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
                prose.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
                addView(prose,new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
    }
    private void addCode(Context context,MarkdownBlocks.Block block,int index,boolean dark,Actions actions) {
        LinearLayout card=new LinearLayout(context);card.setOrientation(VERTICAL);
        card.setBackground(Ui.outlined(Ui.field(dark),Ui.line(dark),12,context));
        card.setClipToOutline(true);
        LayoutParams cardParams=new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0,Ui.dp(context,10),0,Ui.dp(context,10));
        addView(card,cardParams);
        LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(context,12),0,Ui.dp(context,4),0);
        TextView language=Ui.text(context,block.language.isEmpty()?"Code":block.language,12,Ui.muted(dark));
        language.setSingleLine(true);language.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(language,new LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        Button copy=action(context,"Copy","copy",dark);copy.setContentDescription("Copy code");
        copy.setOnClickListener(v->{if(actions!=null)actions.copy(block.text);});
        header.addView(copy,new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(context,48)));
        Button save=action(context,"Save","download",dark);
        save.setContentDescription("Save code as ."+MarkdownBlocks.extension(block.language));
        save.setOnClickListener(v->{if(actions!=null)actions.save(MarkdownBlocks.suggestedName(block.language,index),MarkdownBlocks.mimeType(block.language),block.text);});
        header.addView(save,new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,Ui.dp(context,48)));
        card.addView(header,new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        android.view.View divider=new android.view.View(context);divider.setBackgroundColor(Ui.line(dark));
        card.addView(divider,new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,Ui.dp(context,1)));
        HorizontalScrollView scroll=new HorizontalScrollView(context);scroll.setFillViewport(true);
        TextView code=Ui.text(context,trimLastNewline(block.text),13,Ui.text(dark));
        code.setTypeface(Typeface.MONOSPACE);code.setTextIsSelectable(true);code.setHorizontallyScrolling(true);
        code.setPadding(Ui.dp(context,12),Ui.dp(context,12),Ui.dp(context,12),Ui.dp(context,12));
        scroll.addView(code,new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll,new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    private static Button action(Context context,String title,String iconName,boolean dark) {
        Button view=new Button(context);view.setText(title);view.setTextSize(12);view.setAllCaps(false);view.setTextColor(Ui.text(dark));
        view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));view.setGravity(Gravity.CENTER);
        view.setMinHeight(Ui.dp(context,48));view.setMinimumHeight(Ui.dp(context,48));view.setMinWidth(Ui.dp(context,48));view.setMinimumWidth(Ui.dp(context,48));
        view.setPadding(Ui.dp(context,8),0,Ui.dp(context,8),0);view.setStateListAnimator(null);
        view.setBackground(Ui.tappable(context,Ui.background(android.graphics.Color.TRANSPARENT,8,context),dark));
        Drawable icon=DeskStyle.icon(context,iconName,Ui.muted(dark));
        icon.setBounds(0,0,Ui.dp(context,16),Ui.dp(context,16));view.setCompoundDrawablesRelative(icon,null,null,null);view.setCompoundDrawablePadding(Ui.dp(context,5));
        return view;
    }
    private static String trimLastNewline(String value) {
        if(value.endsWith("\r\n"))return value.substring(0,value.length()-2);
        if(value.endsWith("\n"))return value.substring(0,value.length()-1);return value;
    }
    private static String trimBoundaryNewlines(String value) {
        int start=0,end=value.length();while(start<end&&(value.charAt(start)=='\n'||value.charAt(start)=='\r'))start++;
        while(end>start&&(value.charAt(end-1)=='\n'||value.charAt(end-1)=='\r'))end--;
        return value.substring(start,end);
    }
}
