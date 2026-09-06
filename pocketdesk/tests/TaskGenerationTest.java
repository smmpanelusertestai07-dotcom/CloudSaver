package com.pocketlinux;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class TaskGenerationTest {
    public static void main(String[] args) throws Exception {
        TaskGeneration owner = new TaskGeneration();
        long stopped = owner.next();
        AtomicInteger state = new AtomicInteger(1);
        CountDownLatch finishOld = new CountDownLatch(1);
        Thread oldWorker = new Thread(() -> {
            try { finishOld.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            owner.runIfCurrent(stopped, () -> state.set(0));
        });
        oldWorker.start();
        owner.next(); // Stop invalidates the previous task.
        long reopened = owner.next();
        owner.runIfCurrent(reopened, () -> state.set(2));
        finishOld.countDown();
        oldWorker.join(2000);
        check(!oldWorker.isAlive(), "old cleanup did not finish");
        check(state.get() == 2, "old cleanup cleared the reopened session");
        check(!owner.runIfCurrent(stopped, () -> { throw new AssertionError("stale status published"); }),
                "superseded generation retained ownership");

        // A check and its write must be one operation: a new acceptance cannot slip between
        // them and then be overwritten by an old task's busy/notification cleanup.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread finishing = new Thread(() -> owner.runIfCurrent(reopened, () -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            state.set(0);
        }));
        finishing.start();
        entered.await();
        Thread accepting = new Thread(() -> {
            synchronized (owner) {
                long next = owner.next();
                owner.runIfCurrent(next, () -> state.set(3));
            }
        });
        accepting.start();
        release.countDown();
        finishing.join(2000);
        accepting.join(2000);
        check(!finishing.isAlive() && !accepting.isAlive(), "generation update deadlocked");
        check(state.get() == 3, "new acceptance was overwritten by concurrent old cleanup");
        System.out.println("PASS TaskGeneration (rapid Stop/Open and atomic finalization)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
