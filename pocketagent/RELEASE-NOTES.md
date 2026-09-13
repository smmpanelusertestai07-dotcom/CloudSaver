# PocketAgent 14.0.5 — a key that survives, and three sentences that were not true

Version **14.0.5**, code **435**.

**The signing key is now in the repository, on purpose.** The build minted a fresh key whenever
`.signing/` was absent, and `.gitignore` kept that folder out of the repository — so every clone
signed with a key of its own, and no two builds could install over each other. For an app that
is delivered as an APK and installed by hand, that is not a signing policy: it is a promise that
each update costs its owner every project, chat and connected account they had. The key is
committed and deliberately public. It is **not a secret** — anyone can sign an APK with it, so it
proves nothing about who built a file — and it exists for one reason: the next build installs
over this one. A Play Store release still uses a private key through `POCKETAGENT_KEYSTORE`.
The `-devkey` suffix goes with it, so the delivered APK no longer labels itself unable to
replace anything.

**Antigravity's unavailability was explained wrongly, three times.** The app said Google's
published terms restrict third-party access and that permission for PocketAgent had not been
established. That is not what Google publishes. Their own headless documentation shows a script
holding `agy`'s stdin open and driving it turn by turn, and names no restriction on who may do
it. The catalog also described an "Official Antigravity ACP engine"; `agy` has no native ACP
mode at all — adding one is still an open request on Google's own CLI repository, and every ACP
route to it today is a community adapter. So the code would not have worked even unblocked.

All three lines now say the true thing: Antigravity is not connected because PocketAgent has no
adapter for `agy`'s own newline-delimited event stream, and that work is ours, not Google's. An
owner told "permission was refused" waits for Google; an owner told the truth knows what is
actually missing.

**The tagline was still PocketLinux's.** Two screens read "A Linux computer that runs locally on
your phone". This app is not a Linux computer — the Ubuntu userspace is where the agents run,
not the thing being offered. Both now read "AI agents that build real software on your phone".

Earlier release notes are in the [historical archive](history/PRE-REBRAND-RELEASE-NOTES.md).
