# PocketAgent 14.2.5

PocketAgent is a native Android interface for official coding agents running in an Ubuntu
userspace on the phone. Beta.12 updates the name, package and icon to PocketAgent. Beta.11 added shared-project agent switching and artifact downloads. Beta.10 added inline dictation, composer usage, response actions and public tool-activity cards. Mode, model and reasoning effort stay in the composer; the partial-width side
panel shows conversations directly. The supplied Claude mobile screenshots guide the layout,
while PocketAgent retains its own identity and Android typography.
The release includes an APK and source archive. It remains a development beta: it is not an official provider app, production certification or complete desktop clone.

## Use the APK

1. Install on an **ARM64 Android 10+** phone. Package `com.pocketagent.mobile` installs separately from PocketDesk (`com.pocketdesk.mobile`) and PocketLinux (`com.pocketlinux`). Android does not transfer their private projects, chats, settings or accounts. Export source from the old app before removing it, import projects through PocketAgent’s file picker and connect accounts again. Future PocketAgent updates require the same package and signing identity to retain its data.
2. Open PocketAgent and choose **Set up workspace**. Ubuntu and development tools download during setup; keep a stable connection, several GB free and sufficient battery. The APK does not contain offline Ubuntu or signed-in accounts.
3. Create a project or clone a public HTTPS repository. **Project tools** can also import source ZIPs into a new project. For private GitHub repositories, use the separate **Project tools → GitHub** account connection described below; ChatGPT app access does not sign Git in.
4. Open **Agents** in the side panel (or **Model → Switch agent**) and choose an agent, then tap **Connect**. PocketAgent installs its pinned official Linux ARM64 release on first use. Follow the engine's official sign-in in the browser, then return. After a successful connection in beta.8 or newer, the app can reconnect that installed provider/project when visible and unlocked using the CLI's saved account; explicit Disconnect or Sign out stops this behavior for that provider. Each provider/project retains its own successful connection record; switching does not sign the other agents out.
5. Type a prompt and tap the **up-arrow** to send. Mode, model and effort each appear once in the composer; tap effort to open the advertised-effort slider. The **usage value in the composer** opens the limits the provider reports. Review tool approvals in Chat and use the **stop icon** to cancel a task.
6. **+** opens Photos & files, **@ Project file**, **$ Skills & apps** and **/ Commands**. Typing `/`, `@` or `$` opens the corresponding supported picker. The **microphone left of Voice/Send** records inside the composer; hold it to choose a dictation language. It never sends automatically. With connected Codex and an empty composer, the final button shows the **Voice waveform**. Any draft text or attached context changes it to the **send arrow**. Voice availability still depends on the engine and account.
7. Open the **side panel** for conversations and search in the selected project. Saved chats remain listed while disconnected; connected Codex can load its actual history. Toggle **Recent/Archived** to view archived chats; **Load earlier chats** continues the list in place. Each chat has its own **⋮** sheet for rename, archive/restore, deletion and sharing without opening another chat-management page. **⋮ → Background tasks** shows the current Codex tasks. **Account & settings → Saved history** retains the separate local transcript viewer.
8. Open **Apps & tools** from the side panel for Codex Apps, MCP and Skills. Follow explicit permission/sign-in prompts; visible, unlocked screens update their reported state automatically. **Use in chat** attaches an app or skill to the next prompt without sending it.
9. **⋮** retains Files, Changes, Preview and Environment. Files gives access to project editing and **Project tools** for Git/source import/export. **Account & settings → Appearance** changes System/Dark/Light. Environment manages Codex variable names and explicitly entered values.
10. **Project tools** checks GitHub setup automatically. Explicitly **Install GitHub CLI** if needed, then **Connect GitHub account**. Copy its one-time code, tap **Continue at github.com**, finish GitHub's official device flow and return. Reported account status updates while this screen is active; private repositories still require permission.
11. Open **⋮ → Workspace** for actual Activity, Changes and Preview on this phone. **Account & settings → Privacy & notifications** opens provider pages/guides and controls local completion, approval and error alerts.

