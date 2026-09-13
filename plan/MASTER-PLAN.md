# Master plan — the phone development rig

**Status:** planning only. No code is written from this document until the owner confirms the
name and the icon. Last researched: 13 September 2026.

**Standing rules this plan inherits (do not break):**

- Never edit anything that already exists in CloudSaver. New work goes in its own top-level folder.
- Never touch `pocketlinux/` on GitHub. It stays exactly as it is.
- Push to `main` and to `claude/mobile-vm-ai-app-2siqua`.
- Every string inside the app is English. Every reply in chat is Hinglish.
- Version names end in 0 or 5.
- Comparisons go in chat as tables, never as an HTML artifact.
- No secret, key, token or `.env` in the APK or the repo.

---

## 1. Identity

### 1.1 Name — shortlist with availability

Checked against Google Play, the App Store, GitHub and general web search on 13 Sep 2026.

| Candidate | Availability | Notes |
|---|---|---|
| **PocketRig** | ✅ **clean — no product found** | "Rig" is developer slang for a workstation ("my dev rig"). Short, spellable, family-consistent with PocketLinux. |
| Handforge | ✅ clean | Evocative, but does not say "development". |
| Palmforge | ✅ clean | Palmtop + forge. Slightly retro. |
| PocketStudio | ❌ taken | Several Play Store apps (photo editing, wardrobe, thermal printer). |
| PocketForge | ❌ taken | App Store developer account, GitHub projects, Patreon project. |
| PocketIDE / Pocket IDE | ❌ taken | Live on Play Store **and** App Store. |
| PocketDev | ❌ taken | **PocketDev AI** on Play Store — a direct competitor in this space. |
| PocketBench | ❌ taken | GitHub repo, Hytale mod. |
| PocketCode | ❌ taken | Android IDE, active. |
| Devbox | ❌ taken | Jetify. |

**Recommendation: `PocketRig`.**
Reasons: it is the only clean name that is also *descriptive in the industry's own words*. A
developer reads "rig" as "the machine I build on". It continues the PocketLinux naming family
the owner already owns, and it is not tied to three vendors — which matters now that the app
opens to any extension.

Package id: `com.pocketrig`
APK name: `PocketRig-v<version>-release.apk`

### 1.2 Tagline

House style from PocketLinux is a plain factual line, not a slogan. Candidates:

1. **"A real development rig that runs on your phone."** ← recommended
2. "Linux, VS Code and your coding agent — all on the phone."
3. "Build anything, from your pocket."

Recommended (1) because it mirrors PocketLinux's own working line
("A Linux computer that runs locally on your phone") and is literally true.

**Sub-line for the hero card:**
"Ubuntu 24.04 LTS · Visual Studio Code · any coding agent · no computer required"

### 1.3 Icon direction

PocketAgent's mark was brackets `[ ]` with a spark, on a deep violet tile. That reasoning is
recorded in `pocketdoors/app/src/com/pocketagent/doors/Brand.java` and is worth keeping:
violet was chosen because it is the slot no agent vendor occupies on a home screen.

For PocketRig the mark should read as *a workbench / rig*, not as an editor:

- A simple isometric or flat **bench/rail** form, or a **bracket pair holding a bar**.
- Same deep-violet tile family, because it still sits beside Claude (terracotta), Codex (dark),
  Antigravity (rainbow arch) and VS Code (blue) on the same home screen.
- Bone/warm-white mark, never pure white.
- Generated from `branding/tokens.json` by a script, with a gate test that fails the build if
  the code's colours and the token file ever disagree. (This pattern already exists and works.)

**Both name and icon are pending the owner's confirmation. Nothing is built until then.**

---

## 2. What the app is, in one paragraph

PocketRig puts a real Ubuntu 24.04 LTS ARM64 system inside an Android app, runs the official
open-source build of Visual Studio Code on it, and lets the owner install any coding-agent
extension from the Open VSX registry — with Google's Antigravity, Anthropic's Claude Code and
OpenAI's Codex set up in one tap. Everything runs on the phone. No computer is needed at any
point, including for setup.

---

## 3. Architecture (all verified 13 Sep 2026)

