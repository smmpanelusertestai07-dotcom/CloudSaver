package com.pocketagent.mobile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Guards Java-side runtime directories. PRoot itself is not a security sandbox. */
final class RuntimeStoragePaths {
    private RuntimeStoragePaths() {}

    static File directory(File privateRoot, String relative, boolean create) throws IOException {
        File base = privateRoot.getCanonicalFile();
        if (!base.isDirectory()) throw new IOException("Private storage directory is unavailable");
        if (relative == null || !relative.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*"))
            throw new IOException("Invalid private runtime directory");
        File current = base;
        for (String part : relative.split("/")) {
            current = new File(current, part);
            if (Files.isSymbolicLink(current.toPath()))
                throw new IOException("Runtime storage contains an unsupported symbolic link");
            if (current.exists() && !current.isDirectory())
                throw new IOException("Runtime storage path is not a directory");
            if (create && !current.isDirectory() && !current.mkdir() && !current.isDirectory())
                throw new IOException("Could not create a private runtime directory");
            if (!current.getCanonicalPath().startsWith(base.getPath() + File.separator))
                throw new IOException("Runtime storage leaves its private directory");
        }
        return current;
    }
}
