#!/bin/bash
# What the AI apps on this computer can and cannot do with the screen, and how to prove it.
#
# Codex's Appshots are a macOS feature and Claude Desktop's Computer Use is not in the Linux
# beta, so PocketLinux provides the capability itself, over the Model Context Protocol. This
# window is where the owner sees whether it is wired up, without opening a terminal.
set -u
MCP=/usr/local/bin/pocketdesk-mcp
ask() { zenity --info --no-markup --width=460 --title="AI computer use" --text="$1" 2>/dev/null; }

line() { printf '%s\n' "$1"; }

report=""
if [ -x "$MCP" ]; then
  tools=$(python3 "$MCP" --selftest 2>/dev/null | tr -d '[]"' | sed 's/{tools: //; s/}//')
  report="PocketLinux gives any AI agent on this computer eyes and hands:

• appshot - a picture of the window in front, and the words on it
• click, type_text, press_key, scroll - working that window
• list_windows, focus_window - moving between apps
• run_in_terminal - a command in a window you can watch

Tools offered: ${tools:-unknown}"
else
  report="The desktop tools are not installed on this computer yet. Start the desktop once more and they will be put in place."
fi

if command -v tesseract >/dev/null 2>&1; then
  report="$report

Reading the words on screen: ON."
else
  report="$report

Reading the words on screen: OFF. Install it with
    sudo apt install -y tesseract-ocr tesseract-ocr-eng
and an appshot will carry the text as well as the picture."
fi

codex_state="not set up"
[ -f "$HOME/.codex/config.toml" ] && grep -q 'mcp_servers.pocketdesk' "$HOME/.codex/config.toml" 2>/dev/null \
  && codex_state="ready"
claude_state="not set up"
[ -f "$HOME/.claude.json" ] && grep -q '"pocketdesk"' "$HOME/.claude.json" 2>/dev/null \
  && claude_state="ready"
report="$report

Codex: $codex_state
Claude Code: $claude_state
Any other agent: point it at
    python3 $MCP

An agent only sees this desktop when it asks. Nothing is watched, recorded or sent anywhere on
its own, and every picture is taken and read on this phone."

case "${1:-status}" in
  status) if command -v zenity >/dev/null 2>&1; then ask "$report"; else line "$report"; fi ;;
  print)  line "$report" ;;
  *)      line "usage: pocketdesk-agent [status|print]"; exit 1 ;;
esac
