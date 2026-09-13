# PocketAgent Doors 15.2.0 — the addresses and the flags, checked against the real thing

Version **15.2.0**, code **520**, application ID `com.pocketagent.doors`.

This installs beside PocketAgent 14.3.0 rather than over it. The one that works today keeps
working while this one is being proved.

## Two failures on a real phone, and one habit behind both

Door A opened on `Error: Not Found — The requested URL /remote was not found on this server`.
Door B ended in a Node stack trace: `MODULE_NOT_FOUND`, empty require stack, then
`openai.chatgpt could not be installed`.

Neither was a hard problem. Both were things this app asserted without checking.

### Door A: the wrong domain

The dashboard address in 15.1.5 was `https://antigravity.google/remote`. Google's documentation
lives on `antigravity.google`, so a `/remote` path there looked right. Their dashboard is on
**`antigravity.google.com`** — a different domain. The guess cost a whole set-up run.

The address is now written down once, verified, and a check fails the build if any path on
`antigravity.google` is ever used as an interface address again.

### Door A: a flag that does not exist

The fallback in 15.1.5 ran `agy remote-control start --foreground` if the service path failed.
There is no such flag. The published arm64 binary was unpacked and read: `remote-control` takes
`start`, `status` and `stop`, plus `--name`. `--foreground` would have been rejected as unknown.

The fallback is gone. The daemon is started with the documented command, and then **asked**
whether it is running rather than assumed to be — exit zero is not proof when the thing that
usually registers the daemon is a service manager this workspace does not have. If it is not
running, the daemon's own words go on the screen.

### Door A: the dashboard opens in a browser now

Google refuse an OAuth sign-in inside an embedded view, so a dashboard shown in this app's own
window could never be signed in. Their own instruction is to open it in a browser and add it to
the home screen, which is also how their notifications arrive. So Door A ends with a button that
hands the address to the phone's real browser. The key bar is hidden there — there is no page in
this window for it to type into.

15.1.5 also loaded that address the moment the screen opened, before any work had happened. That
is why the 404 appeared so early, and why it said nothing about whether the daemon had started.
Nothing is loaded on the way in any more.

### Door A: it follows Google's own updates

The CLI is no longer pinned to one build. Google publish a manifest naming the current version,
its download and its sha512; this reads the same manifest their installer reads, refuses any
download that is not on Google's own storage host, and verifies the checksum they publish. The
build this app verified by hand stays as a floor for when the manifest cannot be reached.

### Door B: a 220 MB download on a fifteen-minute deadline

code-server's launcher runs `lib/node <root>`, and node resolves that through `package.json`'s
`main`, which is `out/node/entry.js`. `MODULE_NOT_FOUND` with an empty require stack means node
could not find it — the launcher had arrived and the thing it launches had not.

The archive is 220 MB across 6,631 files, and the download was given fifteen minutes. On mobile
data that is not always enough.

Now: free space is checked before any of someone's data is spent; the download resumes instead of
restarting; it has an hour; the unpacked tree is checked for the three files node actually needs,
by name; and the server is asked for its own version before anything trusts it. The digest is
only recorded once all of that passes, so a truncated download can never become the copy every
later check is compared against.

The first failure is also visible now. 15.1.5 ran `--list-extensions` with its errors hidden, so
the real reason was thrown away and only the second, more confusing crash reached the screen.

## The phone stays awake during set-up

Set-up ran on an ordinary thread with no wake lock. Unpacking a base image and configuring a few
hundred packages takes tens of minutes under PRoot, and `dpkg` frozen halfway has to be repaired
before anything else can be installed. The screen is held on and a wake lock is taken for the
length of the install, with a three-hour ceiling, and both are released the moment it ends.

## What is still not proven

Everything above is a fix for something that was observed. None of it is a claim that Door A
works end to end. What Google document is a headless daemon and a headless sign-in; what has
never been seen is either of them running under PRoot on this phone. The app reports which route
the daemon took, and says so plainly when it takes neither.

Door C (Cursor) is still not wired up, and Cursor still publish no headless route.

## Checks

Nineteen now. The five added in this version each fail on the exact mistake that produced one of
the two failures above: an unverified address, an undocumented flag, a publisher's site framed
inside the app, a download trusted before it was complete, and a set-up that could be frozen
halfway.