The built-in actions do not require typing terminal commands. Unusual projects may still need
the agent to configure compatible tools, and an Android kernel cannot run every Linux or
mobile build tool merely because Ubuntu is installed.

## Implemented scope

| Area | Implementation | Important limit |
| --- | --- | --- |
| Interface | Native Android views, warm System/Dark/Light themes, slim header, partial-width side panel and one compact composer; additional controls stay in menus | Real-phone layout, keyboard, accessibility and performance testing remain necessary; PocketAgent keeps its own identity |
| Workspace | Checksummed Ubuntu ARM64 base, signed Ubuntu packages, verified official Node 24 download, Git, Python and compiler tools | PRoot shares Android's kernel; no Docker/KVM, Windows runtime or hardware VM |
| Codex | Pinned official `0.154.0` App Server stdio, ChatGPT sign-in, streamed threads, approvals, models and account rate limits | Eligible plan and engine-exposed capabilities only; Linux sandbox compatibility varies on phones |
| Codex modes | Ask approvals, Auto edits and advertised built-in Plan collaboration mode | Plan requires reported Plan/default metadata and a known model; it does not itself isolate code or guarantee every edit prompts |
| Reasoning effort | Native slider over the selected model's advertised choices, readable official names/descriptions and Auto/default; explicit Apply | No extra highest level is invented; `xhigh` is shown as Extra high, and missing choices remain unavailable |
| Usage and credits | Live reported quota bars, reset times, credit balance and earned reset credits; confirmed consume/retry action | Missing data remains unavailable; token counts are separate from quota; a clock reaching reset time does not refill a local counter |
| Native actions | `/` lists supported official actions and enabled skills; `@` chooses project files; `$` resolves enabled skills and callable apps against the current catalog | Unsupported slash actions and ambiguous explicit references keep the draft; unknown `$` text remains ordinary prompt text; no arbitrary slash-to-shell execution or claim of every desktop command |
| Conversations | Local Codex project-history search, resume, rename, archive/restore, confirmed deletion and reviewed text sharing | Local engine history is not a cloud-account backup; account ownership of individual history entries is not reported; exports may be bounded excerpts |
| Background tasks | Actual current-conversation commands reported by Codex, with explicit stop controls | No official scheduler; Android/OEM memory and battery policies can terminate local work |
| Chat recovery | Resumes the saved engine thread; fills an empty display cache from returned history; confirmed missing conversations recover once with an archived local transcript | No automatic prompt replay; ordinary auth/connection failures do not reset history; local transcripts are not a cloud backup |
| Images and files | Android picker copies selected files into the private project; up to four validated image inputs for image-capable Codex models; project-path attachments | No broad photo/storage permission, public-folder mount or invented video-input support; size and project-path limits apply |
| Media | Automatic visible image previews, attachment thumbnails with remove buttons, full preview on tap, audio/video Play, Save and Share | Remote image downloads require validated public HTTPS; format/codec/size limits apply; ordinary web pages/video do not auto-download |
| Voice | Official Codex thread realtime signaling with native Start/Mute/End, returned captions and WebRTC audio using the connected ChatGPT account | Requires experimental protocol/account access and a compatible Android System WebView; no API-key fallback or successful phone-call claim |
| Voice typing | Inline on-device capture with waveform, partial transcript, Stop/Cancel and safe cursor insertion | Android 12+, supported installed recognition/language and microphone permission; no external activity or silent online fallback |
| Read aloud | Response speaker opens Auto/English/Hindi device voice selection and playback | Requires installed offline TTS voice; pronunciation selection does not translate; separate from Codex Voice |
| Response actions | Relative timestamps, Copy text/Markdown, native formatted prose and per-code Copy/Save | Unknown historical times stay absent; code export uses Android destination picker; raw HTML is literal |
| Agent activity | Expandable public reasoning summaries and real web/search/command/file/MCP progress | No raw or encrypted reasoning; details only appear when the provider supplies them |
| Codex Apps | Native catalog search, enable/disable, official returned connection URLs, typed app attachments and reported account-runtime readiness | Enabled metadata/browser return is not readiness; current chats may need fresh tools; GitHub availability depends on catalog and policies |
| MCP | Add remote HTTPS servers, inspect local/remote configuration, OAuth browser handoff, toggle/reload/status and explicit URL/form consent | No native arbitrary local-process installer; server tools can send permitted project data to their service |
| Skills | List/toggle, project skill creation and typed Use in chat attachment | Instructions can influence the agent; selection does not send a task or overwrite an existing skill |
| Plugins | Opt-in Preview catalog/details and policy-gated install/uninstall; incomplete capability details block installation | Official management methods are under development and excluded from production clients; installation does not authorize bundled apps |
| Cursor | Official ACP process, official login, prompts, model options and permission cards | Unsupported quota remains unavailable; account/device validation is required |
| Claude Code | Unmodified official CLI with its own sign-in and streaming protocol | Account/device validation required; no third-party Claude.ai OAuth token proxy or Agent SDK subscription-token reuse |
| Antigravity | Provider entry explains availability | Connection disabled because permission for this separate client has not been established under published terms |
| Projects and editor | Create/select, HTTPS clone, source ZIP import, file rename/delete and UTF-8 editing up to 512 KB with atomic save checks | No complete language server, extension marketplace or native debugger; imports do not replace existing projects |
| Git | Real status/diff, initialize, set HTTPS origin, commit all local changes, clean fast-forward pull and non-force push with explicit review | Private clone/push requires repository permission; other HTTPS hosts need their own Git credentials |
| GitHub account | Native signed-Ubuntu `gh` installation, official device-code browser login, verified account status, exact github.com Git helper and local sign-out | Separate from ChatGPT's GitHub app; CLI credentials may use a plaintext file inside the private Ubuntu profile; browser/device/private-repo workflows remain untested on a real phone |
| Environment | Name-only readback and masked value form with explicit save confirmation; unsent project-inspection helper draft | Values are profile-wide on this phone, readable by commands and not echoed in status; reconnect/new chat for updated config; no automatic setup script |
| Live workspace | Real engine activity, matching-thread tasks, read-only current files/Git changes and interactive local preview | The runtime is Ubuntu on this phone, not a Cursor-style cloud VM, remote screen recording or independently provisioned computer |
| Preview | Token-protected static server, optional trusted npm dev script and hardened loopback WebView; existing desktop link only if already running | Not an Android/iOS emulator; no automatic desktop launch; npm scripts execute trusted project code |
| Privacy and notifications | Fixed official provider account/privacy links or labeled guides; local Task completed/Needs approval/Agent errors notification preferences | Browser pages own provider settings; the app does not display a fake training Off toggle; Android permissions/channels and authoritative engine events control alerts |
| Export/share | Source ZIP, selected file copies and reviewed conversation text through Android picker/share flows | No official public-chat URL; source filtering excludes conventional secrets but is not a complete secret scanner |

