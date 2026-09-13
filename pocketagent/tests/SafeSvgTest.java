package com.pocketagent.mobile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class SafeSvgTest {
    private static int assertions;
    public static void main(String[] args)throws Exception{
        String safe=wrap("<g transform='translate(1 2) scale(.5)'><path fill='currentColor' fill-rule='evenodd' d='M0 0h10v10H0z M2.5 2.5 C3 4 4 3 5 5 Q6 6 7 7 T8 8 A2 2 0 0 1 10 10'/></g>");
        String normalized=clean(safe);
        check(normalized.contains("<path")&&normalized.contains("fill=\"currentColor\""),"logo geometry and currentColor preserved");
        check(normalized.contains("color=\"#202123\"")&&normalized.contains("width=\"100%\""),"explicit viewport and foreground");
        check(!normalized.contains("<?xml")&&!normalized.contains("<!DOCTYPE"),"fresh shape markup only");
        String shapes=clean(wrap("<rect x='1' y='1' width='10' height='10' rx='2' fill='#fff'/><circle cx='5' cy='5' r='3' stroke='rgb(0, 0, 0)'/><ellipse cx='2' cy='2' rx='1' ry='2'/><line x1='0' y1='0' x2='10' y2='10'/><polygon points='0 0 10 0 5 10'/><polyline points='0,0 2,2'/><title>Logo</title>"));
        check(shapes.contains("<polygon")&&shapes.contains("<title>Logo</title>"),"closed shape subset accepted");
        check(!clean("<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' id='Layer_1' viewBox='0 0 10 10'><path d='M0 0L1 1'/></svg>").contains("xlink"),"unused identifiers/namespaces omitted");
        check(clean(wrap("<desc><![CDATA[<script>not markup</script>]]></desc><path d='M0 0L1 1'/>" )).contains("&lt;script&gt;"),"description markup encoded as inert text");
        for(String content:new String[]{"<script>alert(1)</script>","<foreignObject><div>HTML</div></foreignObject>","<image href='https://evil.test/x.png'/>","<use href='#a'/>","<animate attributeName='x'/>","<set attributeName='href'/>","<style>path{fill:url(https://evil.test)}</style>","<a href='https://evil.test'><path d='M0 0L1 1'/></a>","<iframe/>","<video/>","<defs><linearGradient id='g'/></defs>","<svg><path d='M0 0L1 1'/></svg>","<path d='M0 0' onclick='alert(1)'/>","<path d='M0 0' onload='alert(1)'/>","<path d='M0 0' style='fill:red'/>","<path d='M0 0' fill='url(https://evil.test/x)'/>","<path d='M0 0' fill='url(#gradient)'/>","<path d='M0 0' filter='url(#blur)'/>","<path d='M0 0' xlink:href='https://evil.test'/>","<path d='M0 0' class='external'/>","<path d='M0 0L1e99999 1'/>","<path d='M0 0L1000001 2'/>","<rect width='calc(10px)'/>","<rect width='100000000'/>","<g transform='url(https://evil.test)'/>","<path d='M0 0' opacity='100'/>","<path d='M0 0' fill='red; background:url(x)'/>","<path xmlns='http://evil.test' d='M0 0'/>","<?network href='https://evil.test'?>"}) reject(wrap(content),"active or unsupported markup");
        reject("<!DOCTYPE svg [<!ENTITY xxe SYSTEM 'file:///private/auth.json'>]><svg xmlns='http://www.w3.org/2000/svg'><title>&xxe;</title></svg>","external entity blocked before parser");
        reject("<!DOCTYPE svg [<!ENTITY a 'aaaa'><!ENTITY b '&a;&a;'>]><svg xmlns='http://www.w3.org/2000/svg'><title>&b;</title></svg>","entity expansion blocked");
        reject("<?xml-stylesheet href='https://evil.test/style.css'?>"+wrap("<path d='M0 0'/>"),"external stylesheet blocked");
        reject(wrap("<title>&amp;</title>"),"entities rejected by closed subset");
        reject("<html><body>not SVG</body></html>","HTML never reaches renderer");
        reject(wrap("<path d='M0 0'/>")+"<svg/>","multiple roots rejected");
        reject(wrap("<g>".repeat(25)+"</g>".repeat(25)),"tree depth bounded");
        reject(wrap("<path d='M0 0'/>".repeat(1024)),"node count bounded");
        reject(wrap("<path d='M0 0'/>"+" ".repeat(SafeSvg.MAX_BYTES)),"document byte size bounded");
        boolean invalid=false;try{SafeSvg.sanitize(new byte[]{(byte)0xff,(byte)0xfe,0,0},0);}catch(IOException expected){invalid=true;}check(invalid,"non-UTF8 byte streams rejected");
        System.out.println("PASS SafeSvg: "+assertions+" assertions (logo geometry, active/external markup, entities, parser normalization and complexity bounds)");
    }
    private static String wrap(String content){return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'>"+content+"</svg>";}
    private static String clean(String svg)throws IOException{return SafeSvg.sanitize(svg.getBytes(StandardCharsets.UTF_8),0xff202123);}
    private static void reject(String svg,String message)throws Exception{try{clean(svg);}catch(IOException expected){assertions++;return;}throw new AssertionError(message+" was accepted");}
    private static void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
