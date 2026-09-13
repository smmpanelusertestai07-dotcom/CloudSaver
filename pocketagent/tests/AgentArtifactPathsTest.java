package com.pocketagent.mobile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Real private rootfs fixtures: existing outputs work, absent paths are never guessed. */
public final class AgentArtifactPathsTest {
    private static int assertions;
    interface Checked {void run() throws Exception;}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("pocketagent-artifacts-");
        try {
            File rootfs=Files.createDirectory(root.resolve("ubuntu-rootfs")).toFile();
            File project=Files.createDirectories(rootfs.toPath().resolve("home/coder/Projects/demo")).toFile();
            File shared=Files.createDirectory(root.resolve("shared")).toFile();
            String guest="/home/coder/Projects/demo";
            byte[] payload={80,75,3,4,1,2,3};
            Path apk=project.toPath().resolve("calculator.apk");Files.write(apk,payload);
            eq(target(rootfs,project,guest,shared,"calculator.apk").regularFile(),apk.toFile(),"relative project APK");
            eq(target(rootfs,project,guest,shared,"./calculator.apk").regularFile(),apk.toFile(),"harmless leading relative dot resolves");
            eq(target(rootfs,project,guest,shared,"././calculator.apk").identity,target(rootfs,project,guest,shared,"calculator.apk").identity,"relative dot source identity canonicalized");
            eq(target(rootfs,project,guest,shared,"sandbox:"+guest+"/calculator.apk").regularFile(),apk.toFile(),"guest sandbox APK");
            eq(target(rootfs,project,guest,shared,"file://"+apk).regularFile(),apk.toFile(),"private host project APK");
            for(String folder:new String[]{"tmp","var/tmp","mnt/data","workspace/scratch/run1","home/coder/Downloads"}) {
                Path file=Files.createDirectories(rootfs.toPath().resolve(folder)).resolve("calculator.apk");Files.write(file,payload);
                AgentArtifactPaths.Target checked=target(rootfs,project,guest,shared,"sandbox:/"+folder+"/calculator.apk");
                eq(checked.regularFile(),file.toFile(),"actual external artifact "+folder);
                check(Arrays.equals(Files.readAllBytes(checked.regularFile().toPath()),payload),"APK bytes unchanged "+folder);
                check(!checked.projectFile,"external output not misrepresented as project file");
                eq(target(rootfs,project,guest,shared,file.toString()).regularFile(),file.toFile(),"host rootfs path mapped "+folder);
            }
            Path spaced=rootfs.toPath().resolve("mnt/data/my app.zip");Files.write(spaced,payload);
            eq(target(rootfs,project,guest,shared,"<sandbox:/mnt/data/my%20app.zip>").regularFile(),spaced.toFile(),"encoded spaces preserved");
            eq(target(rootfs,project,guest,shared,"sandbox:/mnt/data/my%20app.zip#L1").regularFile(),spaced.toFile(),"line anchor is not filename");
            Files.write(shared.toPath().resolve("output.bin"),payload);
            eq(target(rootfs,project,guest,shared,"/home/coder/Shared/output.bin").regularFile(),shared.toPath().resolve("output.bin").toFile(),"actual bound Shared directory mapped");
            AgentArtifactPaths.Target missing=target(rootfs,project,guest,shared,"/mnt/data/missing.apk");
            reject(()->missing.regularFile(),"missing basename is never substituted with an unrelated project artifact");
            check(!missing.file.exists(),"resolver never invents or creates output");
            String identity=target(rootfs,project,guest,shared,"/tmp/calculator.apk").identity;
            File other=Files.createDirectory(project.toPath().resolveSibling("other")).toFile();
            check(!identity.equals(target(rootfs,other,"/home/coder/Projects/other",shared,"/tmp/calculator.apk").identity),"saved source reference scoped to selected project");
            for(String bad:new String[]{"/root/.codex/auth.json","/home/coder/.codex/auth.json","/home/coder/Projects/other/secret.txt",
                    "/etc/passwd","/data/user/0/com.pocketagent.mobile/files/auth.json","/storage/emulated/0/private.txt","/proc/self/environ",
                    "/tmp/../etc/passwd","/tmp/%2e%2e/etc/passwd","/tmp/%252e%252e/etc/passwd","/tmp/a%00.apk","/tmp/a%5cb.apk",
                    "/tmp/.codex/auth.json","/mnt/data/credentials.json","/workspace/.ssh/id_rsa","/tmp/.env","/tmp/app.keystore",
                    "/tmp/a.p12","/tmp/a.pem","/tmp/subdir/.aws/settings","/tmp/service-account-test.json",
                    "https://example.com/app.apk","file://evil/tmp/app.apk","//evil/tmp/app.apk","content://phone/secret","sandbox:/tmp/app.apk?x=1"})
                reject(()->target(rootfs,project,guest,shared,bad),"unsafe source "+bad);
            Path outside=root.resolve("private.apk");Files.write(outside,payload);
            Files.createSymbolicLink(rootfs.toPath().resolve("tmp/link.apk"),outside);
            reject(()->target(rootfs,project,guest,shared,"/tmp/link.apk"),"file symlink blocked");
            Files.createSymbolicLink(rootfs.toPath().resolve("tmp/link-dir"),root);
            reject(()->target(rootfs,project,guest,shared,"/tmp/link-dir/private.apk"),"parent symlink blocked");
            Path rootAlias=root.resolve("alias");Files.createSymbolicLink(rootAlias,rootfs.toPath());
            reject(()->target(rootAlias.toFile(),project,guest,shared,"/tmp/calculator.apk"),"rootfs symlink blocked");
            reject(()->target(rootfs,project,guest,shared,"/mnt/data").regularFile(),"folder is not downloadable file");
            eq(WorkspaceMediaPaths.mime(payload,payload.length,"app.apk"),"application/vnd.android.package-archive","APK MIME retained");
            eq(WorkspaceMediaPaths.mime(payload,payload.length,"source.zip"),"application/zip","archive MIME retained");
            eq(WorkspaceMediaPaths.mime(new byte[]{1},1,"Main.java"),"text/plain","source file MIME retained");
            check(WorkspaceMediaPaths.MAX_FILE_BYTES==512L*1024*1024,"general artifacts have independent 512 MiB limit");
            check(WorkspaceMediaPaths.MAX_IMAGE_BYTES==20L*1024*1024,"image decode cap remains separate");
            System.out.println("PASS AgentArtifactPaths: "+assertions+" assertions (real Ubuntu outputs, bound Shared, project scope, missing files, traversal, symlinks, credentials, MIME)");
        }finally{try(java.util.stream.Stream<Path> paths=Files.walk(root)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}}
    }
    private static AgentArtifactPaths.Target target(File root,File project,String guest,File shared,String link)throws IOException{return AgentArtifactPaths.target(root,project,guest,shared,link);}
    private static void reject(Checked run,String label)throws Exception{try{run.run();}catch(IOException expected){assertions++;return;}throw new AssertionError(label+" accepted");}
    private static void eq(Object actual,Object expected,String label){check(expected.equals(actual),label+": "+actual);}
    private static void check(boolean condition,String label){assertions++;if(!condition)throw new AssertionError(label);}
}
