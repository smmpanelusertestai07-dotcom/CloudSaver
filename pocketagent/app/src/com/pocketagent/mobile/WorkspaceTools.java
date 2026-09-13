package com.pocketagent.mobile;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Phone-sized project operations. Blocking methods must run on a worker thread. */
final class WorkspaceTools {
    static final int MAX_TEXT_BYTES = 512 * 1024;
    static final int MAX_DIRECTORY_ENTRIES = 500;
    private static final int MAX_LOG_CHARS = 128 * 1024;
    private static final String SELECTED = "native_selected_project";
    private static final AtomicBoolean PROJECT_OPERATION = new AtomicBoolean();
    private static final String GIT = ProjectGit.COMMAND;
    private static volatile Process previewProcess;
    private static volatile int previewPort;
    private static volatile String previewProject;
    private static volatile String previewToken = "";

    static final class Entry {
        final String name;
        final String path;
        final boolean directory;
        final long size;

        Entry(String name, String path, boolean directory, long size) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.size = size;
        }
    }

    private WorkspaceTools() {}

    static List<File> projects(Context context) throws IOException {
        File root = workspace(context);
        File[] files = root.listFiles();
        if (files == null) throw new IOException("Cannot read the projects folder.");
        List<File> result = new ArrayList<>();
        for (File file : files) {
            if (!file.getName().startsWith(".") && file.isDirectory()
                    && !Files.isSymbolicLink(file.toPath()) && isInside(root, file)) {
                result.add(file);
            }
        }
        Collections.sort(result, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    static File selectedProject(Context context) throws IOException {
        String name = context.getSharedPreferences(ContainerRuntime.PREFS, Context.MODE_PRIVATE)
                .getString(SELECTED, "");
        if (name != null && !name.isEmpty()) {
            try {
                File file = safeChild(workspace(context), name);
                if (file.isDirectory()) return file;
            } catch (IOException ignored) { }
        }
        List<File> available = projects(context);
        return available.isEmpty() ? null : available.get(0);
    }

    static void selectProject(Context context, File project) throws IOException {
        File checked = checkProject(context, project);
        context.getSharedPreferences(ContainerRuntime.PREFS, Context.MODE_PRIVATE)
                .edit().putString(SELECTED, checked.getName()).apply();
    }

    static File createProject(Context context, String name) throws IOException {
        validateName(name);
        File project = safeChild(workspace(context), name);
        if (project.exists()) throw new IOException("A project with this name already exists.");
        if (!project.mkdir()) throw new IOException("Cannot create the project folder.");
        saveText(context, project, "README.md", "# " + name
                + "\n\nCreated with PocketAgent. Ask an agent to build your idea, then review its changes.\n");
        saveText(context, project, "index.html", "<!doctype html>\n<html lang=\"en\"><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + name + "</title><style>body{margin:0;padding:12vh 8vw;background:#101322;"
                + "color:#edf1ff;font:18px system-ui}small{color:#a99dff}h1{font-size:42px;letter-spacing:-2px}"
                + "p{max-width:32em;line-height:1.6;color:#b7bfd9}</style>"
                + "<small>POCKETAGENT / YOUR WORKSPACE</small><h1>" + name + "</h1>"
                + "<p>Your preview is running on this phone. Tell your agent what you want to create.</p></html>\n");
        selectProject(context, project);
        return project;
    }

    static File cloneProject(Context context, String url, String name,
                             ContainerRuntime.OutputListener listener) throws IOException {
        beginProjectOperation(context);
        try {
            requireRuntime(context); validateName(name);
            String source = validateGitUrl(url);
            File target = safeChild(workspace(context), name);
            if (target.exists()) throw new IOException("A project with this name already exists.");
            run(context, "GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=/bin/false " + ProjectGit.commandForRemote(source, GitHubAuthService.snapshot().optBoolean("connected")) + " clone --depth 1 -- "
                    + quote(source) + " " + quote("/home/coder/Projects/" + name), 15, listener);
            if (!target.isDirectory()) throw new IOException("Git finished without creating the project folder.");
            selectProject(context, target); return target;
        } finally { PROJECT_OPERATION.set(false); }
    }

    static boolean isProjectOperationBusy() { return PROJECT_OPERATION.get(); }

    static void beginProjectOperation(Context context) throws IOException {
        JSONObject agent = AgentService.snapshot();
        if (LinuxService.isBusy() || agent.optBoolean("busy") || agent.optBoolean("installing")
                || agent.optBoolean("connecting") || agent.optBoolean("authenticating") || agent.optBoolean("sessionOpening")
                || CodexIntegrations.mutates(AgentProtocol.child(agent, "integrations").optString("operation")))
            throw new IOException("Finish or stop the current agent task and setup before changing project files.");
        if (!PROJECT_OPERATION.compareAndSet(false, true)) throw new IOException("Wait for the current project operation to finish.");
    }
    static void endProjectOperation() { PROJECT_OPERATION.set(false); }

    static File importZip(Context context, InputStream source, String name,
                          ContainerRuntime.OutputListener listener) throws IOException {
        beginProjectOperation(context);
        File archive = null;
        try {
            requireRuntime(context); validateName(name);
            if (source == null) throw new IOException("Android could not open the selected ZIP.");
            archive = File.createTempFile("project-import-", ".zip", context.getCacheDir());
            try (FileOutputStream output = new FileOutputStream(archive)) {
                byte[] buffer = new byte[16384]; long size = 0; int read;
                while ((read = source.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled.");
                    size += read;
                    if (size > ProjectArchive.MAX_COMPRESSED) throw new IOException("Source ZIP limit: 64 MB compressed.");
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            File project = ProjectArchive.importZip(workspace(context), name, archive,
                    value -> { if (listener != null) listener.line(value); });
            selectProject(context, project); return project;
        } finally {
            if (archive != null) archive.delete();
            PROJECT_OPERATION.set(false);
        }
    }

    static void renameFile(Context context, File project, String relative, String newName) throws IOException {
        beginProjectOperation(context);
        try {
            validateLeaf(newName);
            File base = checkProject(context, project), source = safeChild(base, relative);
            if (source.equals(base) || !source.isFile() || containsGitPath(relative)) throw new IOException("Choose an ordinary project file.");
            File target = safeChild(base, base.toPath().relativize(new File(source.getParentFile(), newName).toPath()).toString());
            if (target.exists()) throw new IOException("A file with this name already exists.");
            Files.move(source.toPath(), target.toPath());
        } finally { PROJECT_OPERATION.set(false); }
    }

    static void deleteFile(Context context, File project, String relative) throws IOException {
        beginProjectOperation(context);
        try {
            File base = checkProject(context, project), source = safeChild(base, relative);
            if (source.equals(base) || !source.isFile() || containsGitPath(relative)) throw new IOException("Only ordinary project files can be deleted here.");
            if (!source.delete()) throw new IOException("The file could not be deleted.");
        } finally { PROJECT_OPERATION.set(false); }
    }

    private static boolean containsGitPath(String relative) {
        for (String part : relative.replace('\\', '/').split("/")) if (part.equalsIgnoreCase(".git")) return true;
        return false;
    }
    private static void validateLeaf(String name) throws IOException {
        if (name == null || name.isEmpty() || name.length() > 180 || name.equals(".") || name.equals("..")
                || name.equalsIgnoreCase(".git") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0)
            throw new IOException("Enter a single file name, without folders.");
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) throw new IOException("Invalid file name.");
    }

    /** Account connector access is separate from credentials used by Git's HTTPS transport. */
    static JSONObject gitOverview(Context context, File project) throws IOException {
        File base = checkProject(context, project);
        if (!new File(base, ".git").isDirectory()) return AgentProtocol.object("repository", false);
        String branch = runGitInProject(context, project, GIT + " symbolic-ref --quiet --short HEAD || true", 2, null).trim();
        String remote = runGitInProject(context, project, GIT + " config --get remote.origin.url || true", 2, null).trim();
        String safeRemote = "", remoteNote = "";
        if (!remote.isEmpty()) {
            try { safeRemote = validateGitUrl(remote); }
            catch (IOException unsupported) { remoteNote = "Origin uses an unsupported or credential-bearing address. Replace it with a plain HTTPS URL to use native sync."; }
        }
        return AgentProtocol.object("repository", true, "branch", branch, "remote", safeRemote, "remoteNote", remoteNote,
                "status", gitStatus(context, project), "diff", gitDiff(context, project));
    }

    static void setGitRemote(Context context, File project, String url) throws IOException {
        beginProjectOperation(context);
        try {
            String checked = validateGitUrl(url);
            runGitInProject(context, project, GIT + " config --replace-all remote.origin.url " + quote(checked)
                    + " && " + GIT + " config --replace-all remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*'", 2, null);
        } finally { PROJECT_OPERATION.set(false); }
    }

    static String syncGit(Context context, File project, boolean push, String reviewedRemote, String reviewedBranch,
                          ContainerRuntime.OutputListener listener) throws IOException {
        beginProjectOperation(context);
        try {
            String remote = validateGitUrl(runGitInProject(context, project, GIT + " config --get remote.origin.url", 2, null).trim());
            String branch = runGitInProject(context, project, GIT + " symbolic-ref --quiet --short HEAD", 2, null).trim();
            validateBranch(branch);
            if (!push && !runGitInProject(context, project, GIT + " status --porcelain", 2, null).trim().isEmpty())
                throw new IOException("Commit or preserve your local changes before pulling. Native pull only fast-forwards a clean project.");
            String reviewedCommand = ProjectGit.reviewedSyncCommand(reviewedRemote, reviewedBranch, remote, branch, push, GitHubAuthService.snapshot().optBoolean("connected"));
            try { return runGitInProject(context, project, reviewedCommand, 10, listener); }
            catch (IOException error) { throw new IOException("Git " + (push ? "push" : "pull") + " did not complete. It needs existing Git HTTPS credentials and repository permission; ChatGPT's GitHub app does not provide them.\n" + error.getMessage(), error); }
        } finally { PROJECT_OPERATION.set(false); }
    }

    static String commitAll(Context context, File project, String message, String author, String email) throws IOException {
        beginProjectOperation(context);
        try {
            if (message == null || message.trim().isEmpty() || message.length() > 2000) throw new IOException("Enter a commit message of 1–2,000 characters.");
            if (author == null || author.trim().isEmpty() || author.length() > 120 || author.contains("\n")
                    || email == null || !email.matches("[^\\s<>@]{1,100}@[^\\s<>@]{1,150}")) throw new IOException("Enter your commit author name and email.");
            return runGitInProject(context, project, GIT + " add --all -- . && " + GIT + " -c commit.gpgsign=false"
                    + " -c user.name=" + quote(author.trim()) + " -c user.email=" + quote(email.trim())
                    + " commit -m " + quote(message.trim()), 2, null);
        } finally { PROJECT_OPERATION.set(false); }
    }

    private static void validateBranch(String name) throws IOException {
        ProjectGit.branch(name);
    }

    static String gitSyncCommand(String remote, String branch, boolean push) throws IOException {
        return ProjectGit.syncCommand(remote, branch, push);
    }

    static List<Entry> listFiles(Context context, File project, String relative) throws IOException {
        File base = checkProject(context, project);
        File directory = safeChild(base, relative);
        if (!directory.isDirectory()) throw new IOException("This folder is no longer available.");
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Cannot read this folder.");
        if (children.length > MAX_DIRECTORY_ENTRIES) {
            throw new IOException("This folder has more than " + MAX_DIRECTORY_ENTRIES
                    + " entries. Ask your agent to work with a smaller folder.");
        }
        List<Entry> result = new ArrayList<>();
        for (File child : children) {
            if (".git".equals(child.getName()) || Files.isSymbolicLink(child.toPath())) continue;
            if (!isInside(base, child)) continue;
            String path = base.toPath().relativize(child.toPath()).toString();
            result.add(new Entry(child.getName(), path, child.isDirectory(), child.length()));
        }
        Collections.sort(result, (a, b) -> a.directory == b.directory
                ? a.name.compareToIgnoreCase(b.name) : (a.directory ? -1 : 1));
        return result;
    }

    static String readText(Context context, File project, String relative) throws IOException {
        File file = safeChild(checkProject(context, project), relative);
        if (!file.isFile()) throw new IOException("This file is no longer available.");
        byte[] bytes = readBounded(file, MAX_TEXT_BYTES);
        for (byte value : bytes) {
            if (value == 0) throw new IOException("This is a binary file. Use an appropriate preview tool.");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IOException("The editor supports UTF-8 text files only.", e);
        }
    }

    static void saveText(Context context, File project, String relative, String text) throws IOException {
        if (text == null) throw new IOException("No text to save.");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) throw new IOException("The editor limit is 512 KB per file.");
        File base = checkProject(context, project);
        File target = safeChild(base, relative);
        if (target.equals(base) || target.isDirectory()) throw new IOException("Choose a file name.");
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory()) throw new IOException("The parent folder does not exist.");
        File temporary = File.createTempFile(".pocketagent-save-", ".tmp", parent);
        try {
            if (target.isFile()) {
                try {
                    android.system.Os.chmod(temporary.getPath(), android.system.Os.stat(target.getPath()).st_mode & 0777);
                } catch (android.system.ErrnoException e) {
                    throw new IOException("Could not preserve the file's permissions.", e);
                }
            }
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.getFD().sync();
            }
            safeChild(base, relative);
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    static void saveTextIfUnchanged(Context context, File project, String relative, String text,
                                    String original) throws IOException {
        if (original == null || !readText(context, project, relative).equals(original)) {
            throw new IOException("This file changed after you opened it. Reopen it before saving to protect the new changes.");
        }
        saveText(context, project, relative, text);
    }

    static String gitStatus(Context context, File project) throws IOException {
        return gitStatus(context, project, null);
    }

    static String gitStatus(Context context, File project, ContainerRuntime.OutputListener listener) throws IOException {
        return runGitInProject(context, project, "git --no-optional-locks --no-pager -c core.fsmonitor=false -c core.hooksPath=/dev/null"
                + " -c core.quotePath=false status --short --branch", 2, listener);
    }

    static String gitDiff(Context context, File project) throws IOException {
        return gitDiff(context, project, null);
    }

    static String gitDiff(Context context, File project, ContainerRuntime.OutputListener listener) throws IOException {
        // Both staged and unstaged changes; --no-ext-diff prevents repository-configured diff commands.
        return runGitInProject(context, project, "git --no-optional-locks --no-pager -c core.fsmonitor=false -c core.hooksPath=/dev/null"
                + " -c core.quotePath=false diff --no-ext-diff --no-textconv -- ."
                + " && printf '\\n--- STAGED CHANGES ---\\n'"
                + " && git --no-optional-locks --no-pager -c core.fsmonitor=false -c core.hooksPath=/dev/null"
                + " -c core.quotePath=false diff --cached --no-ext-diff --no-textconv -- .", 2, listener);
    }

    static String initializeGit(Context context, File project) throws IOException {
        beginProjectOperation(context);
        try { return runGitInProject(context, project, GIT + " init", 2, null); }
        finally { PROJECT_OPERATION.set(false); }
    }

    /** Invoked only after an explicit user action: package scripts execute project code. */
    static String installDependencies(Context context, File project,
                                      ContainerRuntime.OutputListener listener) throws IOException {
        requirePackageScript(context, project, null);
        return runInProject(context, project, "npm install --no-audit --no-fund", 20, listener);
    }

    static String buildProject(Context context, File project,
                               ContainerRuntime.OutputListener listener) throws IOException {
        requirePackageScript(context, project, "build");
        return runInProject(context, project, "npm run build", 20, listener);
    }

    /** The true branch runs the project's dev script and must be confirmed by the user. */
    static synchronized int startPreview(Context context, File project, boolean npm,
                                         ContainerRuntime.OutputListener listener) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Preview start was cancelled.");
        requireRuntime(context);
        File checked = checkProject(context, project);
        if (npm) requirePackageScript(context, checked, "dev");
        stopPreview();
        int port;
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            port = socket.getLocalPort();
        }
        String token = npm ? "" : newPreviewToken();
        if (!npm) installStaticPreview(context);
        String command = npm
                ? "npm run dev -- --host 127.0.0.1 --port " + port
                : "POCKETAGENT_PREVIEW_TOKEN=" + quote(token)
                    + " python3 -u /usr/local/lib/pocketagent/static-preview.py --port " + port
                    + " --root " + quote(guestPath(context, checked));
        Process process = ContainerRuntime.startContainer(context, "cd " + quote(guestPath(context, checked))
                + " && " + command);
        previewProcess = process;
        previewPort = port;
        previewProject = checked.getName();
        previewToken = token;
        LogCollector output = new LogCollector(process.getInputStream(), listener);
        Thread collector = new Thread(output, "PocketAgent-preview-output");
        collector.setDaemon(true);
        collector.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(npm ? 45 : 15);
        while (System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                stopPreview();
                throw new IOException("Preview start was cancelled.");
            }
            if (!process.isAlive()) {
                stopPreview();
                throw new IOException("Preview stopped before it was ready.\n" + output.value());
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return port;
            } catch (IOException waiting) {
                try { Thread.sleep(150); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    stopPreview();
                    throw new IOException("Preview start was cancelled.", interrupted);
                }
            }
        }
        stopPreview();
        throw new IOException("Preview did not open port " + port
                + ". For npm preview, the dev script must accept --host and --port.\n" + output.value());
    }

    static synchronized void stopPreview() {
        Process process = previewProcess;
        previewProcess = null;
        previewPort = 0;
        previewProject = null;
        previewToken = "";
        if (process != null) ProotProcess.stopAndWait(process);
    }

    static synchronized void stopPreview(Process expected) {
        if (expected == null) return;
        if (previewProcess == expected) stopPreview();
        else ProotProcess.stopAndWait(expected);
    }

    static Process previewProcessHandle(int port) {
        return previewPort == port ? previewProcess : null;
    }

    static String previewToken() { return previewPort() == 0 ? "" : previewToken; }

    private static String newPreviewToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder(64);
        for (byte item : bytes) value.append(String.format(java.util.Locale.US, "%02x", item & 255));
        return value.toString();
    }

    private static void installStaticPreview(Context context) throws IOException {
        File directory = safeChild(ContainerRuntime.rootfs(context), "usr/local/lib/pocketagent");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot prepare the preview helper.");
        File target = safeChild(directory, "static-preview.py");
        File temporary = File.createTempFile("preview-", ".tmp", directory);
        try {
            try (InputStream source = context.getAssets().open("pocketagent-static-preview.py");
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = source.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    static int previewPort() {
        Process process = previewProcess;
        return process != null && process.isAlive() ? previewPort : 0;
    }

    static String previewProject() {
        return previewPort() == 0 ? null : previewProject;
    }

    /** Export source, excluding dependencies, Git history and conventional credential files. */
    static void exportZip(Context context, File project, java.io.OutputStream destination) throws IOException {
        beginProjectOperation(context);
        try {
            File checked = checkProject(context, project);
            try (ZipOutputStream zip = new ZipOutputStream(destination)) {
                long[] budget = {0, 0}; zipDirectory(checked, checked, zip, budget, 0);
            }
        } finally { PROJECT_OPERATION.set(false); }
    }

    private static void zipDirectory(File base, File folder, ZipOutputStream zip, long[] budget, int depth)
            throws IOException {
        if (depth > 40) throw new IOException("Project folders are too deeply nested for export.");
        File[] children = folder.listFiles();
        if (children == null) throw new IOException("Cannot read a project folder during export.");
        for (File file : children) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Source export cancelled.");
            String name = file.getName();
            if (excludedExportName(name) || Files.isSymbolicLink(file.toPath())) continue;
            if (!isInside(base, file)) continue;
            if (++budget[0] > 10000) throw new IOException("Export limit: 10,000 project entries.");
            if (file.isDirectory()) { zipDirectory(base, file, zip, budget, depth + 1); continue; }
            if (!file.isFile()) continue;
            String path = base.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
            zip.putNextEntry(new ZipEntry(path));
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Source export cancelled.");
                    budget[1] += count;
                    if (budget[1] > 128L * 1024 * 1024) throw new IOException("Export limit: 128 MB of source files.");
                    zip.write(buffer, 0, count);
                }
            }
            zip.closeEntry();
        }
    }

    /** Conventional credential names only; source contents are not a complete secret scan. */
    private static boolean excludedExportName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals(".git") || lower.equals("node_modules") || lower.equals(".venv")
                || lower.equals(".signing")
                || lower.equals(".env") || lower.startsWith(".env.") || lower.equals(".npmrc")
                || lower.equals(".ssh") || lower.equals(".aws") || lower.equals(".kube")
                || lower.equals(".netrc") || lower.equals(".git-credentials")
                || lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".jks")
                || lower.endsWith(".p12") || lower.endsWith(".pfx") || lower.endsWith(".keystore")
                || lower.equals("credentials.json") || lower.equals("id_rsa")
                || lower.equals("id_ed25519") || lower.equals("id_ecdsa") || lower.equals("id_dsa")
                || (lower.startsWith("service-account") && lower.endsWith(".json"));
    }

    static String guestPath(Context context, File project) throws IOException {
        return "/home/coder/Projects/" + checkProject(context, project).getName();
    }

    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    static String validateGitUrl(String value) throws IOException {
        return ProjectGit.remote(value);
    }

    static File safeChild(File root, String relative) throws IOException {
        if (relative == null || relative.indexOf('\0') >= 0 || new File(relative).isAbsolute()) {
            throw new IOException("Invalid project path.");
        }
        String[] segments = relative.replace('\\', '/').split("/");
        File current = root.getCanonicalFile();
        for (String segment : segments) {
            if (segment.equals("..")) throw new IOException("Paths must stay inside this project.");
            if (segment.isEmpty() || segment.equals(".")) continue;
            current = new File(current, segment);
            if (Files.isSymbolicLink(current.toPath())) {
                throw new IOException("Linked files are not opened by the native editor.");
            }
        }
        if (!isInside(root, current)) throw new IOException("Paths must stay inside this project.");
        return current.getCanonicalFile();
    }

    private static boolean isInside(File root, File file) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        File canonicalFile = file.getCanonicalFile();
        return canonicalFile.equals(canonicalRoot)
                || canonicalFile.getPath().startsWith(canonicalRoot.getPath() + File.separator);
    }

    private static File workspace(Context context) throws IOException {
        File root = ContainerRuntime.workspaceRoot(context);
        File expected = safeChild(ContainerRuntime.rootfs(context), "home/coder/Projects");
        if (!root.getCanonicalFile().equals(expected)) throw new IOException("The projects folder is outside Ubuntu.");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create the projects folder.");
        if (!root.isDirectory()) throw new IOException("The projects folder is unavailable.");
        return root.getCanonicalFile();
    }

    private static File checkProject(Context context, File project) throws IOException {
        if (project == null) throw new IOException("Choose a project first.");
        File root = workspace(context);
        File checked = safeChild(root, project.getName());
        if (!checked.equals(project.getCanonicalFile()) || !checked.isDirectory() || checked.equals(root)) {
            throw new IOException("Choose a project inside the PocketAgent projects folder.");
        }
        return checked;
    }

    private static void validateName(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IOException("Use 1–64 letters, numbers, dots, hyphens or underscores; start with a letter or number.");
        }
    }

    private static void requireRuntime(Context context) throws IOException {
        if (!ContainerRuntime.isWorkspaceInstalled(context)) throw new IOException("Finish Ubuntu workspace setup first.");
    }

    private static void requirePackageScript(Context context, File project, String script) throws IOException {
        String json = readText(context, project, "package.json");
        try {
            JSONObject manifest = new JSONObject(json);
            if (script != null) {
                JSONObject scripts = manifest.optJSONObject("scripts");
                if (scripts == null || scripts.optString(script, "").trim().isEmpty()) {
                    throw new IOException("This project has no npm " + script + " script in package.json.");
                }
            }
        } catch (org.json.JSONException e) {
            throw new IOException("package.json is not valid JSON.", e);
        }
    }

    private static String runInProject(Context context, File project, String command, int minutes,
                                       ContainerRuntime.OutputListener listener) throws IOException {
        requireRuntime(context);
        return run(context, "cd " + quote(guestPath(context, project)) + " && " + command, minutes, listener);
    }

    private static String runGitInProject(Context context, File project, String command, int minutes,
                                          ContainerRuntime.OutputListener listener) throws IOException {
        File base = checkProject(context, project), metadata = safeChild(base, ".git");
        if (metadata.exists() && !metadata.isDirectory())
            throw new IOException("Native Git tools need a standalone repository. Linked worktrees are not managed by this screen.");
        for (String name : new String[]{"config", "config.worktree", "HEAD", "index", "objects", "refs"}) safeChild(metadata, name);
        String guest = guestPath(context, project);
        // A repository's core.worktree setting must not redirect this native operation to another project.
        return runInProject(context, project, "export GIT_WORK_TREE=" + quote(guest) + " GIT_DIR=" + quote(guest + "/.git") + "; " + command, minutes, listener);
    }

    private static String run(Context context, String command, int minutes,
                               ContainerRuntime.OutputListener listener) throws IOException {
        Process process = ContainerRuntime.startContainer(context, command);
        LogCollector collector = new LogCollector(process.getInputStream(), listener);
        Thread output = new Thread(collector, "PocketAgent-project-output");
        output.setDaemon(true);
        output.start();
        try {
            if (!process.waitFor(minutes, TimeUnit.MINUTES)) {
                throw new IOException("The project operation timed out.\n" + collector.value());
            }
            output.join(2000);
            if (process.exitValue() != 0) {
                throw new IOException("The project operation exited with code " + process.exitValue() + ".\n" + collector.value());
            }
            return collector.value();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("The project operation was cancelled.", e);
        } finally {
            ProotProcess.stopAndWait(process);
        }
    }

    private static byte[] readBounded(File file, int limit) throws IOException {
        if (file.length() > limit) throw new IOException("The editor limit is 512 KB per file.");
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("The file is too large for the native editor.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static final class LogCollector implements Runnable {
        private final InputStream stream;
        private final ContainerRuntime.OutputListener listener;
        private final StringBuilder log = new StringBuilder();
        LogCollector(InputStream stream, ContainerRuntime.OutputListener listener) {
            this.stream = stream;
            this.listener = listener;
        }
        @Override public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] chars = new char[2048];
                StringBuilder pending = new StringBuilder();
                int count;
                while ((count = reader.read(chars)) != -1) {
                    String chunk = new String(chars, 0, count);
                    synchronized (this) {
                        log.append(chunk);
                        if (log.length() > MAX_LOG_CHARS) log.delete(0, log.length() - MAX_LOG_CHARS);
                    }
                    if (listener != null) {
                        pending.append(chunk);
                        int newline;
                        while ((newline = pending.indexOf("\n")) >= 0) {
                            notifyLine(pending.substring(0, newline));
                            pending.delete(0, newline + 1);
                        }
                        if (pending.length() > 4096) {
                            notifyLine(pending.substring(0, 4096));
                            pending.delete(0, 4096);
                        }
                    }
                }
                if (listener != null && pending.length() > 0) notifyLine(pending.toString());
            } catch (IOException e) {
                synchronized (this) { log.append("\nOutput closed: ").append(e.getMessage()); }
            }
        }
        private void notifyLine(String line) {
            try { listener.line(line); } catch (RuntimeException ignored) { }
        }
        synchronized String value() { return log.toString(); }
    }
}
