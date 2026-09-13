# PocketAgent Doors 15.3.0 — the editor was running and the app said it had failed

Version **15.3.0**, code **530**, application ID `com.pocketagent.doors`.

This installs beside PocketAgent 14.3.0 rather than over it. The one that works today keeps
working while this one is being proved.

## 15.3.0: four bugs between a working door and a screen that said otherwise

The editor door reached the end. Its own log: extension `openai.chatgpt` v26.908.40401 installed,
`Starting code-server on 127.0.0.1:8391`, `READY http://127.0.0.1:8391/`. The screen said
**Codex could not start here**, offered a sign-in strip for a server that needs no sign-in, and
the browser got `ERR_CONNECTION_REFUSED`. Four separate faults, none of them the door's.

### A guess about a line beat the app's own protocol

Lines from a door are scanned for a link, because a sign-in prints one. That scan ran *before*
the marker checks — and the editor announces itself with `READY http://127.0.0.1:8391/`, which
contains a link. The scan claimed the line, raised the sign-in strip, and skipped the `READY`
that opens the door. The run was then recorded as a failure.

Markers are read first now. A marker is this app's own protocol; a guess about a line never wins
over one.

### Nothing can detach, so nothing may try

proot runs with `--kill-on-exit`: when the process it was given finishes, every process inside
the workspace is killed with it. The editor door started its server in the background and
returned — which ended the script, which ended the session, which killed the server. The app
announced a working editor and by the time anyone looked there was nothing listening.

Both doors now stay for as long as the thing they started is running: the editor waits on its
server, Antigravity watches its daemon and reports the moment it stops. The app's own line about
"running in the background" is gone, because nothing here runs in the background.

### One screen, three starts, one service

`read interrupted by close() on another thread`, over a sign-in that was working.

A normal Antigravity sign-in starts this service three times: on opening, to sign in, and again
when the sign-in finishes. Each start replaced the running process and thread without ending the
one before, and the older run then reached its own clean-up and stopped the service — destroying
the process the *newer* run was reading. Java reported that read being interrupted, and the app
showed it as the door's failure.

Every run now carries the number it was given and touches nothing once a newer one exists.
Starting a door ends the previous one first, deliberately, instead of leaving it to collide.

Stopping a door no longer boots a second workspace to run a "stop" command. `--kill-on-exit` had
already ended everything; the second session was pure cost, and it ran on the main thread.

### Sign-in needs a terminal

Read out of the published binary, in the CLI's own words: *"Launch the CLI without arguments to
sign in"*, and an auth step called *"Selecting sign-in method"*. That is an interactive screen,
and an interactive program handed a pipe either refuses to draw or draws nothing.

Sign-in now runs under `script`, which allocates a real pty, so the CLI behaves the way it does
over SSH. And what it says stays on screen: a conversation was being shown one line at a time in
a status line, so every line replaced the one before — including the question being asked.

## 15.2.5: the Antigravity archive was never unpacked

15.2.0 reached `ERROR: The archive did not contain the Antigravity binary`, and the archive was
fine. It holds exactly one entry — a file called `antigravity` at the root — and the unpack ran
`tar --strip-components=1`, which strips that file's only path component. tar extracted nothing
and exited zero, so the fallback never ran and the check that followed blamed the archive.

Reproduced against the published archive, then fixed: no component is stripped, the binary is
found by name at any depth (Google's own installer takes `antigravity` out of this archive and
installs it as `agy`, so both names are accepted), and it is confirmed executable before anything
claims it is installed. If it is genuinely not there, what the archive *did* contain is printed.

code-server's archive does wrap its files in a directory and still needs that component stripped,
so a check now states both cases, to stop one being "fixed" into the other later.

## 15.2.5: a quarter-gigabyte download that said nothing

Door B got all the way to `Installing extension 'openai.chatgpt'...` and sat there. It was not
stuck — that extension is **231 MB**, Claude Code's is **99 MB**, and Open VSX sends no progress
at all. On top of code-server's own 220 MB, Door B costs roughly **450 MB** of data the first
time, which on a phone plan is a real cost and is nobody's business to hide.

The size is now read from Open VSX and said before the download starts, along with a line saying
there will be no progress and it is not stuck.

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

Twenty-five now. Each one added across 15.2.0, 15.2.5 and 15.3.0 fails on the exact mistake that
produced one of the failures above: an unverified address, an undocumented flag, a publisher's
site framed inside the app, a download trusted before it was complete, a set-up that could be
frozen halfway, an archive unpacked the wrong shape, a long download that said nothing, a guess
about a line beating a marker, a door that returned while its server was meant to be running, a
sign-in run on a pipe, and a question that scrolled away as it arrived.
