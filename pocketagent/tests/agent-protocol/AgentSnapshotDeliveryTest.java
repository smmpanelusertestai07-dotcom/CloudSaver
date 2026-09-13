package com.pocketagent.mobile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Regressions for installer progress delivery while the agent worker is occupied. */
public final class AgentSnapshotDeliveryTest {
    public static void main(String[] args) throws Exception {
        progressDoesNotNeedIdleWorker();
        coalescesAndKeepsUpdatesDuringDelivery();
        System.out.println("PASS AgentSnapshotDeliveryTest (blocked installer and delivery races)");
    }

    private static void progressDoesNotNeedIdleWorker() throws Exception {
        ExecutorService ui = Executors.newSingleThreadExecutor();
        ExecutorService agent = Executors.newSingleThreadExecutor();
        AtomicReference<String> latest = new AtomicReference<>("");
        CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1);
        List<String> received = new ArrayList<>();
        AgentSnapshotDelivery delivery = new AgentSnapshotDelivery(ui::execute, () -> {
            String value = latest.get();
            synchronized (received) { received.add(value); }
            if (value.equals("Downloading")) first.countDown();
            if (value.equals("Verifying")) second.countDown();
        });
        try {
            // This single task occupies the entire serialized agent executor,
            // just as AgentInstaller.install() does inside the Connect action.
            Future<?> installation = agent.submit(() -> {
                latest.set("Downloading"); delivery.publish();
                await(first, "Initial installer progress was not delivered");
                latest.set("Verifying"); delivery.publish();
                await(second, "Progress stalled until the installer worker became idle");
            });
            installation.get(8, TimeUnit.SECONDS);
            synchronized (received) {
                if (received.size() != 2 || !received.get(1).equals("Verifying"))
                    throw new AssertionError("Installer progress was lost: " + received);
            }
        } finally {
            agent.shutdownNow(); ui.shutdownNow();
        }
    }

    private static void coalescesAndKeepsUpdatesDuringDelivery() {
        ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
        AtomicReference<String> latest = new AtomicReference<>("Starting");
        List<String> received = new ArrayList<>();
        AtomicReference<AgentSnapshotDelivery> reference = new AtomicReference<>();
        AgentSnapshotDelivery delivery = new AgentSnapshotDelivery(scheduled::add, () -> {
            received.add(latest.get());
            if (received.size() == 1) {
                // A background update races with the ongoing UI delivery.
                latest.set("Open official sign-in"); reference.get().publish();
            }
        });
        reference.set(delivery);
        delivery.publish(); latest.set("Connecting"); delivery.publish();
        if (scheduled.size() != 1) throw new AssertionError("Progress was not coalesced");
        scheduled.remove().run();
        if (scheduled.size() != 1) throw new AssertionError("Update during delivery was lost");
        scheduled.remove().run();
        if (received.size() != 2 || !received.get(0).equals("Connecting")
                || !received.get(1).equals("Open official sign-in") || !scheduled.isEmpty())
            throw new AssertionError("Unexpected deliveries: " + received);
    }

    private static void await(CountDownLatch latch, String failure) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError(failure);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new AssertionError(failure, error);
        }
    }
}
