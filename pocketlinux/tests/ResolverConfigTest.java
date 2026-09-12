package com.pocketlinux;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

public final class ResolverConfigTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.US_ASCII);
    }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("pd-dns-test-").toFile();
        File etc = new File(root, "etc");
        etc.mkdir();
        File target = new File(etc, "resolv.conf");
        File outside = File.createTempFile("pd-dns-outside-", ".txt");
        Files.write(outside.toPath(), "keep\n".getBytes(StandardCharsets.US_ASCII));
        try {
            Files.createSymbolicLink(target.toPath(), outside.toPath());
            ResolverConfig.write(root, Arrays.asList(InetAddress.getByName("192.0.2.53"),
                    InetAddress.getByName("192.0.2.53"), InetAddress.getByName("2001:db8::53")));
            String mobile = read(target);
            check(mobile.contains("nameserver 192.0.2.53\n"), "mobile resolver not installed");
            check(mobile.contains(InetAddress.getByName("2001:db8::53").getHostAddress()), "IPv6 resolver lost");
            check(mobile.split("nameserver ", -1).length == 3, "duplicate resolver not removed");
            check(!Files.isSymbolicLink(target.toPath()), "guest resolver link followed");
            check(read(outside).equals("keep\n"), "file outside guest was modified");

            ResolverConfig.write(root, Collections.emptyList());
            check(mobile.equals(read(target)), "network loss erased last working DNS");
            ResolverConfig.write(root, Arrays.asList(InetAddress.getByName("0.0.0.0"),
                    InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")));
            check(mobile.equals(read(target)), "unusable stub DNS replaced active resolver");
            ResolverConfig.write(root, Collections.singletonList(InetAddress.getByName("198.51.100.53")));
            check(read(target).contains("198.51.100.53") && !read(target).contains("192.0.2.53"),
                    "Wi-Fi handover kept stale DNS");
            Files.delete(target.toPath());
            Files.delete(etc.toPath());
            Files.createSymbolicLink(etc.toPath(), outside.getParentFile().toPath());
            ResolverConfig.write(root, Collections.singletonList(InetAddress.getByName("192.0.2.53")));
            check(read(outside).equals("keep\n"), "symlinked guest etc escaped root");
            System.out.println("PASS ResolverConfig (IPv4/IPv6, network handover, empty-network retention, symlink safety)");
        } finally {
            if (!Files.isSymbolicLink(etc.toPath())) Files.deleteIfExists(target.toPath());
            Files.deleteIfExists(etc.toPath());
            Files.deleteIfExists(root.toPath());
            Files.deleteIfExists(outside.toPath());
        }
    }
}
