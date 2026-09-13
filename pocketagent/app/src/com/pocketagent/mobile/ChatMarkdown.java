package com.pocketagent.mobile;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.View;

/** Native text formatting only: no HTML execution or automatic network requests. */
final class ChatMarkdown {
    interface Open { void link(String value); }
    /** Kept for incremental streaming TextViews and other existing callers. */
    static CharSequence render(String raw,int codeColor,int linkColor,Open open) {
        SpannableStringBuilder out=new SpannableStringBuilder();
        for(MarkdownBlocks.Block block:MarkdownBlocks.parse(raw)) {
            if(block.code) {
                int start=out.length();out.append(block.text);
                span(out,new TypefaceSpan("monospace"),start,out.length());
                span(out,new BackgroundColorSpan(codeColor),start,out.length());
            } else append(out,MarkdownBlocks.document(block.text),codeColor,linkColor,open);
        }
        return out;
    }
    static CharSequence prose(String raw,int codeColor,int linkColor,Open open) {
        SpannableStringBuilder out=new SpannableStringBuilder();
        append(out,MarkdownBlocks.document(raw),codeColor,linkColor,open);return out;
    }
    private static void append(SpannableStringBuilder out,MarkdownBlocks.Document document,int codeColor,int linkColor,Open open) {
        int base=out.length();out.append(document.text);
        for(MarkdownBlocks.Mark mark:document.marks) {
            int start=base+mark.start,end=base+mark.end;
            switch(mark.kind) {
                case MarkdownBlocks.BOLD:span(out,new StyleSpan(Typeface.BOLD),start,end);break;
                case MarkdownBlocks.ITALIC:span(out,new StyleSpan(Typeface.ITALIC),start,end);break;
                case MarkdownBlocks.HEADING:span(out,new StyleSpan(Typeface.BOLD),start,end);span(out,new RelativeSizeSpan(1.12f),start,end);break;
                case MarkdownBlocks.STRIKE:span(out,new StrikethroughSpan(),start,end);break;
                case MarkdownBlocks.CODE:span(out,new TypefaceSpan("monospace"),start,end);span(out,new BackgroundColorSpan(codeColor),start,end);break;
                case MarkdownBlocks.QUOTE:span(out,new QuoteSpan(linkColor),start,end);break;
                case MarkdownBlocks.LINK:
                    final String target=mark.target;
                    if(open!=null)span(out,new ClickableSpan(){
                        @Override public void onClick(View widget){open.link(target);}
                        @Override public void updateDrawState(TextPaint paint){paint.setColor(linkColor);paint.setUnderlineText(true);}
                    },start,end);
                    break;
                default:break;
            }
        }
    }
    private static void span(SpannableStringBuilder out,Object span,int start,int end){if(end>start)out.setSpan(span,start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
    private ChatMarkdown(){}
}
