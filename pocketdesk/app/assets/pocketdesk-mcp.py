#!/usr/bin/env python3
"""PocketDesk's eyes and hands, offered to any AI agent over MCP.

Codex's Appshots are macOS only, and Claude Desktop's Computer Use is not in the Linux beta.
Neither is coming to a phone. But the capability behind them -- look at the screen, read what is
on it, click and type -- is ordinary X11 work, and this desktop already carries every tool it
needs: xdotool for the pointer and the keyboard, wmctrl for the window list, scrot for the
picture. Anthropic's own computer-use reference environment is built from exactly these.

So PocketDesk offers them itself, as a Model Context Protocol server over stdin and stdout. Any
agent that speaks MCP -- Claude Code, Codex, or one the owner writes -- can see this desktop and
work it, without a second machine and without any of it leaving the phone.

Deliberately dependency-free: MCP is JSON-RPC 2.0 over a line-delimited stream, which is a page
of code, and pulling a library would mean a download on a metered connection for no gain.
"""

import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile

DISPLAY = os.environ.get("DISPLAY", ":1")
PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "pocketdesk"
SERVER_VERSION = "1.0.0"

# A phone screenshot is about 720x1600. Sent whole it is a large, slow image for a model to read
# on a metered connection; scaled to this width the text of a normal window is still legible.
TARGET_WIDTH = 900
# Never hand back an image so large it stalls the agent's own request on mobile data.
MAX_IMAGE_BYTES = 4_000_000


def run(argv, timeout=20):
    """A command inside the desktop's own X session. Returns (code, stdout, stderr)."""
    environment = dict(os.environ)
    environment["DISPLAY"] = DISPLAY
    try:
        done = subprocess.run(
            argv, capture_output=True, timeout=timeout, env=environment, text=True
        )
        return done.returncode, done.stdout.strip(), done.stderr.strip()
    except FileNotFoundError:
        return 127, "", "%s is not installed in this computer" % argv[0]
    except subprocess.TimeoutExpired:
        return 124, "", "%s did not answer in %ss" % (argv[0], timeout)


def have(name):
    return shutil.which(name) is not None


def active_window():
    """(id, title, x, y, width, height) of the window in front, or None."""
    code, window, _ = run(["xdotool", "getactivewindow"])
    if code != 0 or not window:
        return None
    _, title, _ = run(["xdotool", "getwindowname", window])
    code, geometry, _ = run(["xdotool", "getwindowgeometry", "--shell", window])
    values = {}
    if code == 0:
        for line in geometry.splitlines():
            if "=" in line:
                key, _, value = line.partition("=")
                values[key.strip()] = value.strip()
    return {
        "id": window,
        "title": title,
        "x": int(values.get("X", 0) or 0),
        "y": int(values.get("Y", 0) or 0),
        "width": int(values.get("WIDTH", 0) or 0),
        "height": int(values.get("HEIGHT", 0) or 0),
    }


def screen_size():
    code, out, _ = run(["xdotool", "getdisplaygeometry"])
    if code == 0 and out:
        parts = out.split()
        if len(parts) == 2:
            return int(parts[0]), int(parts[1])
    return 0, 0


def capture(whole_screen):
    """A PNG of the active window, or of the whole desktop. Returns (bytes, note)."""
    if not have("scrot"):
        return None, "scrot is not installed, so this computer cannot take a picture of itself"
    folder = tempfile.mkdtemp(prefix="pocketdesk-shot-")
    target = os.path.join(folder, "shot.png")
    width = screen_size()[0]
    if not whole_screen:
        window = active_window()
        if window and window["width"]:
            width = window["width"]
    percent = 100
    if width and width > TARGET_WIDTH:
        percent = max(20, int(TARGET_WIDTH * 100 / width))
    # scrot writes the thumbnail beside the full picture, which is how the image is scaled down
    # without ImageMagick or Pillow: neither is installed, and both are a download.
    argv = ["scrot", "--overwrite", "-t", str(percent)]
    if not whole_screen:
        argv.append("-u")           # -u is the window with the input focus
    argv.append(target)
    code, _, error = run(argv, timeout=30)
    if code != 0:
        return None, error or "the picture could not be taken"
    thumbnail = os.path.join(folder, "shot-thumb.png")
    picture = thumbnail if percent < 100 and os.path.isfile(thumbnail) else target
    try:
        with open(picture, "rb") as handle:
            data = handle.read()
    except OSError as problem:
        return None, str(problem)
    finally:
        shutil.rmtree(folder, ignore_errors=True)
    if len(data) > MAX_IMAGE_BYTES:
        return None, "the picture came out too large to send"
    return data, None


