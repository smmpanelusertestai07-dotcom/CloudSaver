package com.pocketide.bridge

import com.pocketide.core.AppDirs

/**
 * The programs a room runs to reach the phone bridge. The rooms module writes them into the
 * shared tools directory (mode 0755) and puts [BIN_DIR] first on every room's PATH, with
 * BROWSER set to [XDG_OPEN], so every agent's sign-in link ends up in the phone's browser.
 */
object PhoneGuestTools {
    const val BIN_DIR = "${AppDirs.GUEST_TOOLS}/bin"

    /** Where [xdgOpen] is installed. */
    const val XDG_OPEN = "$BIN_DIR/xdg-open"

    /**
     * xdg-open for a room. Sign-in flows run `xdg-open <link>` (agy, Node's "open") or
     * `$BROWSER <link>` (Python's webbrowser); without a program there, agy logs
     * "executable file not found" and the owner would have to retype a link hundreds of
     * characters long. This one hands http and https links to the app's "open_url" op and opens
     * nothing else. When the phone does not answer it prints the link, so it can still be
     * copied. Exit codes follow xdg-open: 1 usage, 3 not a web address, 4 not opened.
     */
    val xdgOpen: String = """
        #!/usr/bin/env python3
        # PocketIDE's xdg-open: web addresses open in the phone's browser; nothing else opens.
        import json
        import os
        import socket
        import sys

        SOCKET = os.environ.get("POCKETIDE_PHONE_SOCKET", "${PhoneBridge.GUEST_SOCKET}")
        USAGE = "Usage: xdg-open <http or https address>"
        MAX_REPLY = 1024 * 1024


        def ask_phone(url):
            request = {"id": 1, "op": "${PhoneBridge.OPEN_URL}", "args": {"url": url}}
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as link:
                link.settimeout(60)
                link.connect(SOCKET)
                link.sendall((json.dumps(request) + "\n").encode("utf-8"))
                link.shutdown(socket.SHUT_WR)
                with link.makefile("rb") as replies:
                    return json.loads(replies.readline(MAX_REPLY).decode("utf-8"))


        def main(args):
            if args in (["--help"], ["--manual"]):
                print(USAGE)
                return 0
            if args == ["--version"]:
                print("xdg-open (PocketIDE)")
                return 0
            if len(args) != 1:
                print(USAGE, file=sys.stderr)
                return 1
            url = args[0]
            if not url.lower().startswith(("http://", "https://")):
                print("PocketIDE opens only web addresses (http and https) on the phone.", file=sys.stderr)
                return 3
            try:
                reply = ask_phone(url)
            except (OSError, ValueError):
                print("PocketIDE did not answer. Open this room in PocketIDE and try again.", file=sys.stderr)
                print("The address was: " + url, file=sys.stderr)
                return 4
            if isinstance(reply, dict) and reply.get("ok") is True:
                return 0
            error = reply.get("error") if isinstance(reply, dict) else None
            print(error or "The page could not be opened.", file=sys.stderr)
            print("The address was: " + url, file=sys.stderr)
            return 4


        if __name__ == "__main__":
            sys.exit(main(sys.argv[1:]))
    """.trimIndent() + "\n"
}
