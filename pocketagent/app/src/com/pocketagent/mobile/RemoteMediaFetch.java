package com.pocketagent.mobile;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Bounded HTTPS GET, with TLS hostname verification and DNS addresses pinned before connecting.
 * No cookie jar, Authorization, browser session, referrer, or automatic redirect handling. */
final class RemoteMediaFetch {
    static final class Result {
        final String mime, name;
        final long size;
        Result(String mime,String name,long size){this.mime=mime;this.name=name;this.size=size;}
    }
    static Result download(String value,OutputStream output,long maximum) throws IOException {
        URI uri=RemoteMediaPolicy.uri(value);
        long deadline=System.nanoTime()+300_000_000_000L;
        for(int redirects=0;redirects<=3;redirects++) {
            check(deadline);
            InetAddress[] addresses=InetAddress.getAllByName(uri.getHost());
            if(addresses.length==0)throw new IOException("Media host is unavailable.");
            for(InetAddress address:addresses)if(!RemoteMediaPolicy.publicAddress(address))throw new IOException("Local network media links are blocked.");
            try(Socket connected=new Socket()) {
                // The connection uses the exact checked IP. TLS still verifies the original hostname.
                connected.connect(new InetSocketAddress(addresses[0],443),timeout(deadline,12000));
                connected.setSoTimeout(timeout(deadline,12000));
                try(SSLSocket socket=(SSLSocket)((SSLSocketFactory)SSLSocketFactory.getDefault()).createSocket(connected,uri.getHost(),443,true)) {
                    SSLParameters parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(parameters);
                    socket.setSoTimeout(timeout(deadline,12000));socket.startHandshake();
                    String path=uri.getRawPath();if(path==null||path.isEmpty())path="/";
                    if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
                    OutputStream request=socket.getOutputStream();
                    request.write(("GET "+path+" HTTP/1.1\r\nHost: "+uri.getHost()+"\r\nUser-Agent: PocketAgent-Media/1\r\nAccept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));request.flush();
                    InputStream input=new BufferedInputStream(socket.getInputStream(),32768);
                    Header header=header(input);
                    if(header.status==301||header.status==302||header.status==303||header.status==307||header.status==308) {
                        if(redirects==3)throw new IOException("Too many media redirects.");
                        String location=header.values.get("location");if(location==null)throw new IOException("The media redirect is missing its destination.");
                        uri=RemoteMediaPolicy.uri(uri.resolve(location).toString());continue;
                    }
                    if(header.status!=200)throw new IOException("Media server returned "+header.status+". The link may have expired.");
                    String encoding=header.values.get("content-encoding");
                    if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new IOException("Compressed media responses are not supported.");
                    String lengthValue=header.values.get("content-length"),transfer=header.values.get("transfer-encoding");
                    long length=-1;
                    if(lengthValue!=null)try{length=Long.parseLong(lengthValue);if(length<0||length>maximum)throw new NumberFormatException();}catch(NumberFormatException e){throw new IOException("Media exceeds the download limit.");}
                    if(transfer!=null&&(!transfer.equalsIgnoreCase("chunked")||lengthValue!=null))throw new IOException("Unsupported media transfer.");
                    String filename=filename(uri,header.values.get("content-disposition"),header.values.get("content-type"));
                    Capture capture=new Capture(output,maximum,deadline);
                    if(transfer!=null)chunked(input,capture);else if(length>=0)exact(input,capture,length);else copy(input,capture);
                    if(capture.total==0)throw new IOException("The media file is empty.");
                    String mime=WorkspaceMediaPaths.mime(capture.prefix.toByteArray(),capture.prefix.size(),filename);
                    String declared=header.values.get("content-type");
                    if(declared!=null)declared=declared.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
                    if(mime.equals("application/octet-stream") && ("image/svg+xml".equals(declared)||"text/plain".equals(declared)))mime=declared;
                    // HTML error/login pages are never presented as images or documents.
                    String prefix=new String(capture.prefix.toByteArray(),StandardCharsets.US_ASCII).trim().toLowerCase(Locale.ROOT);
                    if("text/html".equals(declared)||prefix.startsWith("<!doctype html")||prefix.startsWith("<html"))throw new IOException("This link returned a web page. Open the source link in your browser instead.");
                    return new Result(mime,filename,capture.total);
                }
            }
        }
        throw new IOException("Media download failed.");
    }
    /** Keep APK/archive names even when a signed download URL has no file extension. */
    static String filename(URI uri,String disposition,String contentType) {
        String name=null;
        if(disposition!=null&&disposition.length()<=4096) {
            java.util.regex.Matcher extended=java.util.regex.Pattern.compile("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*UTF-8'[^']*'([^;\\s]+)").matcher(disposition);
            if(extended.find())try{name=java.net.URLDecoder.decode(extended.group(1).replace("+","%2B"),"UTF-8");}catch(Exception ignored){}
            if(name==null) {
                java.util.regex.Matcher regular=java.util.regex.Pattern.compile("(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))").matcher(disposition);
                if(regular.find())name=regular.group(1)!=null?regular.group(1):regular.group(2).trim();
            }
        }
        if(name==null||name.trim().isEmpty())name=uri.getPath();
        if(name==null)name="download";
        name=name.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
        name=WorkspaceMediaPaths.safeName(name);
        String type=contentType==null?"":contentType.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
        if(name.indexOf('.')<0) {
            if(type.equals("application/vnd.android.package-archive"))name+=".apk";
            else if(type.equals("application/zip"))name+=".zip";
            else if(type.equals("application/pdf"))name+=".pdf";
            else if(type.equals("text/plain"))name+=".txt";
        }
        return WorkspaceMediaPaths.safeName(name);
    }
    static final class Header {int status;final Map<String,String> values=new LinkedHashMap<>();}
    static Header header(InputStream input)throws IOException{
        int[] budget={16384};String status=line(input,budget);
        if(!status.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?"))throw new IOException("Invalid media response.");
        Header header=new Header();header.status=Integer.parseInt(status.substring(9,12));
        for(int count=0;count<80;count++){
            String value=line(input,budget);if(value.isEmpty())return header;
            int colon=value.indexOf(':');if(colon<1||Character.isWhitespace(value.charAt(0)))throw new IOException("Invalid media headers.");
            String key=value.substring(0,colon).toLowerCase(Locale.ROOT),body=value.substring(colon+1).trim();
            if(!key.matches("[a-z0-9-]+"))throw new IOException("Invalid media headers.");
            if(header.values.containsKey(key) && (key.equals("content-length")||key.equals("transfer-encoding")||key.equals("location")))throw new IOException("Ambiguous media response.");
            header.values.put(key,body);
        }throw new IOException("Too many media headers.");
    }
    private static String line(InputStream input,int[] budget)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();boolean cr=false;
        for(;;){if(--budget[0]<0)throw new IOException("Media headers are too large.");int value=input.read();if(value<0)throw new IOException("Incomplete media response.");
            if(cr){if(value!='\n')throw new IOException("Invalid media line ending.");return new String(out.toByteArray(),StandardCharsets.US_ASCII);}
            if(value=='\r')cr=true;else {if(value<32&&value!='\t'||value>126)throw new IOException("Invalid media header character.");out.write(value);}}
    }
    static final class Capture extends OutputStream {
        final OutputStream output;final long maximum,deadline;final ByteArrayOutputStream prefix=new ByteArrayOutputStream();long total;
        Capture(OutputStream output,long maximum,long deadline){this.output=output;this.maximum=maximum;this.deadline=deadline;}
        @Override public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
        @Override public void write(byte[] bytes,int offset,int count)throws IOException{check(deadline);if(count>maximum-total)throw new IOException("Media exceeds the download limit.");int capture=Math.min(count,512-prefix.size());if(capture>0)prefix.write(bytes,offset,capture);output.write(bytes,offset,count);total+=count;}
    }
    static void exact(InputStream input,Capture output,long length)throws IOException{byte[] bytes=new byte[32768];while(length>0){check(output.deadline);int read=input.read(bytes,0,(int)Math.min(bytes.length,length));if(read<0)throw new IOException("The media download was interrupted.");if(read==0)continue;output.write(bytes,0,read);length-=read;}}
    static void copy(InputStream input,Capture output)throws IOException{byte[] bytes=new byte[32768];for(;;){check(output.deadline);int read=input.read(bytes);if(read<0)return;if(read>0)output.write(bytes,0,read);}}
    static void chunked(InputStream input,Capture output)throws IOException{
        for(int chunks=0;chunks<100000;chunks++){
            String size=line(input,new int[]{128}).split(";",2)[0].trim();long count;
            try{if(!size.matches("[0-9a-fA-F]{1,12}"))throw new NumberFormatException();count=Long.parseLong(size,16);}catch(NumberFormatException error){throw new IOException("Invalid media chunk.");}
            if(count==0)return;
            if(count>output.maximum-output.total)throw new IOException("Media exceeds the download limit.");
            exact(input,output,count);if(!line(input,new int[]{2}).isEmpty())throw new IOException("Invalid media chunk ending.");
        }throw new IOException("Too many media chunks.");
    }
    private static int timeout(long deadline,int cap)throws IOException{check(deadline);return (int)Math.max(1,Math.min(cap,(deadline-System.nanoTime())/1_000_000L));}
    private static void check(long deadline)throws IOException{if(Thread.currentThread().isInterrupted())throw new IOException("Media download cancelled.");if(System.nanoTime()>deadline)throw new IOException("Media download timed out. Tap to retry.");}
    private RemoteMediaFetch(){}
}
