package com.pocketagent.mobile;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.SecureRandom;
import java.util.Locale;

/** Private project attachments and deliberate agent artifact downloads. IO runs on a worker thread. */
final class WorkspaceMedia {
    static final long MAX_FILE_BYTES = WorkspaceMediaPaths.MAX_FILE_BYTES;
    static final long MAX_IMAGE_BYTES = WorkspaceMediaPaths.MAX_IMAGE_BYTES;
    static final int MAX_INLINE_ENCODED_CHARS = WorkspaceMediaPaths.MAX_INLINE_ENCODED_CHARS;
    static final long MAX_IMAGE_PIXELS = 40_000_000L;
    private static final long SHARE_LIFETIME = 24L * 60 * 60 * 1000;
    private static final String IMPORTS = ".pocketagent/attachments";
    private static long remoteReserved;
    private WorkspaceMedia() { }

    /** Import only the system picker URI the user selected; never persist access to phone folders. */
    static JSONObject importUri(Context context, File project, Uri uri) throws IOException {
        WorkspaceTools.guestPath(context, project);
        if (uri == null || !"content".equals(uri.getScheme())) throw new IOException("Choose a file using the Android file picker.");
        String name = "attachment";
        long declared = -1;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int ni = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !cursor.isNull(ni)) name = cursor.getString(ni);
                if (si >= 0 && !cursor.isNull(si)) declared = cursor.getLong(si);
            }
        } catch (RuntimeException ignored) { /* Providers may omit optional metadata. */ }
        if (declared > MAX_FILE_BYTES) throw new IOException("Choose a file smaller than 512 MB.");
        String relative = IMPORTS + "/" + randomToken().substring(0, 24) + "-" + WorkspaceMediaPaths.safeName(name);
        File base = project.getCanonicalFile();
        ensureDirectory(base, ".pocketagent");
        ensureDirectory(base, IMPORTS);
        File directory = WorkspaceTools.safeChild(base, IMPORTS);
        File[] existing = directory.listFiles();
        if (existing == null || existing.length >= 200) throw new IOException("This project has 200 imported files. Remove unused attachments first.");
        long used = 0;
        for (File file : existing) if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) used += file.length();
        if (used >= 512L * 1024 * 1024) throw new IOException("Imported attachments reached 512 MB. Remove unused files first.");
        File target = WorkspaceTools.safeChild(base, relative);
        boolean finished = false;
        Object createdKey = null;
        try (InputStream source = context.getContentResolver().openInputStream(uri)) {
            if (source == null) throw new IOException("The selected file cannot be read.");
            try (ParcelFileDescriptor descriptor = createChecked(base, relative);
                 FileOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                createdKey = Files.readAttributes(target.toPath(), java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).fileKey();
                WorkspaceMediaPaths.copyBounded(source, output, Math.min(MAX_FILE_BYTES, 512L * 1024 * 1024 - used));
                output.getFD().sync();
            }
            WorkspaceMediaPaths.file(base, relative);
            finished = true;
            return describe(context, base, relative);
        } catch (SecurityException e) {
            throw new IOException("File access expired. Select the file again.", e);
        } finally {
            if (!finished && createdKey != null) {
                try {
                    File checked = WorkspaceTools.safeChild(base, relative);
                    Object currentKey = Files.readAttributes(checked.toPath(), java.nio.file.attribute.BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS).fileKey();
                    if (createdKey.equals(currentKey)) Files.deleteIfExists(checked.toPath());
                }
                catch (IOException ignored) { }
            }
        }
    }

    static JSONObject describe(Context context, File project, String relative) throws IOException {
        WorkspaceTools.guestPath(context, project);
        File file = WorkspaceMediaPaths.file(project, relative);
        String mime;
        long size;
        try (ParcelFileDescriptor descriptor = openProjectFile(context, project, relative);
             FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            size = descriptor.getStatSize();
            byte[] header = new byte[32];
            int count = input.read(header);
            mime = WorkspaceMediaPaths.mime(header, Math.max(0, count), file.getName());
        }
        String kind = mime.startsWith("image/") ? "image" : mime.startsWith("audio/") ? "audio"
                : mime.startsWith("video/") ? "video" : "file";
        JSONObject result = new JSONObject();
        try {
            result.put("path", project.getCanonicalFile().toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/'));
            result.put("name", file.getName()); result.put("mime", mime); result.put("size", size);
            result.put("kind", kind); result.put("imageInput", false);
            if (kind.equals("image") && size <= MAX_IMAGE_BYTES) {
                try {
                    BitmapFactory.Options options = imageBounds(context, project, relative);
                    result.put("width", options.outWidth); result.put("height", options.outHeight);
                    result.put("imageInput", true);
                } catch (IOException ignored) { result.put("imageInput", false); }
            }
            return result;
        } catch (org.json.JSONException e) { throw new IOException("Could not read file metadata.", e); }
    }

    static String localImagePath(Context context, File project, String relative) throws IOException {
        JSONObject metadata = describe(context, project, relative);
        if (!metadata.optBoolean("imageInput")) throw new IOException("Image input supports valid PNG, JPEG, GIF or WebP up to 20 MB and 40 megapixels.");
        Bitmap decoded = thumbnail(context, project, relative, 256);
        if (decoded == null) throw new IOException("Android could not decode this image. Choose another image.");
        decoded.recycle();
        // Revalidate immediately before the CLI receives its project-local path.
        WorkspaceMediaPaths.file(project, relative);
        return WorkspaceTools.guestPath(context, project) + "/" + metadata.optString("path");
    }

    static Bitmap thumbnail(Context context, File project, String relative, int maximumEdge) throws IOException {
        // Decode bounds and pixels from the same bounded byte snapshot: an agent
        // replacing/resizing the source between two decodes cannot defeat the pixel limit.
        byte[] bytes = imageBytes(context, project, relative);
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        validateBounds(options);
        int edge = Math.max(64, Math.min(2048, maximumEdge));
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > edge) options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) throw new IOException("This image cannot be decoded.");
            return bitmap;
        } catch (OutOfMemoryError exhausted) { throw new IOException("This image is too large for the available memory.", exhausted); }
    }

    private static byte[] imageBytes(Context context, File project, String relative) throws IOException {
        try (ParcelFileDescriptor descriptor = openProjectFile(context, project, relative);
             FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            long length = descriptor.getStatSize();
            if (length <= 0 || length > MAX_IMAGE_BYTES) throw new IOException("Image preview limit: 20 MB.");
            byte[] bytes = new byte[(int)length];
            int offset = 0;
            while (offset < bytes.length) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Image preview cancelled.");
                int read = input.read(bytes,offset,bytes.length-offset);
                if (read < 0) throw new IOException("This image changed while opening it. Try again.");
                offset += read;
            }
            if (input.read() != -1) throw new IOException("This image changed while opening it. Try again.");
            return bytes;
        } catch (OutOfMemoryError exhausted) { throw new IOException("This image is too large for the available memory.", exhausted); }
    }

    private static BitmapFactory.Options imageBounds(Context context, File project, String relative) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        try (ParcelFileDescriptor descriptor = openProjectFile(context, project, relative)) {
            if (descriptor.getStatSize() > MAX_IMAGE_BYTES) throw new IOException("Image preview limit: 20 MB.");
            BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, options);
        }
        validateBounds(options);
        return options;
    }

    private static void validateBounds(BitmapFactory.Options options) throws IOException {
        if (options.outWidth <= 0 || options.outHeight <= 0
                || (long) options.outWidth * options.outHeight > MAX_IMAGE_PIXELS) throw new IOException("Image preview limit: 40 megapixels.");
        if (!("image/png".equals(options.outMimeType) || "image/jpeg".equals(options.outMimeType)
                || "image/gif".equals(options.outMimeType) || "image/webp".equals(options.outMimeType))) {
            throw new IOException("This image format is not supported for native preview.");
        }
    }

    static JSONArray artifacts(Context context, File project, String markdown) {
        JSONArray result = new JSONArray();
        try {
            String guest = WorkspaceTools.guestPath(context, project);
            for (String path : WorkspaceMediaPaths.artifactPaths(project, guest, markdown)) {
                try { result.put(describe(context, project, path)); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
        return result;
    }

    /** Explicit local link only. A real Ubuntu output is snapshotted privately before Save/Share. */
    static synchronized Uri resolveArtifactCopy(Context context, File project, String destination) throws IOException {
        String guest=WorkspaceTools.guestPath(context,project);
        AgentArtifactPaths.Target target=AgentArtifactPaths.target(ContainerRuntime.rootfs(context),project,guest,
                ContainerRuntime.shared(context),destination);
        Uri previous=savedRemote(context,target.identity);
        File source;
        try {source=target.regularFile();}
        catch(IOException unavailable) {
            if(previous!=null&&!Files.exists(target.file.toPath(),LinkOption.NOFOLLOW_LINKS))return previous;
            throw new IOException("This file was not found in the workspace. Ask the agent to create it or send a working download link.",unavailable);
        }
        String sourceKey=String.valueOf(Files.readAttributes(source.toPath(),java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey());
        long sourceSize=source.length(),sourceModified=source.lastModified();
        if(previous!=null) {
            JSONObject info=describeShared(context,previous);
            if(sourceKey.equals(info.optString("sourceFileKey"))&&sourceSize==info.optLong("sourceSize",-1)
                    &&sourceModified==info.optLong("sourceModified",-1)) {
                try(ParcelFileDescriptor checked=openArtifactFile(target)) {return previous;}
            }
        }
        File directory=WorkspaceMediaStore.directory(context.getFilesDir());
        long maximum=Math.min(MAX_FILE_BYTES,WorkspaceMediaStore.available(directory,remoteReserved));
        if(sourceSize>MAX_FILE_BYTES)throw new IOException("Save limit: 512 MB per file.");
        if(maximum<=0||sourceSize>maximum)throw new IOException("Not enough storage to save this file. Free some space and retry.");
        String token=randomToken(),name=WorkspaceMediaPaths.safeName(source.getName());
        File body=WorkspaceMediaStore.file(directory,token,".bin"),metadata=WorkspaceMediaStore.file(directory,token,".json");
        boolean complete=false;
        try {
            Files.createFile(body.toPath());
            long size;
            try(ParcelFileDescriptor descriptor=openArtifactFile(target);
                FileInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                FileOutputStream output=new FileOutputStream(body)) {
                size=WorkspaceMediaPaths.copyBounded(input,output,maximum);output.getFD().sync();
            }
            java.nio.file.attribute.BasicFileAttributes after=Files.readAttributes(target.regularFile().toPath(),
                    java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(size!=sourceSize||after.size()!=sourceSize||after.lastModifiedTime().toMillis()!=sourceModified
                    ||!String.valueOf(after.fileKey()).equals(sourceKey))
                throw new IOException("This file is still changing. Wait for the agent to finish, then try again.");
            String mime;
            try(FileInputStream input=new FileInputStream(body)) {
                byte[] header=new byte[512];int count=input.read(header);
                mime=WorkspaceMediaPaths.mime(header,Math.max(0,count),name);
            }
            JSONObject info=new JSONObject();
            try {
                info.put("name",name);info.put("mime",mime);info.put("size",size);
                info.put("kind",mime.startsWith("image/")?"image":mime.startsWith("video/")?"video":mime.startsWith("audio/")?"audio":"file");
                info.put("sourceHost","On-device workspace");
                // Reuse only an unchanged source; unavailable temporary files retain their saved copy.
                info.put("sourceFileKey",sourceKey);info.put("sourceSize",sourceSize);info.put("sourceModified",sourceModified);
            }catch(org.json.JSONException invalid){throw new IOException("Could not describe this file.",invalid);}
            writeMetadata(metadata,info);rememberRemote(directory,target.identity,token);complete=true;
            return mediaUri(context,token,name);
        }finally{if(!complete){body.delete();metadata.delete();}}
    }

    /** Pin the opened descriptor to the prechecked path; reject parent swaps, pipes and hard links. */
    private static ParcelFileDescriptor openArtifactFile(AgentArtifactPaths.Target target) throws IOException {
        FileDescriptor raw=null;ParcelFileDescriptor descriptor=null;
        try {
            raw=Os.open(target.file.getPath(),OsConstants.O_RDONLY|OsConstants.O_NONBLOCK|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0);
            android.system.StructStat stat=Os.fstat(raw);
            if(!OsConstants.S_ISREG(stat.st_mode)||stat.st_nlink!=1)throw new IOException("Only regular, unlinked output files can be downloaded.");
            descriptor=ParcelFileDescriptor.dup(raw);
            String actual=Os.readlink("/proc/self/fd/"+descriptor.getFd());
            if(!actual.equals(target.file.getPath())||!actual.startsWith(target.base.getPath()+"/"))
                throw new IOException("This file changed while opening. Try again.");
            target.regularFile();
            ParcelFileDescriptor result=descriptor;descriptor=null;return result;
        }catch(ErrnoException failure){throw new IOException("Could not safely open this output file.",failure);}
        finally {
            if(descriptor!=null)try{descriptor.close();}catch(IOException ignored){}
            if(raw!=null)try{Os.close(raw);}catch(ErrnoException ignored){}
        }
    }

    /** Explicit file download. The private copy survives cache clearing and app restarts. */
    static Uri remoteCopy(Context context, String url) throws IOException { return remoteCopy(context, url, false); }
    /** An image reference may load automatically; never persist a non-image response. */
    static Uri remoteImageCopy(Context context, String url) throws IOException { return remoteCopy(context, url, true); }
    private static Uri remoteCopy(Context context, String url, boolean imageOnly) throws IOException {
        final File directory, body, metadata;
        final String token;
        final long maximum;
        synchronized (WorkspaceMedia.class) {
            Uri existing = savedRemote(context, url);
            if (existing != null) {
                if (imageOnly && !"image".equals(describeShared(context, existing).optString("kind"))) throw new IOException("This link is not an image.");
                return existing;
            }
            directory = WorkspaceMediaStore.directory(context.getFilesDir());
            maximum = Math.min(imageOnly ? MAX_IMAGE_BYTES : MAX_FILE_BYTES, WorkspaceMediaStore.available(directory, remoteReserved));
            if (maximum <= 0) throw new IOException("Not enough storage to save this file. Free some space and retry.");
            token = randomToken(); body = WorkspaceTools.safeChild(directory, token + ".bin"); metadata = WorkspaceTools.safeChild(directory, token + ".json");
            Files.createFile(body.toPath()); remoteReserved += maximum;
        }
        boolean complete = false;
        try {
            RemoteMediaFetch.Result result;
            try (FileOutputStream output = new FileOutputStream(body)) {
                result = RemoteMediaFetch.download(url, output, maximum); output.getFD().sync();
            }
            if (imageOnly && !result.mime.startsWith("image/")) throw new IOException("This link is not an image.");
            JSONObject info = new JSONObject();
            try {
                info.put("name", result.name); info.put("mime", result.mime); info.put("size", result.size);
                info.put("kind", result.mime.startsWith("image/") ? "image" : result.mime.startsWith("video/") ? "video" : result.mime.startsWith("audio/") ? "audio" : "file");
                // Keep only the source host. Signed query strings are not written into cache metadata.
                info.put("sourceHost", RemoteMediaPolicy.uri(url).getHost());
            } catch (org.json.JSONException bad) { throw new IOException("Could not describe the downloaded file.", bad); }
            try (FileOutputStream output = new FileOutputStream(metadata)) { output.write(info.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
            rememberRemote(directory, url, token);
            complete = true;
            return mediaUri(context, token, result.name);
        } finally { if (!complete) { body.delete(); metadata.delete(); } synchronized (WorkspaceMedia.class) { remoteReserved -= maximum; } }
    }

    /** Engine image bytes are saved privately; only their opaque URI enters snapshots/history. */
    static synchronized Uri cacheInlineImage(Context context, String mime, String base64) throws IOException {
        if (base64 == null || base64.isEmpty() || base64.length() > MAX_INLINE_ENCODED_CHARS)
            throw new IOException("This inline image is too large. Ask the agent to save it as a project file.");
        File directory = WorkspaceMediaStore.directory(context.getFilesDir());
        long expected = ((long) base64.length() + 3) / 4 * 3;
        if (expected > WorkspaceMediaStore.available(directory, remoteReserved))
            throw new IOException("Not enough storage to save this image. Free some space and retry.");
        String token = randomToken();
        File body = WorkspaceMediaStore.file(directory, token, ".bin"), metadata = WorkspaceMediaStore.file(directory, token, ".json");
        boolean complete = false;
        try {
            Files.createFile(body.toPath());
            long size;
            try (FileOutputStream output = new FileOutputStream(body)) {
                size = WorkspaceMediaPaths.decodeInlineImage(base64, output); output.getFD().sync();
            }
            String actual;
            try (FileInputStream input = new FileInputStream(body)) {
                byte[] header = new byte[32]; int count = input.read(header);
                actual = WorkspaceMediaPaths.mime(header, Math.max(0, count), "image");
            }
            if ((mime != null && !mime.isEmpty() && !actual.equals(mime)) || !(actual.equals("image/png")
                    || actual.equals("image/jpeg") || actual.equals("image/webp") || actual.equals("image/gif")))
                throw new IOException("Unsupported image output.");
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(body.getPath(), bounds); validateBounds(bounds);
            String name = "Image." + (actual.equals("image/jpeg") ? "jpg" : actual.substring(6));
            JSONObject info = new JSONObject();
            try { info.put("name", name); info.put("mime", actual); info.put("size", size); info.put("kind", "image"); info.put("sourceHost", "Agent output"); }
            catch (org.json.JSONException bad) { throw new IOException("Could not describe image output.", bad); }
            writeMetadata(metadata, info);
            complete = true;
            return mediaUri(context, token, name);
        } finally { if (!complete) { body.delete(); metadata.delete(); } }
    }

    /** Local-only lookup; calling this never contacts the source URL. */
    static synchronized Uri savedRemote(Context context, String url) throws IOException {
        File directory = WorkspaceMediaStore.directory(context.getFilesDir());
        File reference = WorkspaceMediaStore.file(directory, WorkspaceMediaStore.sourceKey(url), ".ref");
        if (!Files.isRegularFile(reference.toPath(), LinkOption.NOFOLLOW_LINKS) || reference.length() != 64) return null;
        String token = new String(Files.readAllBytes(reference.toPath()), StandardCharsets.US_ASCII);
        try {
            File metadata = WorkspaceMediaStore.saved(directory, token, true);
            JSONObject info = new JSONObject(new String(Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8));
            return mediaUri(context, token, info.optString("name", "File"));
        } catch (IOException | org.json.JSONException unavailable) { return null; }
    }

    private static synchronized void rememberRemote(File directory, String url, String token) throws IOException {
        String key = WorkspaceMediaStore.sourceKey(url);
        File reference = WorkspaceMediaStore.file(directory, key, ".ref"), temporary = WorkspaceMediaStore.file(directory, key, ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(token.getBytes(StandardCharsets.US_ASCII)); output.getFD().sync(); }
            Files.move(temporary.toPath(), reference.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { temporary.delete(); }
    }

    private static void writeMetadata(File target, JSONObject info) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) { output.write(info.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync(); }
    }

    private static Uri mediaUri(Context context, String token, String name) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".projectfiles")
                .appendPath("file").appendPath(token).appendPath(WorkspaceMediaPaths.safeName(name)).build();
    }

    static JSONObject describeShared(Context context, Uri uri) throws IOException {
        String token = WorkspaceMediaPaths.shareToken(context.getPackageName() + ".projectfiles", uri.toString());
        File body = sharedFile(context, token, false), metadata = sharedFile(context, token, true);
        try { JSONObject result = new JSONObject(new String(Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8)); result.put("size", body.length()); return result; }
        catch (org.json.JSONException bad) { throw new IOException("The cached file is unavailable.", bad); }
    }

    static Bitmap thumbnailShared(Context context, Uri uri, int maximumEdge) throws IOException {
        String token = WorkspaceMediaPaths.shareToken(context.getPackageName() + ".projectfiles", uri.toString());
        File body = sharedFile(context, token, false);
        if (body.length() <= 0 || body.length() > MAX_IMAGE_BYTES) throw new IOException("Image preview limit: 20 MB.");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(body.getPath(), options); validateBounds(options);
        options.inJustDecodeBounds = false; options.inSampleSize = 1;
        int edge = Math.max(64, Math.min(2048, maximumEdge));
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > edge) options.inSampleSize *= 2;
        try { Bitmap bitmap = BitmapFactory.decodeFile(body.getPath(), options); if (bitmap == null) throw new IOException("This image cannot be decoded."); return bitmap; }
        catch (OutOfMemoryError exhausted) { throw new IOException("This image is too large for the available memory.", exhausted); }
    }

    static boolean isSvg(JSONObject info) {
        return "image/svg+xml".equals(info.optString("mime")) || info.optString("name").toLowerCase(Locale.ROOT).endsWith(".svg");
    }

    static String svgShared(Context context, Uri uri) throws IOException {
        String token = WorkspaceMediaPaths.shareToken(context.getPackageName() + ".projectfiles", uri.toString());
        File body = sharedFile(context, token, false);
        if (body.length() <= 0 || body.length() > SafeSvg.MAX_BYTES) throw new IOException("SVG preview limit: 512 KB.");
        return SafeSvg.sanitize(Files.readAllBytes(body.toPath()), 0xff202123);
    }

    static String svgProject(Context context, File project, String relative) throws IOException {
        try (ParcelFileDescriptor descriptor = openProjectFile(context, project, relative);
             FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            if (descriptor.getStatSize() <= 0 || descriptor.getStatSize() > SafeSvg.MAX_BYTES) throw new IOException("SVG preview limit: 512 KB.");
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            WorkspaceMediaPaths.copyBounded(input, bytes, SafeSvg.MAX_BYTES);
            return SafeSvg.sanitize(bytes.toByteArray(), 0xff202123);
        }
    }

    static void saveShared(Context context, Uri source, Uri destination) throws IOException {
        if (destination == null || !"content".equals(destination.getScheme()) || (context.getPackageName() + ".projectfiles").equals(destination.getAuthority())) throw new IOException("Choose a destination with the Android save picker.");
        String token = WorkspaceMediaPaths.shareToken(context.getPackageName() + ".projectfiles", source.toString());
        try (InputStream input = new FileInputStream(sharedFile(context, token, false)); OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) throw new IOException("The selected destination cannot be written.");
            WorkspaceMediaPaths.copyBounded(input, output, MAX_FILE_BYTES);
        } catch (SecurityException error) { throw new IOException("Save access expired. Choose a destination again.", error); }
    }

    static void openSharedPreview(Context context, Uri uri) {
        context.startActivity(new Intent(context, MediaPreviewActivity.class).putExtra(MediaPreviewActivity.EXTRA_SHARED, uri.toString()));
    }

    static void openPreview(Context context, File project, String relative) {
        Intent intent = new Intent(context, MediaPreviewActivity.class)
                .putExtra(MediaPreviewActivity.EXTRA_PROJECT, project.getName())
                .putExtra(MediaPreviewActivity.EXTRA_PATH, relative);
        if (!(context instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** Creates an immutable copy for a deliberate Share/Play action. It expires after 24 hours. */
    static synchronized Uri contentUri(Context context, File project, String relative) throws IOException {
        JSONObject info = describe(context, project, relative);
        if (info.optLong("size") > MAX_FILE_BYTES) throw new IOException("Share and media playback limit: 512 MB.");
        File directory = shareDirectory(context);
        cleanupShares(directory);
        long occupied = 0;
        File[] existing = directory.listFiles();
        if (existing != null && existing.length >= 240) throw new IOException("Media cache is full. Clear the app cache in Android settings.");
        if (existing != null) for (File file : existing) occupied += file.length();
        if (occupied + info.optLong("size") > 1024L * 1024 * 1024
                || info.optLong("size") > directory.getUsableSpace()-WorkspaceMediaStore.FREE_SPACE_RESERVE)
            throw new IOException("Not enough temporary storage to share this file. Free some space and retry.");
        String token = randomToken();
        File body = WorkspaceTools.safeChild(directory, token + ".bin");
        File metadata = WorkspaceTools.safeChild(directory, token + ".json");
        boolean complete = false;
        try {
            Files.createFile(body.toPath());
            try (ParcelFileDescriptor descriptor = openProjectFile(context, project, relative);
                 FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                 FileOutputStream output = new FileOutputStream(body)) {
                WorkspaceMediaPaths.copyBounded(input, output, MAX_FILE_BYTES); output.getFD().sync();
            }
            try (FileOutputStream output = new FileOutputStream(metadata)) {
                output.write(info.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
            }
            complete = true;
            return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".projectfiles")
                    .appendPath("file").appendPath(token).appendPath(WorkspaceMediaPaths.safeName(info.optString("name"))).build();
        } finally { if (!complete) { body.delete(); metadata.delete(); } }
    }

    /** A deliberate external Share receives a disposable snapshot, never a grant to durable media. */
    static synchronized Uri shareCopy(Context context, Uri source) throws IOException {
        JSONObject info = describeShared(context, source);
        String sourceToken = WorkspaceMediaPaths.shareToken(context.getPackageName() + ".projectfiles", source.toString());
        File original = sharedFile(context, sourceToken, false), directory = shareDirectory(context);
        cleanupShares(directory);
        File[] existing = directory.listFiles(); long occupied = 0;
        if (existing == null || existing.length >= 240) throw new IOException("Temporary shares are full. Clear app cache and try again.");
        for (File file : existing) if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) occupied += file.length();
        if (occupied + original.length() > 1024L * 1024 * 1024
                || original.length() > directory.getUsableSpace()-WorkspaceMediaStore.FREE_SPACE_RESERVE)
            throw new IOException("Not enough temporary storage to share this file. Free some space and retry.");
        String token = randomToken();
        File body = WorkspaceTools.safeChild(directory, token + ".bin"), metadata = WorkspaceTools.safeChild(directory, token + ".json");
        boolean complete = false;
        try {
            Files.createFile(body.toPath());
            try (FileInputStream input = new FileInputStream(original); FileOutputStream output = new FileOutputStream(body)) {
                WorkspaceMediaPaths.copyBounded(input, output, MAX_FILE_BYTES); output.getFD().sync();
            }
            try { info.put("temporary", true); } catch (org.json.JSONException invalid) { throw new IOException("Cannot prepare the shared copy.", invalid); }
            writeMetadata(metadata, info); complete = true;
            return mediaUri(context, token, info.optString("name", "File"));
        } finally { if (!complete) { body.delete(); metadata.delete(); } }
    }

    static void saveTo(Context context, File project, String relative, Uri destination) throws IOException {
        if (destination == null || !"content".equals(destination.getScheme())
                || (context.getPackageName() + ".projectfiles").equals(destination.getAuthority())) {
            throw new IOException("Choose a destination with the Android save picker.");
        }
        try (ParcelFileDescriptor descriptor = openProjectFile(context, project, relative);
             FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            if (descriptor.getStatSize() > MAX_FILE_BYTES) throw new IOException("Save limit: 512 MB per file.");
            try (OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("The selected destination cannot be written.");
                WorkspaceMediaPaths.copyBounded(input, output, MAX_FILE_BYTES);
            }
        } catch (SecurityException e) { throw new IOException("Save access expired. Choose a destination again.", e); }
    }

    /** O_NOFOLLOW plus descriptor validation closes the check/open symlink race before reading bytes. */
    static ParcelFileDescriptor openProjectFile(Context context, File project, String relative) throws IOException {
        WorkspaceTools.guestPath(context, project);
        File file = WorkspaceMediaPaths.file(project, relative);
        return openChecked(project.getCanonicalFile(), file, OsConstants.O_RDONLY | OsConstants.O_NONBLOCK);
    }

    private static ParcelFileDescriptor createChecked(File base, String relative) throws IOException {
        File file = WorkspaceTools.safeChild(base, relative);
        return openChecked(base, file, OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL);
    }

    private static ParcelFileDescriptor openChecked(File base, File file, int flags) throws IOException {
        FileDescriptor raw = null;
        ParcelFileDescriptor descriptor = null;
        try {
            raw = Os.open(file.getPath(), flags | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0600);
            if (!OsConstants.S_ISREG(Os.fstat(raw).st_mode)) throw new IOException("Only regular project files can be opened.");
            descriptor = ParcelFileDescriptor.dup(raw);
            String actual = Os.readlink("/proc/self/fd/" + descriptor.getFd());
            String expected = WorkspaceMediaPaths.file(base,
                    base.toPath().relativize(file.toPath()).toString()).getCanonicalPath();
            if (!actual.equals(expected) || !actual.startsWith(base.getCanonicalPath() + "/")) {
                throw new IOException("The file moved outside the project. Please retry.");
            }
            ParcelFileDescriptor result = descriptor; descriptor = null; return result;
        } catch (ErrnoException e) { throw new IOException("Could not safely open this project file.", e); }
        finally {
            if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
            if (raw != null) try { Os.close(raw); } catch (ErrnoException ignored) { }
        }
    }

    static File shareDirectory(Context context) throws IOException {
        File directory = WorkspaceTools.safeChild(context.getCacheDir(), "project-shares");
        if (!directory.exists() && !directory.mkdir()) throw new IOException("Cannot prepare a temporary shared file.");
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("The share cache is unavailable.");
        return directory;
    }

    static synchronized File sharedFile(Context context, String token, boolean metadata) throws IOException {
        WorkspaceMediaPaths.token(token);
        File durable = WorkspaceMediaStore.directory(context.getFilesDir());
        try { return WorkspaceMediaStore.saved(durable, token, metadata); }
        catch (IOException unavailable) { /* Legacy cache or explicit temporary share. */ }
        File cache = shareDirectory(context);
        File body = WorkspaceTools.safeChild(cache, token + ".bin"), infoFile = WorkspaceTools.safeChild(cache, token + ".json");
        if (!Files.isRegularFile(body.toPath(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(infoFile.toPath(), LinkOption.NOFOLLOW_LINKS)
                || body.length() > MAX_FILE_BYTES || infoFile.length() > 8192)
            throw new IOException("This file is unavailable. Choose it again or ask the agent to save a copy.");
        // beta.8 used temporary storage for agent/remote previews. Preserve available copies on first open.
        try {
            JSONObject info = new JSONObject(new String(Files.readAllBytes(infoFile.toPath()), StandardCharsets.UTF_8));
            if (!info.optString("sourceHost").isEmpty() && !info.optBoolean("temporary")) {
                if (body.length() <= WorkspaceMediaStore.available(durable, remoteReserved)) {
                    File targetBody = WorkspaceMediaStore.file(durable, token, ".bin"), targetInfo = WorkspaceMediaStore.file(durable, token, ".json");
                    Files.copy(body.toPath(), targetBody.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    writeMetadata(targetInfo, info);
                    return WorkspaceMediaStore.saved(durable, token, metadata);
                }
                // Still permit a legacy saved preview while it exists, even when there is no space to migrate it.
                return metadata ? infoFile : body;
            }
        } catch (org.json.JSONException invalid) { throw new IOException("File metadata is unavailable.", invalid); }
        File result = metadata ? infoFile : body;
        if (result.lastModified() < System.currentTimeMillis() - SHARE_LIFETIME)
            throw new IOException("This shared copy expired. Share the file again.");
        return result;
    }

    private static void cleanupShares(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        long before = System.currentTimeMillis() - SHARE_LIFETIME;
        for (File file : files) if (file.getName().matches("[a-f0-9]{64}\\.(bin|json)")
                && !Files.isSymbolicLink(file.toPath()) && file.lastModified() < before) {
            String token = file.getName().substring(0, 64);
            File info = new File(directory, token + ".json");
            try {
                if (Files.isRegularFile(info.toPath(), LinkOption.NOFOLLOW_LINKS) && info.length() <= 8192) {
                    JSONObject record = new JSONObject(new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8));
                    if (!record.optString("sourceHost").isEmpty() && !record.optBoolean("temporary")) continue;
                }
            } catch (IOException | org.json.JSONException ignored) { }
            file.delete();
        }
    }

    private static void ensureDirectory(File base, String relative) throws IOException {
        File directory = WorkspaceTools.safeChild(base, relative);
        if (!directory.exists() && !directory.mkdir()) throw new IOException("Cannot prepare the attachments folder.");
        if (!Files.isDirectory(WorkspaceTools.safeChild(base, relative).toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The attachments folder is unavailable.");
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        StringBuilder token = new StringBuilder(64);
        for (byte value : bytes) token.append(String.format(Locale.ROOT, "%02x", value & 255));
        return token.toString();
    }
}