The composer recognizes references outside code spans/blocks and resolves them against the
current project/catalog before building typed prompt inputs. This build supports one explicit
skill and one explicit app attachment per prompt; additional ambiguous references require
correction instead of silently dropping a selected reference. `/reasoning` and `/project` are
available native routes alongside the existing supported commands. `$` can refer to an enabled
skill or callable app; it is not a shell-expansion interface. The app only exposes actions it
can map to its installed official engine and native controls. The tool pickers update from
current inventory reads; enabled skills also appear dynamically as `/skill-name` choices.
Unknown `$` literals stay ordinary text, and non-Codex prompts retain their provider text.
Picker and asynchronous file results are checked against the same provider/project/thread/account
context before being added.

Apps, MCP, Skills and Plugins catalogs are intentionally bounded. A truncation note indicates
that the visible list is incomplete; refresh/search does not promise a full account inventory.
Plugin review shows the actual returned capability metadata, not a claim that every bundled
script or hook source was inspected.

## API mapping and availability

| Feature | Implemented route | Availability boundary |
| --- | --- | --- |
| Models, effort and Plan | `model/list`, `collaborationMode/list`, `turn/start` | Only advertised models/effort/modes; plan and organization policies still apply |
| Quota and reset credits | `account/rateLimits/read`, `account/rateLimits/updated`, `account/rateLimitResetCredit/consume` | Fresh verified account data and account-bound confirmation; persisted account-scoped idempotency key for retries |
| Voice | `thread/realtime/start` / `stop`, WebRTC V1 SDP and realtime events | Installed `experimentalApi` capability, eligible ChatGPT account/policy and working System WebView; no separate API-key billing fallback |
| Tools and history | Official Apps/MCP/Skills/Plugin and thread APIs already integrated | Returned catalogs are bounded; plugin management remains Preview/under development |
| Live workspace | Agent events, `thread/backgroundTerminals/list`, local files/Git and PreviewService | Actual current phone/project/thread state; no cloud VM or simulated desktop feed |
| Privacy and alerts | Official browser routes; Android notification preferences and authoritative engine event IDs | Provider settings are not edited locally; alert delivery depends on device policy and reported events |
| Future desktop features | No invented RPC or self-patching code | Cloud workspaces, scheduler, Appshots and official public-chat URLs are not implemented; new protocols require a tested app update |

