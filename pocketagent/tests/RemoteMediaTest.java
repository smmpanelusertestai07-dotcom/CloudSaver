package com.pocketagent.mobile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Real portable safety rules and HTTP body fixtures; no live third-party requests. */
public final class RemoteMediaTest {
    private static int assertions;
    interface Checked { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        eq(RemoteMediaPolicy.uri("https://cdn.example.com/a.png?signature=abc").getHost(),"cdn.example.com","signed public URL accepted without copying credentials elsewhere");
        for(String value:new String[]{"http://example.com/a.png","file:///private/a.png","content://phone/1","data:image/png;base64,AQ==",
                "https://user:secret@example.com/a.png","https://example.com:444/a.png","https://localhost/a.png","https://machine.local/a.png",
                "https://127.0.0.1/a.png","https://2130706433/a.png","https://0x7f000001/a.png","https://[::1]/a.png","https://example.com./a.png",
                "https://example.com\\@internal/a.png","https://example.com/a.png#token","https://example.com/a\r\nHeader: x"})reject(()->RemoteMediaPolicy.uri(value),"unsafe URL");
        for(String ip:new String[]{"127.0.0.1","0.0.0.0","10.1.2.3","172.16.1.1","192.168.1.1","169.254.169.254","100.64.0.1",
                "192.0.0.1","192.0.2.1","198.18.0.1","198.51.100.5","203.0.113.2","224.1.1.1","255.255.255.255","::1","::","fc00::1","fe80::1","2002:7f00:1::","2001:db8::1"})
            check(!RemoteMediaPolicy.publicAddress(InetAddress.getByName(ip)),"private/reserved IP rejected: "+ip);
        check(RemoteMediaPolicy.publicAddress(InetAddress.getByName("8.8.8.8")),"public IPv4 accepted");
        check(RemoteMediaPolicy.publicAddress(InetAddress.getByName("2606:4700:4700::1111")),"public IPv6 accepted");
        List<RemoteMediaPolicy.Link> links=RemoteMediaPolicy.links("![Logo](https://example.com/render?id=1) [Clip](https://example.com/a.mp4) https://example.com/a.mp4 ![](https://example.com/a.png) [Page](https://example.com/article)");
        eq(links.size(),3,"image declaration, video and image deduplicated; ordinary page not embedded");
        check(links.get(0).name.equals("Logo"),"image alt used for card");
        check(links.get(0).image,"explicit image without extension is eligible for automatic preview");
        check(!links.get(1).image,"video references do not start automatic downloads");
        check(links.get(2).image,"direct image references are eligible for automatic preview");
        for (String suffix : new String[]{"pdf", "zip", "apk", "txt", "mp4", "html"})
            check(!new RemoteMediaPolicy.Link("https://example.com/file."+suffix,null).image,"non-image does not auto-fetch: "+suffix);
        check(RemoteMediaPolicy.directImage("https://example.com/photo.PNG?signature=abc"),"signed uppercase image extension recognized");
        check(!RemoteMediaPolicy.directImage("https://example.com/page?photo=image.png"),"image-looking query is not an image reference");
        check(!RemoteMediaPolicy.directImage("https://localhost/private.png"),"automatic image discovery retains local-host rejection");
        List<RemoteMediaPolicy.Link> repeated=RemoteMediaPolicy.links("![Named image](https://example.com/logo.png) https://example.com/logo.png");
        eq(repeated.size(),1,"image and raw URL deduplicated");
        eq(repeated.get(0).name,"Named image","automatic raw URL pass retains image label");
        eq(RemoteMediaPolicy.links("```js\n![not output](https://example.com/a.png)\n```\n`https://example.com/b.png`").size(),0,"code examples do not become media");
        eq(RemoteMediaPolicy.links("[Image](<https://example.com/a.png> \"title\")").size(),1,"angle target and title supported");
        StringBuilder many=new StringBuilder();for(int i=0;i<12;i++)many.append("![x](https://example.com/").append(i).append(".png) ");
        eq(RemoteMediaPolicy.links(many.toString()).size(),4,"per-message media count bounded");
        check(!RemoteMediaPolicy.directFile("https://example.com/watch?v=1"),"web page never assumed to be playable video");
        check(RemoteMediaPolicy.directFile("https://example.com/export.zip?sig=abc"),"direct archive download discovered");
        check(RemoteMediaPolicy.directFile("https://example.com/Main.java?sig=abc"),"source file download discovered");
        check(!RemoteMediaPolicy.directFile("https://example.com/index.html"),"ordinary web pages still open in browser");
        java.net.URI download=new java.net.URI("https://example.com/download?signature=private");
        eq(RemoteMediaFetch.filename(download,"attachment; filename=\"calculator.apk\"",null),"calculator.apk","Content-Disposition preserves APK name");
        eq(RemoteMediaFetch.filename(download,"attachment; filename*=UTF-8''my%20app.apk",null),"my app.apk","extended download filename decoded");
        eq(RemoteMediaFetch.filename(download,"attachment; filename*=UTF-8''C%2B%2B.zip",null),"C__.zip","plus characters do not become spaces");
        eq(RemoteMediaFetch.filename(download,"attachment; filename=\"../../app.apk\"",null),"app.apk","download filename traversal stripped");
        eq(RemoteMediaFetch.filename(download,null,"application/vnd.android.package-archive"),"download.apk","extensionless APK endpoint retains installable filename");
        eq(RemoteMediaFetch.filename(download,null,"application/zip; charset=binary"),"download.zip","archive content type supplies absent extension");
        eq(RemoteMediaFetch.filename(new java.net.URI("https://example.com/tool.jar"),null,null),"tool.jar","arbitrary extension remains exact on deliberate download");
        RemoteMediaFetch.Header header=RemoteMediaFetch.header(bytes("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Type: image/png\r\n\r\n"));
        eq(header.status,200,"HTTP status parsed");eq(header.values.get("content-type"),"image/png","header names normalized");
        reject(()->RemoteMediaFetch.header(bytes("HTTP/1.1 200 OK\r\nContent-Length: 3\r\ncontent-length: 4\r\n\r\n")),"duplicate content lengths rejected");
        reject(()->RemoteMediaFetch.header(bytes("HTTP/1.1 302 Found\r\nLocation: /a\r\nLocation: /b\r\n\r\n")),"ambiguous redirect rejected");
        reject(()->RemoteMediaFetch.header(bytes("HTTP/1.1 200 OK\r\n Transfer-Encoding: chunked\r\n\r\n")),"folded header rejected");
        reject(()->RemoteMediaFetch.header(bytes("HTTP/1.1 200 OK\nX: bad\n\n")),"bare LF rejected");
        reject(()->RemoteMediaFetch.header(bytes("HTTP/1.1 200 OK\r\nX: "+repeat("x",16400)+"\r\n\r\n")),"header budget bounded");
        ByteArrayOutputStream body=new ByteArrayOutputStream();RemoteMediaFetch.Capture capture=capture(body,4);
        RemoteMediaFetch.chunked(bytes("2\r\nab\r\n2\r\ncd\r\n0\r\n\r\n"),capture);eq(body.toString("UTF-8"),"abcd","actual chunked bytes copied");
        reject(()->RemoteMediaFetch.chunked(bytes("5\r\nabcde\r\n0\r\n\r\n"),capture(new ByteArrayOutputStream(),4)),"oversize chunk rejected before output");
        reject(()->RemoteMediaFetch.chunked(bytes("-1\r\n"),capture(new ByteArrayOutputStream(),4)),"negative chunk rejected");
        reject(()->RemoteMediaFetch.chunked(bytes("g\r\n"),capture(new ByteArrayOutputStream(),4)),"malformed chunk rejected");
        reject(()->RemoteMediaFetch.exact(bytes("ab"),capture(new ByteArrayOutputStream(),4),3),"truncated content length fails");
        body.reset();RemoteMediaFetch.exact(bytes("abcdignored"),capture(body,4),4);eq(body.toString("UTF-8"),"abcd","content length enforced");
        reject(()->RemoteMediaFetch.copy(bytes("abcde"),capture(new ByteArrayOutputStream(),4)),"unknown-length body bounded");
        reject(()->RemoteMediaFetch.copy(bytes("a"),new RemoteMediaFetch.Capture(new ByteArrayOutputStream(),4,System.nanoTime()-1)),"total deadline enforced");
        Thread.currentThread().interrupt();reject(()->RemoteMediaFetch.copy(bytes("a"),capture(new ByteArrayOutputStream(),4)),"cancellation stops body copy");Thread.interrupted();
        System.out.println("PASS RemoteMedia: "+assertions+" assertions (untrusted URLs, public IPs, markdown, header/chunk budgets, truncation, cancellation)");
    }
    private static RemoteMediaFetch.Capture capture(ByteArrayOutputStream output,long max){return new RemoteMediaFetch.Capture(output,max,System.nanoTime()+10_000_000_000L);}
    private static ByteArrayInputStream bytes(String value){return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));}
    private static String repeat(String value,int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(value);return out.toString();}
    private static void reject(Checked action,String label)throws Exception{try{action.run();}catch(IOException expected){assertions++;return;}throw new AssertionError(label+" was accepted");}
    private static void eq(Object actual,Object expected,String label){check(expected.equals(actual),label+": expected "+expected+", got "+actual);}
    private static void check(boolean condition,String label){assertions++;if(!condition)throw new AssertionError(label);}
}