```
┌──────────────────────────────────────────────────────────────┐
│ 1. Phone            Android 13+ · arm64-v8a · ~4 GB RAM      │
├──────────────────────────────────────────────────────────────┤
│ 2. PocketRig APK    plain Java, no framework   (~600 KB)     │
│    └─ WebView (the only surface the owner sees)              │
├──────────────────────────────────────────────────────────────┤
│ 3. Ubuntu 24.04.4 LTS arm64, under PRoot (no root)           │
│    in app-private storage                                    │
├──────────────────────────────────────────────────────────────┤
│ 4. code-server 4.137.0  =  Code v1.137.0 (Code-OSS)          │
│    127.0.0.1 only, code-server's own password auth           │
├──────────────────────────────────────────────────────────────┤
│ 5. Extensions (extension host, Node, server-side)            │
│    Google.google-antigravity · Anthropic.claude-code ·       │
│    openai.chatgpt · anything else from Open VSX              │
└──────────────────────────────────────────────────────────────┘
                             ↕ HTTPS
┌──────────────────────────────────────────────────────────────┐
│ 6. Each vendor's own cloud — the model runs there            │
└──────────────────────────────────────────────────────────────┘
```

### 3.1 Verified component facts

| Component | Version / fact | Licence | Verified how |
|---|---|---|---|
| Ubuntu base | 24.04.4 LTS ARM64, supported to 2029 | free | Canonical mirror + SHA-256 |
| PRoot | bundled native binary | GPL-2.0 | in-repo |
| code-server | **4.137.0**, bundles **Code v1.137.0** | **MIT** (read the LICENSE file) | npm registry + GitHub release notes |
| Code-OSS source | MIT, © Microsoft | **MIT** (read LICENSE.txt) | raw.githubusercontent |
| `Google.google-antigravity` | **1.3.0**, 10 Sep 2026, `universal`, **3,725,666 bytes**, engines `^1.80.0`, sha256 `c24b6a99…58b92e` | proprietary | Open VSX API, namespace `Google` `verified: true` |
| `Anthropic.claude-code` | **2.1.270**, 12 Sep 2026, `linux-arm64`, engines `^1.94.0` | proprietary | Open VSX API, `verified: true` |
| `openai.chatgpt` | **26.5908.31748**, 11 Sep 2026, `linux-arm64`, engines `^1.96.2` | proprietary | Open VSX API, `verified: true` |

Engine check: code-server ships Code 1.137.0, which satisfies 1.80 / 1.94 / 1.96. All three run.

### 3.2 Why code-server and not the vendors' desktop IDEs

Tried and rejected in earlier builds, with the reason recorded so it is not retried:

| Approach | Why it failed |
|---|---|
| Antigravity IDE (Electron) over VNC | 702 MB installed, Electron + Xvfb + openbox + VNC. Renderer crashed (`reason: 'crashed', code: '5'`) against Android's ~32-process ceiling on 3.9 GB RAM. Framebuffer streaming is never going to feel native. |
| Vendors' own Remote Control | OpenAI's Codex Remote requires a **Mac or Windows desktop host** — Linux is not supported. Anthropic's needs Pro/Max (API keys are not accepted). Google's headless daemon has a known registration bug. Gives at best 2 of 3 and breaks "one method per vendor". |
| Native app on the vendors' headless APIs | All three do publish official headless interfaces (`agy -p --output-format stream-json`, `claude -p --output-format stream-json`, Codex app server over JSON-RPC). But Google's own docs state: *"No remote approval flow exists. Custom clients cannot receive and respond to approval requests in streaming mode."* Approvals would have to be pre-configured or skipped. It also throws away all three official GUIs. |

code-server keeps all three official GUIs, needs no X server, no Electron and no VNC, and each
extension is the publisher's own — which is what "official GUI" actually means here.

---

## 4. Framework decision

**Plain Java against the Android SDK. No Gradle, no AndroidX, no Kotlin, no Compose, no
cross-platform framework.** Built with `aapt2` + `javac` + `d8` + `apksigner`.

