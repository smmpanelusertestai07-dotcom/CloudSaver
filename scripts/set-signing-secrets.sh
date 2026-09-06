#!/usr/bin/env bash
#
# Puts the four signing secrets into this repository's GitHub Actions secrets,
# from the keystore file on YOUR machine. Run it once; every release after
# that carries the same signature, so a new build installs over the old one.
#
#   scripts/set-signing-secrets.sh /path/to/cloudsaver-release.jks
#
# It asks for the password without echoing it, and never writes the password
# or the key to disk, to the shell history, or to the repository. Only GitHub
# receives them, encrypted, through the official `gh secret set` command -
# the same thing the web form at Settings > Secrets and variables > Actions
# does, done for you.
#
# Why a script and not a workflow: a workflow that could write its own
# signing key would be a workflow anyone with a pull request could read it
# back out of. The key has to arrive from outside the build, exactly once.
#
# Needs: the GitHub CLI (https://cli.github.com), signed in with
# `gh auth login` as a user who administers this repository.

set -euo pipefail

REPO="${CLOUDSAVER_REPO:-smmpanelusertestai07-dotcom/CloudSaver}"
ALIAS="${KEY_ALIAS:-cloudsaver}"
JKS="${1:-}"

die() { printf '%s\n' "$*" >&2; exit 1; }

[ -n "$JKS" ] || die "usage: $0 /path/to/cloudsaver-release.jks"
[ -f "$JKS" ] || die "no such file: $JKS"
command -v gh >/dev/null 2>&1 || die "the GitHub CLI (gh) is not installed - https://cli.github.com"
command -v base64 >/dev/null 2>&1 || die "base64 is not installed"
gh auth status >/dev/null 2>&1 || die "run 'gh auth login' first"

# The password is read once, silently, and kept only in this process.
printf 'Keystore password (not shown): ' >&2
IFS= read -rs STORE_PASS
printf '\n' >&2
[ -n "$STORE_PASS" ] || die "the password cannot be empty"

# Prove the password opens the keystore and the alias exists BEFORE touching
# GitHub, so a typo cannot leave the repository with four wrong secrets.
if command -v keytool >/dev/null 2>&1; then
  keytool -list -keystore "$JKS" -storepass "$STORE_PASS" -alias "$ALIAS" >/dev/null 2>&1 \
    || die "the password does not open '$JKS', or it has no key named '$ALIAS'"
else
  printf 'keytool not found; skipping the local check and trusting the password.\n' >&2
fi

# One line of base64, exactly what the workflow decodes.
if base64 --help 2>&1 | grep -q -- '-w'; then
  B64=$(base64 -w0 "$JKS")
else
  B64=$(base64 "$JKS" | tr -d '\n')
fi

# Each value goes to gh on stdin, never as an argument, so it is not visible
# in the process list or the shell history.
printf '%s' "$B64"        | gh secret set KEYSTORE_B64      --repo "$REPO"
printf '%s' "$STORE_PASS" | gh secret set KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$ALIAS"      | gh secret set KEY_ALIAS         --repo "$REPO"
printf '%s' "$STORE_PASS" | gh secret set KEY_PASSWORD      --repo "$REPO"

unset STORE_PASS B64

cat >&2 <<MSG

Done. Four secrets are set on $REPO:
  KEYSTORE_B64  KEYSTORE_PASSWORD  KEY_ALIAS  KEY_PASSWORD

The next push to main builds, signs with this key, and publishes the release.
Keep '$JKS' somewhere private and backed up - lose it and no future build can
ever update an installed CloudSaver again.
MSG
