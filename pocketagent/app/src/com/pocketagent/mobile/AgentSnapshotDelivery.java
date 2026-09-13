package com.pocketagent.mobile;

import java.util.concurrent.atomic.AtomicBoolean;

/** Coalesces UI delivery without depending on the agent worker being idle. */
final class AgentSnapshotDelivery {
    interface Scheduler { void schedule(Runnable task); }
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Scheduler scheduler;
    private final Runnable delivery;

    AgentSnapshotDelivery(Scheduler scheduler, Runnable delivery) {
        this.scheduler = scheduler;
        this.delivery = delivery;
    }

    void publish() {
        if (!scheduled.compareAndSet(false, true)) return;
        scheduler.schedule(() -> {
            // Release on the delivery thread, before reading the latest snapshot.
            // An installation may occupy the agent worker for several minutes.
            scheduled.set(false);
            delivery.run();
        });
    }
}