| Why | |
|---|---|
| Size | The current APK is ~600 KB. An AndroidX + Compose build of the same screens is 8–15 MB before any content. |
| Speed on the target device | A Realme C25s (Helio G85, 3.9 GB RAM) is the reference. Fewer classes to load means a faster cold start and less memory pressure — and the memory budget is already spent on Ubuntu, Node and an extension host. |
| Future-proof | Nothing to migrate. AndroidX, Compose and Gradle plugin majors break every year; the platform SDK does not. PocketLinux has survived this way. |
| Legal | No transitive dependency tree to licence-audit. Every third-party file in the repo is listed by hand in the notices. |
| Reviewable | 17 Java files, ~5,500 lines today. The whole app can be read end to end. |
| Honest cost | No Material components library, so the design system is hand-built in `Ui.java`. That is already done and proven in both existing apps. |

**Rejected:** Flutter / React Native (adds a 6–20 MB runtime to host a WebView), Compose
(memory), Gradle (build-time dependency resolution over mobile data in CI).

### 4.1 One-update future-proofing

The point of the architecture is that **the app is a host, not a product surface**. New agent
features arrive through the extensions from Open VSX, not through an APK update. The APK only
needs updating when Android itself changes. Concretely:

- Agent features / models / UI → publisher updates the extension → Open VSX → auto-update.
- Editor features → code-server release → our updater offers it.
- A new AI company appears → the owner installs their extension. No APK change.
- Linux packages → `apt`.

---

## 5. Design system

### 5.1 The honest correction on "liquid glass"

**Liquid Glass is Apple's design language (iOS 26). Google has publicly ruled it out for
Android** — Android's leadership stated it is not coming to Pixel, and Google is evolving
**Material 3 Expressive** instead (announced I/O 2025, standard in Android 16). Shipping a
Liquid-Glass clone on Android in 2026 reads as an iOS port, not as a modern Android app.

**Decision:** Material 3 Expressive as the base language, with **translucent glass surfaces as
this app's signature accent** — which is what PocketLinux already does (`Ui.glass()`,
`Ui.metal()`). Glass is used for elevated cards and the bottom bar, not for everything.

### 5.2 Tokens

| Role | Value | Why |
|---|---|---|
| Brand tile top | `#7A3CD6` | inherited; violet is the slot no agent vendor occupies |
| Brand tile bottom | `#33146F` | |
| Brand flat | `#56289F` | splash background must be flat, not a ramp |
| Accent (light) | `#8B55E8` | |
| Accent (dark) | `#B79BF5` | |
| Mark / on-brand | `#F7F3EB` | warm bone, never pure white |
| Neutral surface | warm, not grey | ink-on-cream, bone-on-ink |
| Added / success | green | |
| Needs you / warning | amber | |
| Removed / failed | red | |

**Rule kept from PocketAgent:** the interface itself is colourless. Nothing decorative is
coloured, so a coloured pixel in this app always means something.

### 5.3 Type, spacing, touch

- System font stack (`sans-serif`, `sans-serif-medium`) — no bundled font, no download.
- Minimum touch target **48 dp** (already a constant in `Ui.java`).
- Card radius 14–20 dp; glass = translucent fill + hairline stroke + soft shadow.
- Respect the system dark/light setting; never force a theme.
- Every screen must work at 720 × 1600 (≈400 dp wide) without horizontal scrolling.

### 5.4 The editor's phone mode

VS Code's desktop layout assumes 1200 px+. The WebView gets ≈400 dp. These are VS Code's own
documented settings (verified in `workbench.contribution.ts`), not hacks:

```json
{
  "window.commandCenter":           true,
  "workbench.activityBar.location": "bottom",
  "workbench.statusBar.visible":    false,
  "workbench.editor.showTabs":      "none",
  "window.zoomLevel":               1.5,
  "workbench.startupEditor":        "none"
}
```

- `activityBar.location: "bottom"` turns the installed agents into a **bottom tab bar** within
  thumb reach. Accepted values are `default` / `top` / `bottom` / `hide` (note: `hide`, not
  `hidden`).
- `window.commandCenter` puts a **tappable** command launcher in the title bar, so
  `Ctrl+Shift+P` is never required.
- Nothing is removed. A "Desktop layout" toggle in Settings restores the full chrome.

### 5.5 Our own bottom bar (native, outside the WebView)