def read_text(whole_screen):
    """The words on screen. Tesseract when it is installed; the window's own title otherwise."""
    if not have("tesseract"):
        return None
    data, error = capture(whole_screen)
    if data is None:
        return "The text could not be read: %s" % error
    folder = tempfile.mkdtemp(prefix="pocketdesk-ocr-")
    try:
        source = os.path.join(folder, "page.png")
        with open(source, "wb") as handle:
            handle.write(data)
        code, out, error = run(
            ["tesseract", source, "stdout", "--psm", "6"], timeout=120
        )
        if code != 0:
            return "The text could not be read: %s" % (error or code)
        return "\n".join(line for line in out.splitlines() if line.strip())
    finally:
        shutil.rmtree(folder, ignore_errors=True)


def in_window(x, y, window):
    if not window or not window["width"] or not window["height"]:
        return True
    return (window["x"] <= x <= window["x"] + window["width"]
            and window["y"] <= y <= window["y"] + window["height"])


# --------------------------------------------------------------------------- the tools

def tool_appshot(arguments):
    """The whole point: a picture plus the words, in one answer, like a Mac Appshot."""
    whole = bool(arguments.get("whole_screen"))
    window = active_window()
    header = "Whole desktop" if whole else (
        "Window: %s" % window["title"] if window and window["title"] else "The window in front")
    if window and not whole and window["width"]:
        header += "  (%dx%d at %d,%d)" % (
            window["width"], window["height"], window["x"], window["y"])
    content = [{"type": "text", "text": header}]
    text = read_text(whole)
    if text:
        content.append({"type": "text", "text": "Text on screen:\n" + text})
    elif not have("tesseract"):
        content.append({"type": "text", "text":
                        "Text reading is off: install it from the desktop's Tools menu "
                        "(about 35 MB) and the words will come with the picture."})
    data, error = capture(whole)
    if data is None:
        content.append({"type": "text", "text": "No picture: %s" % error})
        return content, True
    content.append({
        "type": "image",
        "data": base64.b64encode(data).decode("ascii"),
        "mimeType": "image/png",
    })
    return content, False


def tool_screenshot(arguments):
    data, error = capture(bool(arguments.get("whole_screen")))
    if data is None:
        return [{"type": "text", "text": "No picture: %s" % error}], True
    return [{
        "type": "image",
        "data": base64.b64encode(data).decode("ascii"),
        "mimeType": "image/png",
    }], False


def tool_list_windows(_arguments):
    if not have("wmctrl"):
        return [{"type": "text", "text": "wmctrl is not installed"}], True
    code, out, error = run(["wmctrl", "-l"])
    if code != 0:
        return [{"type": "text", "text": error or "the window list is unavailable"}], True
    lines = []
    for line in out.splitlines():
        parts = line.split(None, 3)
        if len(parts) == 4:
            lines.append("%s  %s" % (parts[0], parts[3]))
    width, height = screen_size()
    header = "Desktop is %dx%d pixels." % (width, height)
    return [{"type": "text", "text": header + "\nOpen windows:\n" + ("\n".join(lines) or "none")}], False


def tool_focus_window(arguments):
    target = str(arguments.get("title") or "").strip()
    if not target:
        return [{"type": "text", "text": "Give the window's title, or part of it."}], True
    code, _, error = run(["wmctrl", "-a", target])
    if code != 0:
        return [{"type": "text", "text": error or "no window matched %r" % target}], True
    return [{"type": "text", "text": "Brought %r to the front." % target}], False