Metadata updates combine engine events, screen lifecycle signals and bounded polling. Eligible
visible Codex clients normally check usage about once a minute, account state every five minutes
and models every ten minutes; resume, account changes and reported reset times can request a
fresh check. Polling pauses during authentication or conflicting work and backs off on errors.
A displayed timestamp or stale/error state describes the latest response, not an instant-sync
guarantee. Git and GitHub status use separate bounded checks while their screen is active.
Verified-account Apps, installed apps, MCP and Skills inventories update on eligible visible
clients on a bounded 30-second cadence, with a 60-second error retry. Actual app notifications
can update callability sooner. These are reads; they do not opt into plugins or write provider
configuration, and a connected-app catalog is not proof of repository or tool permission.
Newly advertised catalog entries can appear automatically; unsupported future protocol changes
cannot be repaired by a catalog refresh. See [Live workspace implementation and limits](LIVE-WORKSPACE.md)
for the local activity/preview sources and comparison with remote desktop services.

## Account and execution boundaries

Subscriptions remain separate. A ChatGPT plan does not grant Claude, Cursor or Google
entitlements; a free consumer chat tier does not necessarily include its coding CLI.
PocketAgent does not invent allowances, share accounts, extract another app's tokens or
silently switch to API-key billing.

A successful connection in beta.8 or newer records the selected provider, project and access
mode for foreground reconnection. Beta.9 also handles unexpected Codex process exits and
transient initialization failures with at most three retries, after 1, 4 and 15 seconds,
while an unlocked foreground client remains eligible. Two stable minutes reset that retry
budget. Recovery reuses the verified installed CLI, saved account, project, chat and selected
access/model/effort/mode. It never replays a prompt, approval, credit request or browser login.

It does not install software, expand workspace access or revive an explicitly disconnected
or signed-out connection automatically. An authoritative account rejection or null account
stops saved-login reuse. Beta.7 preferences alone do not authorize reconnection; a successful
connection in beta.8 or newer establishes the saved choice. Expired or revoked credentials
still require explicit official sign-in; this is not a perpetual-login guarantee.

Choosing a saved Codex chat while disconnected can reconnect its installed provider using
saved authentication, then verify the official thread and project before opening it. A
missing manually selected chat is reported; it is not silently replaced with a new chat.

Official engines store credentials in PocketAgent's private Ubuntu filesystem. **Disconnect**
stops the engine but does not sign it out. Codex's explicit **Sign out** stops any active task
or login and uses official local logout: App Server when idle and connected, or the CLI after
stopping a disconnected/interrupted process. Failed or timed-out logout is not shown as success.
Project files and local history remain. It does not sign out ChatGPT in the browser, revoke
separate GitHub connections or delete other providers' credentials. Clearing Android app
storage or uninstalling removes accounts and workspaces; export source first.

