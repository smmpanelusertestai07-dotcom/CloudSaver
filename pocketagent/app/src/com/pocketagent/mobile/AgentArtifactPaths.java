package com.pocketagent.mobile;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Locale;

/** Maps an explicit agent file link to this app's Ubuntu output folders, never Android storage. */
final class AgentArtifactPaths {
    private static final String[] OUTPUT_ROOTS = {"/tmp", "/var/tmp", "/mnt/data", "/workspace", "/home/coder/Downloads"};
    static final class Target {
        final File base, file;
        final String relative, identity;
        final boolean projectFile;
        Target(File base, File file, String relative, String identity, boolean projectFile) {
            this.base=base;this.file=file;this.relative=relative;this.identity=identity;this.projectFile=projectFile;
        }
        File regularFile() throws IOException { return WorkspaceMediaPaths.file(base, relative); }
    }

    /** Missing targets can identify an already-saved snapshot; the first copy still requires a real file. */
    static Target target(File rootfs, File project, String guestProject, File shared, String destination) throws IOException {
        if(rootfs==null||project==null||guestProject==null||!guestProject.startsWith("/home/coder/Projects/"))
            throw new IOException("Choose a project first.");
        if(Files.isSymbolicLink(rootfs.toPath())||Files.isSymbolicLink(project.toPath())||!rootfs.isDirectory()||!project.isDirectory())
            throw new IOException("This workspace is unavailable.");
        String path=localPath(destination),projectPath=project.getCanonicalPath(),rootPath=rootfs.getCanonicalPath();
        // Host paths are accepted only when they refer to this private rootfs or the selected project.
        if(path.startsWith(projectPath+"/"))path=guestProject+path.substring(projectPath.length());
        else if(path.startsWith(rootPath+"/"))path=path.substring(rootPath.length());
        if(!path.startsWith("/"))path=guestProject+"/"+path;
        checkSegments(path);
        File base;String relative;boolean insideProject=false;
        if(path.startsWith(guestProject+"/")) {
            base=project.getCanonicalFile();relative=path.substring(guestProject.length()+1);insideProject=true;
        } else if(path.startsWith("/home/coder/Shared/")) {
            if(shared==null||Files.isSymbolicLink(shared.toPath()))throw new IOException("This output folder is unavailable.");
            base=shared.getCanonicalFile();relative=path.substring("/home/coder/Shared/".length());
        } else {
            String matched=null;
            for(String output:OUTPUT_ROOTS)if(path.startsWith(output+"/")){matched=output;break;}
            if(matched==null)throw new IOException("This link is not a workspace output file. Ask the agent to save it in the project or /mnt/data.");
            base=WorkspaceTools.safeChild(rootfs,matched.substring(1));relative=path.substring(matched.length()+1);
        }
        File file=WorkspaceTools.safeChild(base,relative);
        if(file.equals(base))throw new IOException("Choose a file, not a folder.");
        String identity="local-artifact\n"+projectPath+"\n"+path;
        return new Target(base,file,relative,identity,insideProject);
    }

    static String localPath(String destination) throws IOException {
        if(destination==null||destination.length()>4096)throw new IOException("Invalid file link.");
        String value=destination.trim();
        if(value.startsWith("<")&&value.endsWith(">"))value=value.substring(1,value.length()-1);
        try {
            URI uri=new URI(value.replace(" ","%20"));
            String scheme=uri.getScheme();
            if(uri.isOpaque()||uri.getRawAuthority()!=null||uri.getRawQuery()!=null
                    ||scheme!=null&&!scheme.equalsIgnoreCase("file")&&!scheme.equalsIgnoreCase("sandbox"))
                throw new IOException("This is not a local file link.");
            String path=uri.getPath();
            if(path==null||path.isEmpty())throw new IOException("Invalid file link.");
            checkSegments(path);
            StringBuilder normalized=new StringBuilder(path.startsWith("/")?"/":"");
            for(String part:path.split("/")) {
                if(part.isEmpty()||part.equals("."))continue;
                if(normalized.length()>0&&normalized.charAt(normalized.length()-1)!='/')normalized.append('/');
                normalized.append(part);
            }
            if(normalized.length()==0)throw new IOException("Choose a file, not a folder.");
            return normalized.toString();
        }catch(java.net.URISyntaxException invalid){throw new IOException("Invalid file link.",invalid);}
    }

    private static void checkSegments(String path) throws IOException {
        if(path.indexOf('\\')>=0||path.matches(".*%[0-9a-fA-F]{2}.*"))throw new IOException("Invalid file link.");
        for(int i=0;i<path.length();i++)if(Character.isISOControl(path.charAt(i)))throw new IOException("Invalid file link.");
        for(String segment:path.split("/",-1)) {
            if(segment.equals(".."))throw new IOException("File links cannot contain traversal.");
            String name=segment.toLowerCase(Locale.ROOT);
            if(name.equals(".codex")||name.equals(".claude")||name.equals(".cursor")||name.equals(".gemini")
                    ||name.equals(".antigravity")||name.equals(".ssh")||name.equals(".aws")||name.equals(".kube")
                    ||name.equals(".config")||name.equals(".signing")||name.equals(".git")
                    ||name.equals(".env")||name.startsWith(".env.")||name.equals(".npmrc")||name.equals(".netrc")
                    ||name.equals(".git-credentials")||name.equals("auth.json")||name.equals("credentials.json")
                    ||name.equals("credentials")||name.equals("token.json")||name.equals("tokens.json")
                    ||name.equals("id_rsa")||name.equals("id_ed25519")||name.equals("id_ecdsa")||name.equals("id_dsa")
                    ||name.endsWith(".pem")||name.endsWith(".key")||name.endsWith(".jks")||name.endsWith(".p12")
                    ||name.endsWith(".pfx")||name.endsWith(".keystore")||name.startsWith("service-account")&&name.endsWith(".json"))
                throw new IOException("Account and credential files cannot be opened as chat downloads.");
        }
    }
    private AgentArtifactPaths() { }
}
