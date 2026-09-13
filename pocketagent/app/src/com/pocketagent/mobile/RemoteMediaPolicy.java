package com.pocketagent.mobile;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Portable rules: links are untrusted, and discovery never makes a network request. */
final class RemoteMediaPolicy {
    static final int MAX_LINKS = 4;
    private static final Pattern MARKDOWN = Pattern.compile("(!?)\\[([^]\\r\\n]{0,160})\\]\\((<[^>\\r\\n]{1,2048}>|[^\\s\\r\\n)]{1,2048})(?:\\s+\"[^\"\\r\\n]{0,200}\")?\\)");
    private static final Pattern RAW = Pattern.compile("https://[^\\s<>\\\"`]{1,2048}");
    static final class Link {
        final String url, name, host;
        final boolean image;
        Link(String value, String label) throws IOException { this(value, label, false); }
        Link(String value, String label, boolean declaredImage) throws IOException {
            URI checked = uri(value); url = checked.toASCIIString(); host = checked.getHost();
            image = declaredImage || directImage(value);
            String path = checked.getPath();
            String filename = path == null ? "File" : path.substring(path.lastIndexOf('/') + 1);
            name = WorkspaceMediaPaths.safeName(label == null || label.trim().isEmpty() ? filename : label);
        }
    }
    static URI uri(String value) throws IOException {
        try {
            if (value == null || value.length() > 2048 || value.indexOf('\\') >= 0) throw new IOException("Invalid media link.");
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawFragment() != null
                    || uri.getRawPath() != null && (uri.getRawPath().contains("%0d") || uri.getRawPath().contains("%0a")))
                throw new IOException("Only public HTTPS media links are supported.");
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".") || host.equals("localhost") || !host.contains(".") || host.endsWith(".local")
                    || host.endsWith(".localhost") || host.endsWith(".internal") || host.endsWith(".home")
                    || host.indexOf(':') >= 0 || host.startsWith("[")) throw new IOException("Local network media links are blocked.");
            // Numeric hosts are unnecessary here and include non-canonical loopback spellings.
            if (host.matches("[0-9.]+") || host.matches("(?i)0x[0-9a-f]+")) throw new IOException("Numeric media hosts are blocked.");
            return uri;
        } catch (java.net.URISyntaxException | IllegalArgumentException error) { throw new IOException("Invalid media link.", error); }
    }
    static boolean publicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a=b[0]&255,c=b[1]&255;
            return a != 0 && a != 10 && a != 127 && a < 224
                    && !(a==100 && c>=64 && c<=127) && !(a==169 && c==254)
                    && !(a==172 && c>=16 && c<=31) && !(a==192 && (c==168 || c==0 || c==2))
                    && !(a==198 && (c==18 || c==19 || c==51)) && !(a==203 && c==0);
        }
        // Global unicast only. Exclude transition/documentation ranges as well as ULA.
        if (b.length != 16 || (b[0]&0xe0) != 0x20) return false;
        if ((b[0]&255)==0x20 && (b[1]&255)==0x02) return false; // 6to4 may embed private IPv4
        return !((b[0]&255)==0x20 && (b[1]&255)==0x01 && (((b[2]&255)==0 && (b[3]&255)==0)
                || ((b[2]&255)==0x0d && (b[3]&255)==0xb8)));
    }
    static boolean directImage(String value) {
        try { return uri(value).getPath().toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp|svg)$"); }
        catch (IOException | RuntimeException ignored) { return false; }
    }
    static boolean directFile(String value) {
        try {
            String path=uri(value).getPath().toLowerCase(Locale.ROOT);
            return path.matches(".*\\.(png|jpe?g|gif|webp|svg|mp4|webm|mov|m4v|mp3|m4a|wav|ogg|flac|pdf|zip|tar|gz|tgz|7z|txt|md|json|csv|apk|aab|docx|xlsx|pptx|java|kt|kts|py|js|jsx|ts|tsx|c|h|cpp|hpp|cs|go|rs|rb|sh|sql|xml|yaml|yml|toml|css)$");
        } catch (IOException | RuntimeException ignored) { return false; }
    }
    static List<Link> links(String markdown) {
        LinkedHashMap<String,Link> found = new LinkedHashMap<>();
        if (markdown == null) return new ArrayList<>();
        StringBuilder prose=new StringBuilder(); boolean fenced=false;
        for(String line:markdown.substring(0,Math.min(128*1024,markdown.length())).split("\n")) {
            if(line.trim().startsWith("```")){fenced=!fenced;continue;}
            if(!fenced)prose.append(line.replaceAll("`[^`]*`", " ")).append('\n');
        }
        Matcher match=MARKDOWN.matcher(prose);
        while(match.find() && found.size()<MAX_LINKS) {
            String value=match.group(3); if(value.startsWith("<"))value=value.substring(1,value.length()-1);
            if("!".equals(match.group(1)) || directFile(value))add(found,value,match.group(2),"!".equals(match.group(1)));
        }
        match=RAW.matcher(prose);
        while(match.find() && found.size()<MAX_LINKS) {
            String value=match.group().replaceFirst("[),.;]+$", "");
            if(directFile(value))add(found,value,null,false);
        }
        return new ArrayList<>(found.values());
    }
    private static void add(LinkedHashMap<String,Link> found,String url,String label,boolean declaredImage) {
        try {Link link=new Link(url,label,declaredImage);Link previous=found.get(link.url);if(previous==null||!previous.image&&link.image)found.put(link.url,link);}catch(IOException ignored){}
    }
    private RemoteMediaPolicy(){}
}