def tool_click(arguments):
    try:
        x = int(arguments["x"])
        y = int(arguments["y"])
    except (KeyError, TypeError, ValueError):
        return [{"type": "text", "text": "click needs whole-number x and y."}], True
    button = int(arguments.get("button", 1))
    if button not in (1, 2, 3):
        return [{"type": "text", "text": "button is 1 (left), 2 (middle) or 3 (right)."}], True
    width, height = screen_size()
    if width and not (0 <= x <= width and 0 <= y <= height):
        return [{"type": "text", "text":
                 "%d,%d is off this screen (%dx%d)." % (x, y, width, height)}], True
    # Clicking outside the window the agent was looking at is how an agent closes something it
    # never meant to touch. It stays inside unless it says so.
    window = active_window()
    if not arguments.get("anywhere") and not in_window(x, y, window):
        return [{"type": "text", "text":
                 "%d,%d is outside the window in front (%s). Pass anywhere=true if that is "
                 "really what you want." % (x, y, window["title"] if window else "none")}], True
    code, _, error = run(["xdotool", "mousemove", str(x), str(y), "click", str(button)])
    if code != 0:
        return [{"type": "text", "text": error or "the click did not go through"}], True
    return [{"type": "text", "text": "Clicked button %d at %d,%d." % (button, x, y)}], False


def tool_type_text(arguments):
    text = arguments.get("text")
    if not isinstance(text, str) or not text:
        return [{"type": "text", "text": "type_text needs some text."}], True
    if len(text) > 4000:
        return [{"type": "text", "text": "That is too much to type in one go (4000 characters)."}], True
    # --clearmodifiers so a key the owner is holding on the phone cannot turn this into a chord.
    code, _, error = run(
        ["xdotool", "type", "--clearmodifiers", "--delay", "12", "--", text], timeout=120
    )
    if code != 0:
        return [{"type": "text", "text": error or "the text was not typed"}], True
    return [{"type": "text", "text": "Typed %d characters." % len(text)}], False


def tool_press_key(arguments):
    keys = arguments.get("keys")
    if not isinstance(keys, str) or not keys.strip():
        return [{"type": "text", "text":
                 "press_key needs an X key name, such as Return, Escape, ctrl+s or alt+Tab."}], True
    for part in keys.split():
        if not all(c.isalnum() or c in "+_-" for c in part):
            return [{"type": "text", "text": "%r is not a key name." % part}], True
    code, _, error = run(["xdotool", "key", "--clearmodifiers"] + keys.split())
    if code != 0:
        return [{"type": "text", "text": error or "the key was not pressed"}], True
    return [{"type": "text", "text": "Pressed %s." % keys}], False


def tool_scroll(arguments):
    direction = str(arguments.get("direction", "down")).lower()
    buttons = {"up": "4", "down": "5", "left": "6", "right": "7"}
    if direction not in buttons:
        return [{"type": "text", "text": "direction is up, down, left or right."}], True
    try:
        amount = int(arguments.get("amount", 3))
    except (TypeError, ValueError):
        return [{"type": "text", "text": "amount is a whole number of steps."}], True
    amount = max(1, min(amount, 25))
    code, _, error = run(
        ["xdotool", "click", "--repeat", str(amount), "--delay", "40", buttons[direction]]
    )
    if code != 0:
        return [{"type": "text", "text": error or "the scroll did not go through"}], True
    return [{"type": "text", "text": "Scrolled %s %d steps." % (direction, amount)}], False


def tool_run_in_terminal(arguments):
    """Open a terminal window running a command, so the owner can see what the agent asked for."""
    command = arguments.get("command")
    if not isinstance(command, str) or not command.strip():
        return [{"type": "text", "text": "Give the command to run."}], True
    code, _, error = run(
        ["lxterminal", "-e", "bash", "-lc", command + "; echo; read -p 'Press Enter to close '"]
    )
    if code != 0:
        return [{"type": "text", "text": error or "the terminal did not open"}], True
    return [{"type": "text", "text": "Opened a terminal window running: %s" % command}], False