| Control | Does | Default |
|---|---|---|
| **⌘ Commands** | opens the Command Palette | visible |
| **⌨ Keys** | shows/hides the key row (Esc, Tab, Ctrl, arrows) | **row hidden** |
| **⊹ Trackpad** | finger-drag cursor control for the editor | hidden |
| **📁 / 🤖** | switch Agent panel ↔ Editor | visible |

### 5.6 What touch can and cannot do (verified)

| Surface | Touch |
|---|---|
| Agent panels — all three are `webview` | ✅ full: scroll, tap, select, buttons |
| Workbench — lists, sidebar, panels, palette | ✅ VS Code supports touch tap and scrolling |
| **Monaco editor text selection** | ❌ **not supported** — Microsoft's own open issue (`monaco-editor#4622`, `#1504`). The trackpad control is the mitigation. |

### 5.7 Screens

| Screen | Contents |
|---|---|
| **Home** | Hero card (name, tagline, state pill) · Agents installed · Set-up status · Phone health (space, heat, data) · Shortcuts to Extensions, Settings, Help |
| **Set up** | Staged progress with real byte counts, wake-lock held, plain-language failures |
| **Workspace** | Full-screen WebView: agent panel by default, editor one tap away, our bottom bar |
| **Extensions** | Three tiers (§7). Recommended / Verified publishers / Everything else |
| **Toolchains** | Optional installs: Android SDK, Godot export templates, Go, Rust, Java, Python |
| **Settings** | Appearance, desktop layout toggle, data & files, security, uninstall/reset |
| **Help** | FAQ (§8), Terms (§9), Privacy (§10), Open-source notices (§11) |

---

## 6. What can be built, and exactly how

| Target | On the phone? | How |
|---|---|---|
| Websites — HTML/CSS/JS, React, Vue, Next.js, Svelte | ✅ | directly |
| Backends / APIs — Node.js, Python (FastAPI, Django, Flask) | ✅ | directly |
| Go, Rust, Java, C, C++, Python — write, compile, run | ✅ | Ubuntu arm64 toolchains |
| CLI tools, scripts, bots, scrapers | ✅ | directly |
| Git — branch, commit, PR, review | ✅ | directly |
| **Android APK** | ✅ with a toolchain install | §6.1 |
| **iOS `.ipa`** | ✅ via GitHub Actions | §6.2 |
| **2D/3D games** | ✅ | §6.3 |
| Unity game | ⚠️ scripts on phone, build in CI | no arm64 Linux Unity editor exists |
| Native Xcode Swift/SwiftUI project | ❌ | needs a cross-platform framework instead |

### 6.1 Android APK toolchain (optional install, ~600 MB)

| Piece | Source | Arch situation |
|---|---|---|
| JDK 17 | `apt install openjdk-17-jdk` | native arm64 ✅ |
| Android SDK cmdline-tools | Google | Java, architecture-independent ✅ |
| `d8`, `apksigner` | SDK | Java ✅ |
| **`aapt2`, `zipalign`** | SDK | ⚠️ **Google ships x86_64 Linux only** |

Two documented routes for `aapt2`, in this order:
1. **Native arm64 builds** compiled from AOSP source (community projects exist and are validated
   on real devices).
2. **Box64** translating the official x86_64 binary.

This is a **community** dependency, clearly labelled as such in the app, not presented as
Google-official.

### 6.2 iOS — GitHub Actions only, no Codemagic

The owner asked for GitHub + the trick, not third-party CI. That is the better answer anyway:

> **GitHub-hosted macOS runners are free, with no minute limit, for public repositories.**
> `macos-15` is current (June 2026) and carries Xcode's command-line build tools, so it can
> build and sign iOS apps.

For private repositories, macOS minutes bill at **10×** against the free quota — 2,000 Linux
minutes becomes ~200 macOS minutes. The app must say this plainly.

**Flow, all from the phone:**

```
write app on phone (Flutter / React Native / Expo / Capacitor)
   → git push
   → GitHub Actions runs on macos-15 (free for public repos)
   → build + sign
   → .ipa downloadable from the workflow artifacts
```

PocketRig ships a **"Create iOS build workflow"** action that writes a ready
`.github/workflows/ios.yml` into the project, so the owner never hand-writes YAML.

**Not possible, and the app will say so:** compiling a native Xcode Swift/SwiftUI project on
the phone itself. Xcode and `codesign` run only on macOS. Cross-platform frameworks are the
route.

