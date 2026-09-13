# Live workspace — beta 7

`WorkspaceActivity.open(Activity, String project)` opens a native view of the selected local project. It does not connect an agent, create a VM or start a desktop.

## What the screen shows

| Surface | Actual source and controls |
| --- | --- |
| Activity | Recent visible messages, command/tool output and progress from `AgentService.snapshot()` for this project. Current-turn stop asks the existing agent to interrupt; approval replies remain in chat. |
| Background tasks | Codex's actual `thread/backgroundTerminals/list` results for the matching connected thread. The screen refreshes while visible and links to the existing task manager for explicit termination. Empty or unavailable metadata is not presented as a running process. |
| Changes | Read-only files, current Git status and staged/unstaged diff. Reads are bounded and coalesced; Git optional index locks are disabled. Files can change again while the agent continues. |
| Preview | Existing `PreviewService` plus `PreviewActivity`: a local static server or an explicitly confirmed project npm dev script. The owner can touch and test the real web preview. This is not an Android/iOS emulator. |
| Existing desktop | Appears only when the legacy desktop is installed and its owned process is already alive. Opens the existing VNC viewer. No desktop-start command is sent. |

Visible, unlocked workspace sessions send a unique `ACTION_CLIENT` presence lease and renew it every 45 seconds. They release it on pause; the service also expires stale clients. App lock and project/thread scope checks apply before requests. The Activity owns no long-running agent or preview process.

Project Tools now reads Git and GitHub status automatically while visible. Git reads are throttled to 10 seconds; GitHub status to 60 seconds and deferred during agent/setup/project work. Errors retain a Retry action. Login, package installation, commit, pull and push still require their explicit native actions.

## Cursor comparison and verified limits

Cursor's current managed Cloud Agents run in isolated VMs with a desktop, browser interaction and human remote-desktop takeover. Its documentation also describes explicit computer-use/desktop-sharing opt-ins on self-hosted Linux workers, with X11 and additional desktop dependencies. Sources: [Cloud Agent capabilities](https://cursor.com/docs/cloud-agent/capabilities), [Computer use and desktop sharing](https://cursor.com/docs/cloud-agent/self-hosted/computer-use) (checked 2026-09-12).

PocketAgent's pinned Codex 0.154.0 app-server schema and current adapter expose command execution/output and background-terminal list/terminate/clean. The new screen uses that existing integration. It does not add Cursor's remote VM service or a Codex desktop screen/control protocol. Native CLI-agent execution and the optional existing Ubuntu desktop share this phone's app-owned filesystem and resources.

The existing DesktopActivity starts a VNC viewer and owns viewer/audio/input cleanup. Its normal viewer lifecycle does not call AgentService or stop the native agent. LinuxService desktop cleanup targets its own tracked processes. The desktop still consumes phone memory/CPU; no simultaneous-desktop performance guarantee is made.

Validation: Java compilation against Android 35 and 17 focused state tests covering project/thread isolation, bounded visible output, disconnected/unknown state and matching live preview. Real phone touch/layout, prolonged operation, existing desktop coexistence and preview behavior require Android testing; no cloud/desktop takeover is simulated.