TOOLS = [
    {
        "name": "appshot",
        "description": (
            "Look at the window in front of the PocketDesk desktop: a picture of it plus the "
            "words on it. This is the tool to use before clicking or typing anywhere."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "whole_screen": {
                    "type": "boolean",
                    "description": "Capture the whole desktop instead of just the window in front.",
                },
            },
        },
        "handler": tool_appshot,
    },
    {
        "name": "screenshot",
        "description": "A picture of the window in front, with no text reading.",
        "inputSchema": {
            "type": "object",
            "properties": {"whole_screen": {"type": "boolean"}},
        },
        "handler": tool_screenshot,
    },
    {
        "name": "list_windows",
        "description": "Every open window on the desktop, and the size of the screen in pixels.",
        "inputSchema": {"type": "object", "properties": {}},
        "handler": tool_list_windows,
    },
    {
        "name": "focus_window",
        "description": "Bring a window to the front by its title, or part of its title.",
        "inputSchema": {
            "type": "object",
            "properties": {"title": {"type": "string"}},
            "required": ["title"],
        },
        "handler": tool_focus_window,
    },
    {
        "name": "click",
        "description": (
            "Click at a point on the desktop, in screen pixels. Take an appshot first: the "
            "coordinates must come from what is actually on screen. Clicks outside the window "
            "in front are refused unless anywhere is true."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "x": {"type": "integer"},
                "y": {"type": "integer"},
                "button": {"type": "integer", "description": "1 left, 2 middle, 3 right"},
                "anywhere": {"type": "boolean"},
            },
            "required": ["x", "y"],
        },
        "handler": tool_click,
    },
    {
        "name": "type_text",
        "description": "Type text into whatever has the keyboard focus.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
        "handler": tool_type_text,
    },
    {
        "name": "press_key",
        "description": (
            "Press a key or a chord, in X key names: Return, Escape, Tab, ctrl+s, alt+Tab, "
            "super+a. Several separated by spaces are pressed in order."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"keys": {"type": "string"}},
            "required": ["keys"],
        },
        "handler": tool_press_key,
    },
    {
        "name": "scroll",
        "description": "Scroll the window under the pointer.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
                "amount": {"type": "integer"},
            },
        },
        "handler": tool_scroll,
    },
    {
        "name": "run_in_terminal",
        "description": (
            "Open a terminal window on the desktop running a command, so the owner can watch it. "
            "For work that needs no window, run the command directly instead."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {"command": {"type": "string"}},
            "required": ["command"],
        },
        "handler": tool_run_in_terminal,
    },
]

BY_NAME = {tool["name"]: tool for tool in TOOLS}


def advertised():
    return [{k: v for k, v in tool.items() if k != "handler"} for tool in TOOLS]


# --------------------------------------------------------------------------- the protocol

def reply(identifier, result=None, error=None):
    message = {"jsonrpc": "2.0", "id": identifier}
    if error is not None:
        message["error"] = error
    else:
        message["result"] = result
    sys.stdout.write(json.dumps(message) + "\n")
    sys.stdout.flush()


def handle(message):
    identifier = message.get("id")
    method = message.get("method")
    parameters = message.get("params") or {}

    # A notification has no id and takes no answer at all, which is what "initialized" is.
    if identifier is None:
        return

    if method == "initialize":
        reply(identifier, {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            "instructions": (
                "This is the PocketDesk Linux desktop running on the owner's phone. Use appshot "
                "to see a window before you act on it, then click, type_text, press_key and "
                "scroll to work it. Coordinates are screen pixels, top left is 0,0."
            ),
        })
    elif method == "tools/list":
        reply(identifier, {"tools": advertised()})
    elif method == "tools/call":
        name = parameters.get("name")
        arguments = parameters.get("arguments") or {}
        tool = BY_NAME.get(name)
        if tool is None:
            reply(identifier, error={"code": -32602, "message": "No tool called %r" % name})
            return
        try:
            content, failed = tool["handler"](arguments)
        except Exception as problem:                      # never take the server down with it
            content, failed = [{"type": "text", "text": "That did not work: %s" % problem}], True
        reply(identifier, {"content": content, "isError": bool(failed)})
    elif method == "ping":
        reply(identifier, {})
    else:
        reply(identifier, error={"code": -32601, "message": "Unknown method %r" % method})


def main():
    if "--selftest" in sys.argv:
        # Proves the wiring without an X display: the tool list is what an agent will see.
        print(json.dumps({"tools": [tool["name"] for tool in TOOLS]}))
        return 0
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError:
            continue
        if isinstance(message, list):
            for one in message:
                handle(one)
        else:
            handle(message)
    return 0


if __name__ == "__main__":
    sys.exit(main())
