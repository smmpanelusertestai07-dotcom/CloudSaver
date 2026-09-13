# PocketAgent 15.6.5 — one workspace, one way into each agent

Version **15.6.5**, code **565**, application ID `com.pocketagent.doors`.

## 15.6.5: the name says what it runs, and "Doors" is gone

PocketAgent 14.3.0 is removed from the repository. It was kept as a fallback while this app was
unproven; 15.6.0 covers the same ground with one workspace instead of a bridge per protocol, so
keeping it was keeping a second answer to a question that has one.

"Doors" goes with it. It named a design that no longer exists — three doors to pick between —
and it stayed in the APK's filename and the documents after the thing it described was deleted.
The app is **PocketAgent**, which is what the launcher has always said. The application ID stays
`com.pocketagent.doors`, so 15.6.0 updates in place and nobody re-downloads a gigabyte of Ubuntu
to rename an app.

The home screen now names the three, under the app's name rather than inside it:

> CLAUDE CODE · CODEX · ANTIGRAVITY

Putting any of those in the app's own name is not an option — each of the three asks that its
mark not become part of somebody else's product name, and an app called after them would read as
official, which this is not. Naming them here is the opposite claim: this is what it runs, said
plainly, so anyone can see what they are getting before spending a gigabyte of mobile data
finding out. A check fails if that line disappears, and another fails if a maker's name ever
turns up in the app's own name.

## 15.6.0: the menu is gone

15.5.0 offered three doors — a remote-control daemon, an extension host, a desktop application —
and let you pick. That was a menu, and a menu is a question you have to answer before you can
start working. Worse, the answer was never really yours: for each maker exactly one route is the
best one their own publishing supports, so offering the others was offering worse choices
politely.

There is one workspace now. code-server runs on the phone and is the screen for all three. Inside
it, each agent is reached the single way its own maker supports best:

| Maker | How it is reached here | Where else the same session shows up |
|---|---|---|
| Anthropic | their VS Code extension, Remote Control on at startup | the Claude app on this phone |
| OpenAI | their VS Code extension | nowhere — their Remote Control hosts on macOS only |
| Google | their CLI in the workspace's terminal, Remote Control beside it | antigravity.google.com in the browser |

The second column is not a choice to make. It is the same session seen from somewhere else, and
it is switched on without anybody touching a setting. Where a maker publishes nothing for a
phone, nothing is offered and none is pretended.

Three scripts became one: `doors-codeserver.sh`, `doors-claude.sh` and `doors-antigravity.sh` are
deleted, and `doors-workspace.sh` does the work. Every fix they earned the hard way came with
them — the resumable download with an hour to finish in, the free-space check, the tree verified
by name before the server is trusted, the digest recorded only after the unpacked copy proves it
runs, `--strip-components=1` for code-server and never for Google's single-file archive, the pty
for sign-in, and the settings that size a desktop editor for a 720-pixel screen.

Two checks now hold the shape: **OneWayIn** fails if any agent gets a second route, a surface of
its own, or if a second startable script appears in assets. **PhoneSurface** fails if an agent
claims somewhere its maker does not publish.

## 15.6.0: three corrections, from the makers' own pages

Re-reading the primary sources before shipping found three things wrong in 15.5.0.

**The version floor was too low.** Remote Control arrived in Claude Code 2.1.52, which is what
this app checked for. But the one thing this whole design rests on — `remoteControlAtStartup`
honoured so the phone app sees the session without anybody opening a menu — is documented as
needing **2.1.203 or later**. An older build would have read the settings file, ignored the key,
and left the owner told their session was on their phone when it was not. The floor is 2.1.203.

**Four variables switch Remote Control off silently.** Anthropic's own requirements page names
`DISABLE_TELEMETRY`, `DO_NOT_TRACK`, `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC` and
`DISABLE_GROWTHBOOK`: each disables the feature-flag evaluation Remote Control depends on, so the
session starts, works, and simply never appears. A fifth, `ANTHROPIC_BASE_URL`, gets it refused
outright. Nothing in this app sets any of them, but a workspace is a real Ubuntu and anything
could, so they are cleared before the workspace starts and a check fails if that ever stops
happening.

**Google's CLI wants a keyring this workspace does not have.** Their own install guide says the
CLI reaches for the operating system's secure store — on Linux, the Secret Service over dbus. A
workspace with no desktop session has neither, so a sign-in may not survive. That is now said on
the Antigravity card and again in the script, before it happens, because being asked to sign in
again with no explanation is worse than being warned.

The plan line for Claude is also corrected to Anthropic's exact list: **Pro, Max, Team or
Enterprise**, and API keys are not supported.

## 15.6.0: the fourth gate that passed on the wrong thing

A check meant to prove Codex claims no phone surface looked for an empty string inside the Codex
entry. The entry has two empty strings in a row — the surface, and the sentence describing it —
so filling the surface in left the other one there and the check stayed green while the app
claimed OpenAI publish something they do not.

