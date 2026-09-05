package com.pocketdesk;

/** Serializes ownership changes with state updates from an asynchronous task. */
final class TaskGeneration {
    private long generation;

    synchronized long next() { return ++generation; }
    synchronized boolean isCurrent(long token) { return token == generation; }

    synchronized boolean runIfCurrent(long token, Runnable update) {
        if (token != generation) return false;
        update.run();
        return true;
    }
}
