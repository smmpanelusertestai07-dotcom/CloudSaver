package com.pocketagent.mobile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Real filesystem/stream fixtures: no Android implementation is mocked. */
public final class WorkspaceMediaTest {
    private static int assertions;
    interface Checked { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("pocketagent-media-");
        try {
            File project = Files.createDirectory(root.resolve("demo")).toFile();
            Files.createDirectory(project.toPath().resolve("art"));
            Files.write(project.toPath().resolve("art/a.png"),new byte[]{1});
            Files.write(project.toPath().resolve("hello world.txt"),"hello".getBytes(StandardCharsets.UTF_8));
            File outside = root.resolve("private.txt").toFile(); Files.write(outside.toPath(),new byte[]{42});
            String guest = "/home/coder/Projects/demo";
            eq(WorkspaceMediaPaths.file(project,"art/a.png"),project.toPath().resolve("art/a.png").toFile().getCanonicalFile(),"regular local file");
            reject(() -> WorkspaceMediaPaths.file(project,"../private.txt"),"parent traversal");
            reject(() -> WorkspaceMediaPaths.file(project,outside.getPath()),"absolute file");
            reject(() -> WorkspaceMediaPaths.file(project,"art\\a.png"),"backslash separator");
            reject(() -> WorkspaceMediaPaths.file(project,"art"),"directory is not file");
            reject(() -> WorkspaceMediaPaths.file(project,"missing"),"missing file");
            Files.createSymbolicLink(project.toPath().resolve("linked"),outside.toPath());
            reject(() -> WorkspaceMediaPaths.file(project,"linked"),"file symlink");
            Files.createSymbolicLink(project.toPath().resolve("linked-dir"),root);
            reject(() -> WorkspaceMediaPaths.file(project,"linked-dir/private.txt"),"parent symlink");
            Path linkedProject = root.resolve("linked-project"); Files.createSymbolicLink(linkedProject,project.toPath());
            reject(() -> WorkspaceMediaPaths.file(linkedProject.toFile(),"art/a.png"),"project symlink");
            eq(WorkspaceMediaPaths.relativeLink(project,guest,"art/a.png"),"art/a.png","relative markdown link");
            eq(WorkspaceMediaPaths.relativeLink(project,guest,guest+"/art/a.png"),"art/a.png","absolute guest link");
            eq(WorkspaceMediaPaths.relativeLink(project,guest,"sandbox:"+guest+"/art/a.png"),"art/a.png","sandbox guest link");
            eq(WorkspaceMediaPaths.relativeLink(project,guest,"file://"+guest+"/art/a.png"),"art/a.png","file guest link");
            eq(WorkspaceMediaPaths.relativeLink(project,guest,project.getPath()+"/art/a.png"),"art/a.png","private project link");
            eq(WorkspaceMediaPaths.relativeLink(project,guest,"<hello%20world.txt>"),"hello world.txt","encoded filename");
            eq(WorkspaceMediaPaths.relativeLink(project,guest,"hello%20world.txt#L12"),"hello world.txt","line anchor");
            for(String bad : new String[]{"https://example.com/a.png","http://localhost/a.png","content://phone/image",
                    "data:image/png,a","javascript:alert(1)","file://evil/art/a.png","//evil/art/a.png",
                    "sandbox:/mnt/data/other.png",guest+"2/art/a.png",guest+"/../other/a.png","%2e%2e/private.txt",
                    "linked", "linked-dir/private.txt","art/a.png?fetch=https://example.com","art%5Ca.png","art/a.png%00"}) {
                reject(() -> WorkspaceMediaPaths.relativeLink(project,guest,bad),"unsafe link "+bad);
            }
            String markdown="![pic](art/a.png) [download](<hello world.txt>) [same]("+guest+"/art/a.png) "
                    +"[remote](https://example.com/a.png) [private](../private.txt)";
            eq(WorkspaceMediaPaths.artifactPaths(project,guest,markdown),Arrays.asList("art/a.png","hello world.txt"),"deduplicated safe artifacts");
            StringBuilder many = new StringBuilder();
            for(int i=0;i<20;i++) {String name="a"+i+".txt"; Files.write(project.toPath().resolve(name),new byte[]{1}); many.append("[file](").append(name).append(") ");}
            eq(WorkspaceMediaPaths.artifactPaths(project,guest,many.toString()).size(),12,"artifact cap");
            eq(WorkspaceMediaPaths.safeName("../../bad\nname.jpg"),"_.._bad_name.jpg","safe import filename");
            eq(WorkspaceMediaPaths.safeName("..."),"attachment","empty sanitized filename");
            check(WorkspaceMediaPaths.safeName(repeat("z",200)+".jpg").length()==100,"name bounded");
            check(WorkspaceMediaPaths.safeName(repeat("z",200)+".jpg").endsWith(".jpg"),"extension retained");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            eq(WorkspaceMediaPaths.copyBounded(new ByteArrayInputStream(new byte[]{1,2,3}),output,3),3L,"exact limit accepts");
            check(Arrays.equals(output.toByteArray(),new byte[]{1,2,3}),"bytes unchanged");
            output.reset();
            reject(() -> WorkspaceMediaPaths.copyBounded(new ByteArrayInputStream(new byte[]{1,2,3,4}),output,3),"unknown-size stream bounded");
            check(output.size()<=3,"excess bytes never written");
            eq(WorkspaceMediaPaths.copyBounded(new ByteArrayInputStream(new byte[0]),output,0),0L,"empty file allowed");
            reject(() -> WorkspaceMediaPaths.copyBounded(new ByteArrayInputStream(new byte[]{1}),output,0),"zero bound enforced");
            Thread.currentThread().interrupt();
            reject(() -> WorkspaceMediaPaths.copyBounded(new ByteArrayInputStream(new byte[]{1}),output,3),"cancelled import stops");
            Thread.interrupted();
            String token=repeat("a",64), authority="com.pocketagent.mobile.projectfiles";
            eq(WorkspaceMediaPaths.shareToken(authority,"content://"+authority+"/file/"+token+"/hello%20world.png"),token,"one opaque snapshot URI");
            for(String bad : new String[]{"file://"+authority+"/file/"+token+"/a.png","content://evil/file/"+token+"/a.png",
                    "content://"+authority+"/file/../private.txt","content://"+authority+"/file/"+token+"/a.png?write=1",
                    "content://"+authority+"/file/"+token+"/a.png#part","content://"+authority+"/file/"+token+"/../a.png",
                    "content://"+authority+"/file/"+token+"/%2e%2e","content://"+authority+"/file/"+token+"/a%2fb.png",
                    "content://"+authority+"/file/short/a.png","content://"+authority+"/file/"+token+"/a.png/"}) {
                reject(() -> WorkspaceMediaPaths.shareToken(authority,bad),"invalid export URI");
            }
            byte[] png={(byte)137,80,78,71,13,10,26,10};
            eq(WorkspaceMediaPaths.mime(png,png.length,"fake.exe"),"image/png","content signature determines image");
            byte[] html="<html>remote</html>".getBytes(StandardCharsets.UTF_8);
            eq(WorkspaceMediaPaths.mime(html,html.length,"fake.png"),"application/octet-stream","image suffix cannot spoof signature");
            byte[] hls="#EXTM3U\nhttps://evil/stream".getBytes(StandardCharsets.UTF_8);
            eq(WorkspaceMediaPaths.mime(hls,hls.length,"fake.mp4"),"application/octet-stream","playlist never gets media player");
            byte[] svg="<svg><image href='https://evil'/></svg>".getBytes(StandardCharsets.UTF_8);
            eq(WorkspaceMediaPaths.mime(svg,svg.length,"a.svg"),"application/octet-stream","active SVG never gets image renderer");
            byte[] largeImage = new byte[3 * 1024 * 1024 + 2];
            new java.util.Random(7).nextBytes(largeImage);
            String encodedImage = java.util.Base64.getEncoder().encodeToString(largeImage);
            ByteArrayOutputStream imageOutput = new ByteArrayOutputStream();
            eq(WorkspaceMediaPaths.decodeInlineImage(encodedImage,imageOutput),(long)largeImage.length,"multi-MiB inline image accepted");
            check(Arrays.equals(largeImage,imageOutput.toByteArray()),"streamed image bytes exact");
            imageOutput.reset();
            for (String invalid : new String[]{"", "QQ==trailing", "QQ==QQ==", "Q$==", "QQé=", "Q", "Q Q="})
                reject(() -> WorkspaceMediaPaths.decodeInlineImage(invalid,imageOutput),"malformed inline base64");
            reject(() -> WorkspaceMediaPaths.decodeInlineImage(repeat("A",WorkspaceMediaPaths.MAX_INLINE_ENCODED_CHARS+1),imageOutput),"encoded transport boundary enforced");
            Thread.currentThread().interrupt();
            reject(() -> WorkspaceMediaPaths.decodeInlineImage("QQ==",imageOutput),"cancelled image decode stops");
            Thread.interrupted();
            File privateFiles = Files.createDirectory(root.resolve("app-files")).toFile();
            File privateCache = Files.createDirectory(root.resolve("app-cache")).toFile();
            File media = WorkspaceMediaStore.directory(privateFiles);
            File savedBody = WorkspaceMediaStore.file(media,token,".bin"), savedInfo = WorkspaceMediaStore.file(media,token,".json");
            Files.write(savedBody.toPath(),largeImage); Files.write(savedInfo.toPath(),"{}".getBytes(StandardCharsets.UTF_8));
            long old = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000;
            savedBody.setLastModified(old); savedInfo.setLastModified(old);
            Files.write(privateCache.toPath().resolve("temporary"),new byte[]{1});
            Files.delete(privateCache.toPath().resolve("temporary")); Files.delete(privateCache.toPath());
            File reopened = WorkspaceMediaStore.directory(privateFiles);
            eq(WorkspaceMediaStore.saved(reopened,token,false),savedBody,"saved media survives reopen,30days andcacheclear");
            check(Arrays.equals(Files.readAllBytes(savedBody.toPath()),largeImage),"persistent media remains exact");
            check(WorkspaceMediaStore.available(media,0)>0,"storage remains available");
            eq(WorkspaceMediaStore.available(media,WorkspaceMediaStore.MAX_TOTAL_BYTES),0L,"reserved storage cannot exceed budget");
            String missingToken=repeat("b",64);
            Files.write(WorkspaceMediaStore.file(media,missingToken,".bin").toPath(),new byte[]{1});
            reject(() -> WorkspaceMediaStore.saved(media,missingToken,false),"partial media without metadata not published");
            String linkedToken=repeat("c",64);
            Files.createSymbolicLink(WorkspaceMediaStore.file(media,linkedToken,".bin").toPath(),outside.toPath());
            Files.write(WorkspaceMediaStore.file(media,linkedToken,".json").toPath(),new byte[]{1});
            reject(() -> WorkspaceMediaStore.saved(media,linkedToken,false),"saved media cannot escape private storage");
            reject(() -> WorkspaceMediaStore.file(media,"../outside",".bin"),"persistent token traversal blocked");
            reject(() -> WorkspaceMediaStore.file(media,token,"/../auth.json"),"persistent suffix traversal blocked");
            String source="https://example.com/image.png?secret=private-value";
            String fingerprint=WorkspaceMediaStore.sourceKey(source);
            check(fingerprint.matches("[a-f0-9]{64}")&&!fingerprint.contains("private-value"),"source metadata contains only hash");
            check(!fingerprint.equals(WorkspaceMediaStore.sourceKey(source+"2")),"signed source variants remain separate");
            System.out.println("PASS WorkspaceMedia: "+assertions+" assertions (containment, imports, streamed multi-MiB images, durable private storage, export URI, MIME guards)");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(root)) {paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}
        }
    }
    private static String repeat(String value,int count) {StringBuilder result=new StringBuilder(); for(int i=0;i<count;i++) result.append(value); return result.toString();}
    private static void reject(Checked action,String label) throws Exception {try{action.run();}catch(IOException expected){assertions++;return;}throw new AssertionError(label+" was accepted");}
    private static void eq(Object actual,Object expected,String label) {check(expected.equals(actual),label+": expected "+expected+", got "+actual);}
    private static void check(boolean condition,String label) {assertions++;if(!condition)throw new AssertionError(label);}
}