GitHub in **Project tools** is a separate Git account connection, using official `gh` installed
from Ubuntu's signed package repository after explicit confirmation. The app displays
the returned device code and opens GitHub's fixed device-sign-in page. Authentication status
and a real user-identity request must succeed before reporting a verified account; browser
return is insufficient. Native Git applies the official helper only to exact github.com
connections, while GitHub still checks repository access for each network operation.

`gh` owns its local credential store. If Ubuntu has no usable system credential store, the CLI
can save credentials in a plaintext configuration file inside PocketAgent's private filesystem;
this is not an encrypted-vault claim. The UI does not display tokens. **Sign out of GitHub
locally** removes the chosen CLI account and checks whether another saved account remains.
It keeps projects and does not revoke the provider's OAuth tokens globally. ChatGPT's GitHub
app connection and Codex logout remain separate. See the official
[login](https://cli.github.com/manual/gh_auth_login),
[Git setup](https://cli.github.com/manual/gh_auth_setup-git) and
[logout](https://cli.github.com/manual/gh_auth_logout) descriptions.

Chat drafts and saved transcript copies are local app-private files. The native conversation
cache is a bounded recent window (80 messages/160,000 serialized characters, with bounded
individual message text); it is not an unlimited transcript backup. The official CLI's own
local thread history remains the source for resumed Codex conversations. The sidebar index
stores chat titles/metadata, not another complete copy of every message or credential. Each
returned history page is saved before the engine's bounded list window advances. Observed
account/provider/project scopes keep cached lists separate; offline lists are saved local
copies, not proof of an active account session or original thread ownership. Confirmed
rename/archive/delete operations update cached metadata only after the official operation
succeeds; refreshing an older page cannot undo a newer confirmed change. Deleting an engine
chat does not delete independent local transcript copies or imported project files.

Public phone folders are not mounted. File access uses individually chosen Android picker
items copied into private project storage; deliberate Save/Share actions expose only the
selected exported content. Legacy broad storage permission and public-folder bind controls
have been removed. This does not make Ubuntu a hardware-isolated sandbox: broader Ubuntu
access may expose other PocketAgent projects and saved engine credentials inside this app.
PRoot is not a network firewall. Unsupported approvals fail explicitly instead of being
silently accepted.

Apps and MCP require separate authorization. Review the browser destination and requested
permissions. Directory visibility, enabled configuration and browser return do not establish
usable tools; refresh checks the reported account runtime, and an existing chat may need
reconnection or a fresh conversation. Server-requested URL/forms require explicit decisions.
A connected service may receive project or account data within the access granted to its tools.

Chat media distinguishes actual engine image output, project files and remote URLs. Validated
cached engine images render directly. Visible remote image cards automatically load validated
image URLs; ordinary web-page/video links do not auto-download. Loaded media is saved in app-private storage,
without cookies, account tokens or referrers. HTTPS/DNS validation rejects private, local,
numeric and credentialed targets and checks redirects before connecting. Source-generated
images still require an actual model/tool output; a plain promise or web-page link does not
produce an image automatically.

Selected phone files are copied into the project's private `.pocketagent/attachments` directory.
Imports retain their existing limits: 100 MiB per file, 512 MiB per project and 200 files.
Saved agent images and loaded remote media use `filesDir/desk-media`; they survive
app restarts and Android cache clearing, do not expire after 24 hours and are not automatically
evicted. Saved media has a 1 GiB/3,000-record bound and preserves 32 MiB of free storage.
Remote files are bounded at 100 MiB and native image decoding at 20 MiB/40 megapixels.
Inline encoded images fit within the transport bound, approximately 12 MiB decoded.
Only temporary copies prepared for Android Share keep the 300 MiB/240-entry, 24-hour expiry.
A previously loaded remote URL can reuse its saved copy without another network request.
Older beta.8 cache files are migrated when still present; already removed files cannot be
recovered locally. Android clear app data or uninstall still deletes private files.

The old 2 MB incoming protocol-line limit was a
PocketAgent transport restriction, not a Codex plan or uploaded-file quota. Incoming frames
now spool beyond 256 KiB into temporary app-private files and have a 16 MiB safety bound.
A frame above that bound is drained without deliberately disconnecting the engine; larger
outputs should be delivered as project files. Native image zoom, audio/video
Play, Save copy and read-only Share snapshots are available for compatible media. A bounded
static-shape SVG subset can preview simple logos: a strict parser constructs fresh normalized
markup for a sealed, JavaScript-disabled WebView with network, file, content and navigation
access blocked. The preview accepts at most 512 KB, 1,024 nodes and a depth of 24; scripts,
external resources and unsupported elements/attributes are rejected. This is not a general SVG
or raw-web-content renderer. Unsupported SVG and other formats remain available to Save/Share.
Real host/TLS, System WebView, Android codec and playback behavior still require device testing.

**Voice typing** is separate from the agent's Voice conversation. The composer uses Android's
explicit on-device recognizer on Android 12+ with an installed supporting service and language.
Microphone permission and a deliberate visible tap are required. If unavailable, the app
suggests the keyboard microphone instead of opening another page or silently using a network
recognizer. The waveform reflects captured audio levels. Final text enters only the same
unchanged draft; interrupted or edited drafts require explicit review. Capture stops on app
pause, lock, scope/focus change or Cancel, with a 60-second capture and 8-second result bound.
No audio file is saved. Accepted dictation follows ordinary draft storage rules.

**Read aloud** uses installed offline Android TTS voices and safe sequential text chunks.
Auto, English and Hindi choose pronunciation; they do not translate the response. Code fences
are skipped when reading prose. This does not use a provider API or consume a claimed Codex
speech entitlement. Stop, backgrounding and chat/account change cancel speech. Device voice
and recognition availability must be tested on the actual phone.

Voice sends microphone audio through the official Codex realtime service after explicit Start
and consent. The engine retains account authentication; the local WebRTC page does not receive
account tokens or load third-party scripts. PocketAgent does not save an audio recording. Leaving
Voice, locking the app or an audio interruption stops its local audio. A coding task already
accepted by the engine may continue in Chat, where tool approvals still require a decision.
PocketAgent requests the required experimental protocol capability; this does not establish
account rollout or managed-policy access. There is no user-facing realtime feature toggle
to enable. Account/policy/protocol rejection remains unavailable until a deliberate reconnect/retry; it
does not switch the user to API-key billing. Voice and Android Dictate text are separate features.

OpenAI documents a separate, plan-dependent Voice allowance. Coding tasks started through Voice
still use the Codex task budget, and eligible credit-based workspaces can consume credits for
Voice. PocketAgent does not invent Voice minutes or infer unlimited coding from Voice access.
See [OpenAI's Voice usage rules](https://learn.chatgpt.com/docs/pricing#chatgpt-voice-in-desktop).

Provider privacy rows open actual official account destinations or clearly labeled guides.
PocketAgent does not read or change provider training preferences and cannot claim training is
Off. Local notification categories are Task completed, Needs approval and Agent errors, enabled
by default; Android permission and channel settings still control delivery. Event cards use
generic private text and deduplicated authoritative IDs, not prompt contents or approval-action
shortcuts. Turning off a category clears its event cards; foreground-service notices remain.
See [provider controls and reviewed destinations](docs/ACCOUNT-SETTINGS-LINKS.md).

Earned reset credits are separate from a displayed credits balance. **Use reset credit** requires
fresh account information and confirmation bound to that account, then asks Codex to consume an available
credit. Retries reuse an account-scoped idempotency key instead of consuming another credit
speculatively. Only the provider's outcome and refreshed rate limits update the display; reset
clocks and local counters do not grant quota or entitlement. **Manage credits** opens the
provider's official account route and does not purchase anything automatically.

Read project `.agents/skills/<name>/SKILL.md` instructions before enabling them. Plugin review must
have complete returned capability details before install, but neither skills nor plugins
establish a trustworthy source or grant extra subscription entitlement. Plugins remains
Preview while the official management API is under development.

Environment values are stored in the phone's Codex configuration, not a remote vault. The UI
shows names only after saving, masks entry and explains that commands can read or transmit
values. Updates apply to future agent configuration and may affect other projects. Do not
assume the Plan mode or environment form is a security boundary.

Static preview rejects traversal, symlinks, dotfiles and common credential files and uses a
random URL token. An npm development server executes trusted project code with different
protections. Loopback is local to the device, not exclusive to PocketAgent.

## Build and validation

This project uses plain Java and the Android SDK without a Gradle dependency download during
compilation. Set `ANDROID_SDK_ROOT` to an SDK containing `platforms/android-35` and
`build-tools/35.0.0`, then run `bash build.sh` here. Version **14.2.5**, code **455**,
target API 35, minimum API 29, ARM64. Native library ZIP placement is aligned for 16 KB pages.
Keep the signing key private and stable for updates. Shared source archives must exclude
`.signing/`, accounts and runtime workspace data. Fresh source builds generate a local key
and remain labelled `-devkey`; set `POCKETAGENT_KEYSTORE`, `POCKETAGENT_STORE_PASS`,
`POCKETAGENT_KEY_PASS` and optionally `POCKETAGENT_KEY_ALIAS` for your own stable release
identity. The delivered build retains its existing certificate subject as historical provenance.
See [README.md](README.md) for the full signing and new-package installation details.

The aggregate host regression runner requires a completed `build.sh` build, the same
`ANDROID_SDK_ROOT`, and `POCKETAGENT_JSON_JAR` pointing to checksum-verified JSON-Java
20250517. This is a host test dependency, not a new APK dependency. The README includes the
pinned download and commands. CI builds first, then runs the suites; a failure prevents
artifact upload. Missing prerequisites fail instead of silently skipping native fixtures.

The new Codex controls map to the pinned **0.154.0** experimental schema. Its inspected
159-method RPC catalog did not expose an official scheduler, Appshots or public-chat-sharing
API. The beta does not invent those APIs or imply that this catalog establishes every feature
of every current Codex product. Plugin management is explicitly excluded from production use
by the official App Server documentation.

Beta.10 host checks cover 19 inline-draft cases, 41 Markdown/code cases, 158 speech chunking
checks, 20 relative-time cases, 76 remote-media checks, 24 session-sheet receipts, 35 activity
and 10 usage-warning cases, 69 Codex controls, 17 logout checks and eight official-schema
fixtures. The combined Java sources compile against Android 35. Device speech, account and
visual behavior remain unverified by these host checks.

Beta.9 host checks include 43 transport assertions under a 128 MiB heap, including a 4 MiB
image packet followed by a valid RPC, a drained 64 MiB generated frame, strict UTF-8,
cancellation and shutdown cleanup. Media checks cover 81 private/saved-media and 62 remote-media
assertions. Reconnection checks cover 40 policy cases, with 16 engine-media and 6 Claude login
guard cases. Control checks include 68 Codex control and 17 logout assertions, plus eight
payloads validated against the pinned official schema, including image-only turns. Relevant
helper/media classes compile against Android 35. Session controls have 79 assertions and
local session indexing 17. These are host checks;
real phone/account recovery, storage and playback are not established by them.

Beta.8 host checks include 117 composer token/catalog assertions, 19 voice-typing policy
assertions, 62 remote-media, 60 workspace-media and 45 static-SVG assertions, 19 reconnect cases, 16 engine
media cases and 6 Claude automatic-login guard cases. Integration-controller checks (97),
protocol (106), recovery (34), history (18), effort (421) and delivery-race checks also passed.
The style classes compile against Android 35; parsed theme resources and sampled muted-text
contrast are checked. These host checks do not exercise an Android microphone, a signed-in
provider, remote media playback or the final phone layout.

Host regression suites cover protocol/control validation, project path handling, storage,
exports and other deterministic behavior. Isolated Linux x64 smoke checks of Codex 0.154.0
and Claude Code 2.1.269 examined initialization, unauthenticated account state and response
shapes without starting user OAuth or a model turn. These are not ARM64 Android execution
tests. Resource compilation, APK signature/manifest verification and static UI review are
build evidence, not proof of working phone login, live plan/credit usage, realtime voice,
notification delivery, image turns or media playback.

Actual device validation still needs clean install and upgrade, interrupted setup, real
provider login, model/mode/effort changes, approvals/cancellation, backgrounding/low memory,
rotation/app lock, keyboard/font sizes/themes, picker imports, media playback, conversation
resume/archive, environment refresh, source/Git workflows, official GitHub device-code login,
private clone/push, live usage/reset-credit outcomes, Voice microphone/transport interruptions,
notification/browser preferences and Apps/MCP/Skills connections.
No physical Android device or signed-in user account was available in the build environment.
This beta makes no 100% security, performance, production-readiness or desktop-parity claim.

Android SDK/NDK, JDK and mobile packaging workflows are not preinstalled in the phone workspace.
Arbitrary Android builds need compatible ARM64 build-tool binaries and are not guaranteed by
Ubuntu. iOS builds require Apple's supported toolchain. Large projects depend on the phone's
RAM, storage, battery and thermal limits.

## Primary references reviewed

- [Codex App Server](https://learn.chatgpt.com/docs/app-server): official integration, account flow, requests, notifications and plugin management limits.
- [Pinned Codex realtime implementation](https://github.com/openai/codex/blob/rust-v0.154.0/codex-rs/core/src/realtime_conversation.rs) and [model-client authentication](https://github.com/openai/codex/blob/rust-v0.154.0/codex-rs/core/src/client.rs): WebRTC routing and engine-owned credentials, without an account-eligibility guarantee.
- [MCP for Codex](https://learn.chatgpt.com/docs/extend/mcp?surface=cli): server configuration and OAuth.
- [Build skills](https://learn.chatgpt.com/docs/build-skills) and [Plugins](https://learn.chatgpt.com/docs/plugins): extension concepts and configuration.
- [Codex app commands](https://learn.chatgpt.com/docs/reference/slash-commands) and [CLI commands](https://learn.chatgpt.com/docs/cli/slash-commands): supported command concepts and explicit skill/app references; not a desktop-feature parity promise.
- [Codex app](https://learn.chatgpt.com/docs/app) and [Replit mobile](https://replit.com/products/mobile): visual reference structure; no provider branding or downloaded proprietary font is used.
- [ChatGPT appearance](https://help.openai.com/en/articles/11958281-updating-your-visual-experience-on-chatgpt) and [OpenAI brand guidelines](https://openai.com/brand/): appearance inspiration while retaining PocketAgent branding and Android fonts.
- [Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) and [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent): explicit on-device recognition, microphone access and external recognition preferences.
- [Cursor ACP](https://cursor.com/docs/cli/acp): official agent protocol and login.
- [Claude Code legal and compliance](https://code.claude.com/docs/en/legal-and-compliance) and [CLI reference](https://code.claude.com/docs/en/cli-reference): official CLI operation and subscription/OAuth boundaries.
- [Antigravity Zed integration](https://antigravity.google/docs/ide/extensions/zed/) and [Antigravity terms](https://antigravity.google/terms): that documented integration alone does not establish permission for PocketAgent.

## Changes from the previous desktop app

Pehle main experience Linux desktop/VNC viewer tha. PocketAgent ka launcher ab native mobile
IDE hai: touch chat, approvals, files, Git tools aur preview. Ubuntu isi phone par background
mein tools chalata hai; cloud computer ya heavy desktop session required nahi. Beta.8 mein
chat ko zyada jagah milti hai: chhota composer, side panel aur short menus. Effort, usage, files,
preview, accounts aur real local activity ke controls bhi available hain. Phir bhi phone ki hardware limits,
provider ke account rules aur real-device testing baaki hain; complete desktop parity ya
har project ka successful build guarantee nahi hai.