The fix is `tests/agents.py`, which reads the catalog by field position the way the constructor
does, so a gate can ask about a named field instead of hoping a pattern lands on it. Every new
check in this release was then broken on purpose and confirmed to fail before being trusted.

---

# Earlier releases

## 15.5.0: scoped to three, and it says why

Cursor, xAI's Grok Build and Meta's Muse Code are out. Google, OpenAI and Anthropic are in. That
is not a ranking of models — it is the list of makers who publish all three things this app needs:
an agent built for linux-arm64, an interface of their own that a phone can reach, and an update
path they control.

Deleting the others quietly would have been the easy version. Instead there is a **Why these
three** screen, reachable from the home screen, that names each maker's compute and reach, states
the bar, and says exactly what is missing for each one left out — Cursor has no Open VSX extension
and its remote runs on Cursor's own machines; Grok Build has no official interface on any platform;
Muse Code is terminal only, with no free tier. Every figure on that screen names its source, and
the screen ends by saying plainly that none of it judges anyone's models.

A check enforces that: an agent may not be removed from the catalog unless the reason for removing
it is written down. The check it replaced only said "Cursor must not claim a headless route", which
passed by having nothing left to look at once Cursor was gone — the weakest kind of green.

## 15.5.0: Claude Code through Anthropic's own app

Claude Code moves from the editor extension to Anthropic's Remote Control. The session runs in this
workspace, inside the phone, and the screen is Anthropic's own mobile app — an interface they built
for a phone, which is further than any editor layout will ever get.

Two things made it possible, and both were checked rather than assumed. Remote Control is confirmed
working on a headless Linux host, which is what this workspace is. And it refuses a bare pipe — it
needs a real terminal, which this app already knows how to give it, because Antigravity's sign-in
needed the same thing.

The door asks the CLI whether it is signed in rather than guessing, refuses to start on a build
older than the one that introduced Remote Control, and holds the session open for as long as it
runs, because nothing here can detach.

Two of the three agents now show the publisher's own phone app. OpenAI is the exception: their
Remote Control needs a Mac to host the session — Windows is listed as coming, Linux is not listed —
so Codex stays on their own VS Code extension.

## 15.4.0: this app said Codex was free, and it is not

Door B's card read **"Every ChatGPT plan, Free included"**. That was this app's claim, not
OpenAI's. Theirs is printed on the extension itself:

> Codex is a coding agent that works with you everywhere you code — included in ChatGPT Plus,
> Pro, Business, Edu, and Enterprise plans.

**Free is not on that list.** Someone on a free account would have spent about 450 MB of mobile
data installing a door they could not open, on the strength of a sentence nobody at OpenAI wrote.

The card now names the plans its publisher names, and a check fails the build if this app ever
describes Codex as free again, or names a different set of plans.

The same card also now says what this is: OpenAI's own VS Code extension — the same one their
desktop editor runs, driving the same Codex engine underneath — and **not** the separate Codex
application for macOS, which is a different product.

## 15.3.5: Codex opened

Door B works. code-server is running on the phone with OpenAI's own extension inside it, and what
fills the window — "Build with Agent", the composer, the Codex sidebar — is their interface, not
one this app drew.

Two things were wrong with it, and neither was the door's.

### The Sign in button did nothing

The extension opens its account page in a **new window**. A WebView that is not told it may open
windows discards that request silently: no error, no page, nothing. The button was dead, and an
agent nobody can sign into is an agent nobody can use.

The window is answered now and handed to the phone's real browser, which is where a sign-in can
actually be completed and remembered. Read out of the published extension: it has no sign-in
command of its own and its whole interface is a webview driving the Codex CLI
(`chatgpt.cliExecutable`), so the account page is the only way in and it has to reach a browser.

### A desktop editor at desktop size

At its own default size the editor showed about a third of itself, with the rest off the
right-hand edge. Zooming the page out would have blurred every glyph; asking the editor to draw
smaller does not. It now starts with a zoom level, word wrap, no minimap and the activity bar
along the top — a vertical strip of icons costs width a 720-pixel screen cannot spare.

Written once. Anything changed afterwards in the editor's own Settings is the owner's and is
never overwritten by an update.

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

Thirty-one now. Each one added across 15.2.0, 15.2.5 and 15.3.0 fails on the exact mistake that
produced one of the failures above: an unverified address, an undocumented flag, a publisher's
site framed inside the app, a download trusted before it was complete, a set-up that could be
frozen halfway, an archive unpacked the wrong shape, a long download that said nothing, a guess
about a line beating a marker, a door that returned while its server was meant to be running, a
sign-in run on a pipe, a question that scrolled away as it arrived, a sign-in window refused
before anyone could see it, a desktop editor left at desktop size on a phone, and a price this
app invented for somebody else's product.