### 6.3 Games — Godot

| Fact | Detail |
|---|---|
| Godot Android editor | **4.7.2, 18 Aug 2026**, arm64, on Play Store and as APK |
| What it does | create, develop and **export** 2D and 3D projects on Android |
| Exports to | Android **and iOS** |
| Licence | MIT |
| ⚠️ Limitation | **C# is not available in the Android editor** — GDScript only |

Two routes, both offered:
- **Visual editing** → Godot's own Android app, alongside PocketRig.
- **Code + export inside PocketRig** → the agent writes GDScript, then
  `godot --headless --export-release` produces the APK. Headless needs no display.

---

## 7. Extensions and security

### 7.1 The risk is real — this must be stated in the app

Visual Studio Code's own documentation:

> *"The extension host has the same permissions as VS Code itself. This means that any action
> that VS Code can perform, an extension can also perform through the extension host."*

Extensions are **not sandboxed**. They can read and write files, reach the network, run
processes, and read stored secrets.

Documented incidents in 2026:

| When | What |
|---|---|
| Feb 2026 | Live Server, Code Runner, Markdown Preview Enhanced, Microsoft Live Preview — **125M+ installs combined** — flaws allowing local file theft and remote code execution |
| Mar 2026 | **Open VSX "Open Sesame"** — a pre-publish scanner bypass. Reported 8 Feb, fixed 11 Feb (3 days) |
| Apr 2026 | **GlassWorm v2 — 73 counterfeit extensions on Open VSX**, cloned from legitimate ones; 6 confirmed malicious, the rest sleepers |
| May 2026 | A poisoned extension exfiltrated **3,800 internal repositories** |
| Jul–Aug 2026 | **77 counterfeit packages on Open VSX** impersonating AMD, Azure, Salesforce and a US government agency |
| Trend | Detections grew from 27 (2024) to 105 (first 10 months of 2025) |

**The load-bearing detail:** all 77 counterfeits were *"distributed by accounts unaffiliated
with the original publishers"* — that is, **unverified namespaces**. Open VSX marks a namespace
verified only when it has a real owner. Our three are verified.

### 7.2 Three tiers

| Tier | Contents | Default |
|---|---|---|
| **1 · Recommended** | `Google.google-antigravity`, `Anthropic.claude-code`, `openai.chatgpt` — verified namespace, **pinned SHA-256**, checked by a build gate | **on** |
| **2 · Verified publishers** | The whole Open VSX registry filtered to `verified: true` — LLVM's clangd, Google's Gemini Code Assist, Red Hat, and anyone else with a verified namespace | **on** |
| **3 · Everything else** | Unverified publishers | **off** — a Settings toggle, and turning it on shows a warning screen quoting the real numbers above |

Every install shows publisher, namespace verification state, version, size and SHA-256 before
it downloads. Downloads are verified against Open VSX's published `sha256`.

### 7.3 Why the app is not locked to three vendors

Because a fourth will come. The three are pre-set because they are the only three that today
publish a first-party agentic coding extension under a verified namespace — not because the
app can only host three.

---

## 8. FAQ — to ship inside the app

Written short, plain, with an icon per entry. Grouped.

### About the app

**What is this?**
A real Ubuntu Linux system and a real Visual Studio Code, running inside an Android app, with
coding agents installed as official extensions. Everything runs on the phone.

**Do I need a computer?**
No — not for setup, not for building, not at any point.

**Is this a virtual machine or an emulator?**
Neither. Ubuntu runs directly on the phone's own kernel through PRoot, with no root and no VM.
That is why an older phone can run it.

**Where does my code live?**
In this app's private storage on the phone. No cloud sync, no backup, no upload. Uninstalling
the app deletes all of it.

### The editor

**Is this the real VS Code?**
It is Code-OSS — the MIT-licensed source of Visual Studio Code, packaged by Coder as
code-server. Editor, terminal, debugger, git, search, extensions and settings all work.

**Do I need a Microsoft licence or account?**
No. VS Code's source is MIT-licensed, and code-server is MIT-licensed. No licence, no account,
no activation, no payment.

