# PocketAgent 14.3.0 — the last copy of a claim that was not true

Version **14.3.0**, code **460**.

14.0.5 corrected three sentences that told the owner Google had refused PocketAgent permission to
drive Antigravity. Google publish no such restriction; their own headless documentation shows a
script driving `agy` turn by turn and names no limit on who may do it. What is actually missing
is on this side — an adapter for the newline-delimited event stream `agy` speaks instead of the
protocol this build understands.

One copy was missed: the Python installer, which is the one an owner actually reaches when they
tap Install. It still said "permission for this third-party client has not been established". It
now says the same true thing the rest of the app says.

The difference is not cosmetic. Told that permission was refused, an owner waits for Google.
Told the truth, they know the work is ours — and it is the one agent whose free tier would cost
them nothing.

## What this build does and does not run

Three of the four connect: **Codex**, **Cursor** and **Claude Code**. **Antigravity has never
connected in any version of this app**, and the catalog has always listed it with a reason rather
than a route. PocketLinux is where all four run, as their own desktop applications.

44 gates, exit 0.
