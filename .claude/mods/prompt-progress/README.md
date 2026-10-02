# prompt-progress

A Claude Code mod (a plugin of function hooks) that puts two progress bars at the prompt:

```
Context ████████░░░░░░░░░░░░░░░░░░░░░░░░ 25% · 250k/1M tokens
Tasks   ████████████████░░░░░░░░░░░░░░░░ 2/4 · Writing tests
```

- **Context**: how full the model's context window is, as of the last response. Yellow
  from 60%, red from 80%.
- **Tasks**: how far the session's task list (TaskCreate / TaskUpdate, or TodoWrite) has
  got, and what is running now. A list that is all done goes away with the next prompt.

## Where it shows

| Where the session is drawn | What you get |
| --- | --- |
| Terminal, Claude Code Desktop | A two-line band directly above the prompt input |
| No terminal or desktop attached (a cloud session followed from the web or the phone) | The same bars as this plugin's status line, where the client shows plugin status lines |
| Anywhere | `/progress` prints the figures; `/progress off` and `/progress on` hide and show the bars |

The band is the `AbovePrompt` site, which only the terminal and the desktop draw today.

## Loading it

Nothing loads this folder by itself. Pick one:

- **One cloud or local session**: ask Claude to copy this folder into the session's mods
  folder (`~/.claude/dev-mods/<session id>/prompt-progress/`) and answer *Enable for this
  session* when Claude Code asks *Enable hot reloading for this session?*. It loads when
  that turn ends.
- **Locally**: `claude --plugin-dir .claude/mods/prompt-progress`
- **Every session on this repository**: move the folder to `.claude/skills/prompt-progress/`.
  It then loads as `prompt-progress@skills-dir` once the workspace is trusted.

## Checking it

```
claude plugin validate .claude/mods/prompt-progress
claude plugin test .claude/mods/prompt-progress
```

Built against Claude Code 2.1.287. The mods API is early access and can change between
releases; `claude plugin validate` says what a newer build would refuse.