**What is missing compared to Microsoft's own build?**
Only Microsoft's own proprietary parts: their marketplace, the C# debugger, the Windows C++
debugger, Remote-SSH / Dev Containers / WSL, and Live Share. None of them apply to a phone
that is itself the machine. Open-source alternatives exist for C and C++ (LLVM's clangd, with
29.5M downloads) and for C#.

**What is Open VSX?**
The extension registry Code-OSS builds use, run by the Eclipse Foundation. Microsoft's
marketplace is restricted by its terms to Microsoft's own products, so every non-Microsoft
build — VSCodium, code-server, Cursor, Windsurf and **Google's own Antigravity IDE** — uses
Open VSX instead.

**Is Open VSX behind Microsoft's marketplace?**
Not for these extensions. On 13 Sep 2026 all three agent extensions carried the **same version,
published the same day**, on both registries. Microsoft's registry is larger overall (141,332
extensions vs 17,758), which matters only if you need an extension that is not on Open VSX.

### The agents

**Which agents are set up?**
Google's Antigravity, Anthropic's Claude Code and OpenAI's Codex — each the publisher's own
extension from their own verified namespace.

**Can I add others?**
Yes. Any extension on Open VSX. Verified publishers are browsable by default; unverified ones
need a setting turned on.

**Why these three?**
They are the only companies that today ship all four of: their own frontier model family, their
own first-party agentic coding tool, frontier-scale compute they own, and a published
frontier-safety policy. Independent 2026 trackers place them level with each other and ahead of
others on agentic coding.

**Which are free?**
Only Antigravity has a real free tier — Google's Individual plan is $0 and includes Gemini 3.8
/ 3.7 / 3.6 Flash, Gemini 3.1 Pro, Claude Sonnet and Opus 4.6, and gpt-oss-120b, with unlimited
tab completions and command requests under weekly rate limits. Claude Code needs Pro, Max, Team
or Enterprise (or pay-as-you-go). Codex is included in ChatGPT Plus, Pro, Business, Edu and
Enterprise.

**Does my code get uploaded?**
The model always runs in the vendor's cloud — that is true of every coding agent anywhere. The
extension sends the context it needs to answer. Your files stay on the phone and edits happen
on the phone.

### Where this fits among the ways to use an agent

The industry calls these **development interfaces** or **product surfaces**. There are four,
and all three companies support all four:

| Surface | What it is | Runs on a phone? |
|---|---|---|
| Agent-first desktop workspace | An app where you hand over a project and supervise the agent | ❌ desktop only; phone via remote companion |
| **IDE integration (extension)** | Agent inside the editor, with code, terminal and debugger | ❌ normally desktop — **this app is the exception** |
| Terminal agent (CLI/TUI) | Agent in the project folder's terminal | ❌ normally; remote access possible |
| Browser / web workspace | Build and preview in a browser | ✅ but capabilities vary |

Two supporting modes also exist: a **mobile remote companion** (the work still runs on a
connected computer or in the cloud) and **headless automation via SDK/API/CI**.

**PocketRig's position:** it makes the second row — IDE integration, ranked second overall for
features and control — run on the phone itself, with no connected computer. That is the whole
point of the app.

### Building things

**Can I build an Android APK?**
Yes, after installing the Android toolchain from the Toolchains screen. One caveat stated
honestly: Google ships `aapt2` for x86_64 Linux only, so PocketRig uses a community-built arm64
binary (or Box64 translation) for that one tool.

**Can I build an iOS app without a Mac?**
Yes, for cross-platform projects. Write the app here (Flutter, React Native, Expo, Capacitor),
push to GitHub, and GitHub's macOS runners build and sign the `.ipa`. **For public repositories
these runners are free with no minute limit.** For private repositories macOS minutes bill at
10×, so the free quota goes about ten times faster.
**What is not possible:** compiling a native Xcode Swift/SwiftUI project on the phone. Xcode
and `codesign` only run on macOS.

**Can I make games?**
Yes. Godot's official Android editor (4.7.2, arm64, MIT) creates, develops and exports 2D and
3D projects, to Android and iOS. Inside PocketRig the agent can write GDScript and export
headlessly. Godot's Android editor does not support C#. Unity has no arm64 Linux editor —
scripts can be written here and built in CI.

