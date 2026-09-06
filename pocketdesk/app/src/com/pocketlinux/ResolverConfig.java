package com.pocketlinux;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;

/** Pure-Java resolver writer, also exercised on the build host. Never follows guest symlinks. */
final class ResolverConfig {
    static synchronized void write(File root, List<InetAddress> servers) throws IOException {
        File etc = new File(root, "etc");
        if (!etc.isDirectory() || !etc.getCanonicalFile().equals(new File(root.getCanonicalFile(), "etc"))) return;
        LinkedHashSet<String> addresses = new LinkedHashSet<>();
        for (InetAddress server : servers) {
            if (server == null || server.isAnyLocalAddress() || server.isLoopbackAddress()
                    || server.isMulticastAddress()) continue;
            String address = server.getHostAddress();
            if (address != null && address.matches("[0-9a-fA-F:.]+(%[A-Za-z0-9_.-]+)?")) addresses.add(address);
        }
        if (addresses.isEmpty()) return;
        StringBuilder config = new StringBuilder("# PocketLinux: phone network DNS\noptions timeout:2 attempts:2\n");
        int count = 0;
        for (String address : addresses) {
            config.append("nameserver ").append(address).append('\n');
            if (++count == 3) break; // glibc MAXNS
        }
        File target = new File(etc, "resolv.conf");
        byte[] bytes = config.toString().getBytes(StandardCharsets.US_ASCII);
        if (Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)
                && Files.size(target.toPath()) < 4096
                && java.util.Arrays.equals(bytes, Files.readAllBytes(target.toPath()))) return;
        File temporary = File.createTempFile(".pocketdesk-resolv-", ".tmp", etc);
        try {
            Files.write(temporary.toPath(), bytes);
            temporary.setReadable(true, false);
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }
}