### Phone, performance and safety

**Which phones work?**
Android 13 or newer, arm64-v8a, about 4 GB of RAM, and free space for what you install
(≈410 MB for the base plus the agents you choose).

**Will my phone get hot?**
It gets warm while an agent works — that is normal. The app stops the workspace above a
temperature threshold to protect the battery.

**Are extensions safe?**
Extensions are not sandboxed — VS Code's own docs say they have the same permissions as the
editor. Counterfeit extensions have appeared on both registries in 2026. PocketRig defaults to
verified publishers only, pins and verifies SHA-256 for the three recommended ones, and warns
before unverified installs are enabled.

**What happens if I uninstall?**
Everything goes: Ubuntu, the editor, the extensions, your projects, your sign-ins. Nothing is
left anywhere else, because nothing was ever anywhere else.

---

## 9. Terms of Use — to ship inside the app (short form)

1. **What this app is.** PocketRig is a host. It installs and runs software published by other
   people — Canonical's Ubuntu, Coder's code-server, Microsoft's Code-OSS, and extensions from
   the Open VSX registry. It is not affiliated with, endorsed by or sponsored by Microsoft,
   Google, Anthropic, OpenAI, Canonical, Coder or the Eclipse Foundation.

2. **Third-party terms apply.** Using an agent means agreeing to that company's own terms and
   paying for their own plan. PocketRig does not resell, proxy or subsidise any of them.

3. **Accounts and payment.** You sign in to each agent with your own account, inside that
   publisher's own extension. PocketRig never asks for, sees or stores those credentials.

4. **Extensions are third-party software.** Extensions run with the same permissions as the
   editor. Installing an unverified extension is your decision and your risk. PocketRig shows
   the publisher, the verification state and the checksum before anything is installed.

5. **No warranty.** The app is provided as-is. Building, publishing and distributing anything
   you make is your responsibility, including app-store rules, licences and the law where you
   live.

6. **Community components.** Some optional toolchains (for example the arm64 `aapt2` used for
   Android builds) are community-maintained, not vendor-official. They are labelled as such
   before installation.

7. **Changes.** The terms shown in the app are the current ones. Material changes are noted in
   the release notes.

8. **Licence.** PocketRig's own code is Apache-2.0. Every bundled component keeps its own
   licence; see Open-source notices.

---

## 10. Privacy — to ship inside the app (short form)

**The whole policy in one line: nothing leaves your phone except what you ask for.**

| Question | Answer |
|---|---|
| Does PocketRig collect analytics or telemetry? | **No.** None, ever. |
| Does it have an account or a server? | **No.** There is no PocketRig account and no PocketRig server. |
| Where do my files live? | This app's private storage. Other apps cannot read it. |
| Is anything backed up or synced? | **No.** |
| When does the app use the network? | Three times only: downloading Ubuntu packages, downloading extensions from Open VSX, and each agent talking to its own company. |
| What does an agent send? | The context it needs to answer, to its own company, under that company's privacy policy. Your files stay here; edits happen here. |
| Where are my sign-ins stored? | Inside the Linux system, in the publisher's own extension storage — never in PocketRig's own code and never in the APK. |
| Does the app ask for camera, microphone, location, contacts, SMS or storage? | **No.** |
| What happens on uninstall? | Everything is deleted with the app. |

**Permissions requested, and why:**

| Permission | Why |
|---|---|
| `INTERNET` | downloads and the agents' own traffic |
| `ACCESS_NETWORK_STATE` | follow DNS when Wi-Fi and mobile data swap |
| `WAKE_LOCK` | keep a long set-up from being interrupted |
| `POST_NOTIFICATIONS` | "workspace is running" with a Stop button |
| `FOREGROUND_SERVICE` (+ `SPECIAL_USE`) | stop Android killing the workspace |
| `VIBRATE` | key-row feedback |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | keep long work from being cut off |

---

## 11. Open-source notices — structure to ship

Follow PocketLinux's existing structure, which is already good:

- Application logo and mark (ours, Apache-2.0)
- Ubuntu (Canonical) — trademark note, no endorsement implied
- PRoot (GPL-2.0)
- code-server (MIT, Coder Technologies Inc.)
- Code-OSS / Visual Studio Code source (MIT, © Microsoft) — with the explicit note that this is
  **not** Microsoft's branded build and carries no Microsoft licence
- Open VSX Registry (Eclipse Foundation)
- Each installed extension, with publisher and its own licence
- Optional toolchains (JDK, Android SDK, community `aapt2`, Godot — MIT)
- **Marks this app does and does not show** — the section PocketLinux already has, kept

---

## 12. Quality gates (build fails if any fail)

Carried forward from PocketAgent, plus new ones. Every gate exists because a real mistake
happened — none are theoretical.

| Gate | Guards against |
|---|---|
| `VerifiedNamespaces` | an extension id whose Open VSX namespace is not `verified` reaching Tier 1 |
| `PinnedChecksums` | a recommended extension installed without a pinned SHA-256 |
| `EngineFits` | an extension whose `engines.vscode` exceeds the bundled Code version |
| `OfficialExtensions` | a community extension being presented as official |
| `Loopback` | code-server binding to anything but 127.0.0.1 |
| `NoSecrets` | a key, token or `.env` in the APK or repo |
| `SigningKey` | signing material committed |
| `VerifiedUrls` | a URL in the scripts that does not resolve |
| `SizesAreReal` | a size claim in the UI that does not match the real download |
| `MarkerOrder` | a READY line swallowed so a working server reports failure |
| `StaysInSession` | PRoot `--kill-on-exit` killing the server |
| `PlansAreTheirs` | claiming a plan is free when the publisher does not say so |
| `TheirNamesNotOurs` | vendor names used as if they were ours |
| `FitsTheScreen` | a screen that scrolls horizontally at 400 dp |
| `BrandAssets` | `Brand.java` and `branding/tokens.json` disagreeing |
| `AssetScriptSyntax` | a shell asset that does not parse |
| `Compiles` | — |

---

## 13. Release plan

| Release | Contents |
|---|---|
| **First** | Ubuntu + code-server + three recommended extensions + Extensions screen (3 tiers) + phone-mode editor + bottom bar + Home / Set up / Help (FAQ, Terms, Privacy, Notices) + gates |
| **Second** | Toolchains screen: Android SDK (with arm64 `aapt2`), Go, Rust, Java, Python |
| **Third** | iOS workflow generator, Godot headless export templates |

Reason for splitting: the Android toolchain alone is ~600 MB and needs its own testing pass on
the real device. Shipping it inside the first release would make the first run enormous and
untestable.

### Repository layout

```
CloudSaver/
├── pocketlinux/     ← untouched, never modified
├── pocketdoors/     ← previous PocketAgent build, left as history
├── plan/            ← this document
└── pocketrig/       ← the new app (name pending confirmation)
    ├── app/
    ├── tests/
    ├── branding/
    ├── build.sh
    └── README.md
```

---

## 14. Open questions for the owner

1. **Name** — `PocketRig` recommended. `Handforge` and `Palmforge` are the clean alternatives.
2. **Icon** — bench/rail mark on the violet tile, or keep the bracket-and-spark mark.
3. **Tagline** — "A real development rig that runs on your phone."

Nothing is built until 1 and 2 are settled.

---

## 15. Sources checked (13 Sep 2026)

Open VSX registry API (namespaces, versions, checksums, manifests) · Microsoft VS Code
`LICENSE.txt` and `workbench.contribution.ts` · Coder code-server `LICENSE` and npm registry ·
code-server FAQ · VSCodium extension-compatibility docs · VS Code extension-runtime-security
docs · Microsoft Marketplace extensionquery API · antigravity.google docs (IDE extensions, VS
Code extension, CLI headless, Remote Control, pricing) · ai.google.dev (managed agents,
Antigravity agent, agent environments, pricing) · platform.claude.com (Managed Agents overview
and quickstart) · code.claude.com (Remote Control, headless) · learn.chatgpt.com (Codex cloud,
Codex Remote, Codex IDE, Codex SDK) · developers.openai.com (Agents API, OpenAI-hosted
sandboxes) · Godot Engine Android editor docs and downloads · GitHub Actions runner pricing ·
Expo EAS and macOS-runner reporting · security reporting on 2026 extension incidents ·
VSCodroid repository and Play Store listing.
