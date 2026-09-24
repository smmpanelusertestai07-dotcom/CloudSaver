package com.pocketide.bridge

/** The few responses the bridge writes itself: a tiny page per refusal, and the entry redirect. */
internal object BridgeReplies {
    const val NOT_INSIDE_APP = "This page opens only inside PocketIDE."
    const val LINK_EXPIRED = "This link has expired. Open the page again from PocketIDE."
    const val NEXT_NOT_ALLOWED = "That address is not allowed."
    const val OTHER_PAGE = "Another page tried to reach this one. It was stopped."
    const val PORT_NOT_SHARED = "That port is not shared with this screen."
    const val NOT_SERVED_HERE = "This address is not served here."
    const val UNREADABLE = "This request could not be read."
    const val TOO_SLOW = "The request took too long to arrive."
    const val TOO_LARGE = "The request headers are too large."
    const val BUSY = "Too many connections are open. Try again in a moment."

    fun notRunning(port: Int) = "Nothing is answering on port $port yet. Start its server, then reload."
    fun noAnswer(port: Int) = "The server on port $port did not answer in time."
    fun badAnswer(port: Int) = "The server on port $port sent a reply that could not be read."

    fun page(status: Int, message: String, withBody: Boolean = true): ByteArray {
        val body = ("<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>PocketIDE</title></head><body><p>${escape(message)}</p></body></html>")
            .toByteArray(Charsets.UTF_8)
        val head = HeaderList().apply {
            add("Content-Type", "text/html; charset=utf-8")
            add("Content-Length", body.size.toString())
            add("Cache-Control", "no-store")
            add("Content-Security-Policy", "default-src 'none'")
            add("X-Content-Type-Options", "nosniff")
            add("Referrer-Policy", "no-referrer")
            add("Connection", "close")
        }
        val bytes = ResponseHead("HTTP/1.1 $status ${reason(status)}", status, head).encode()
        return if (withBody) bytes + body else bytes
    }

    /**
     * 302 to [next] with the bridge's cookie: HttpOnly so no page script can read it, and
     * SameSite=Strict so no other site's page can make the browser send it.
     */
    fun entry(next: String, cookieName: String, token: String, staleCookies: List<String>): ByteArray {
        val head = HeaderList().apply {
            add("Location", next)
            add("Set-Cookie", "$cookieName=$token; Path=/; HttpOnly; SameSite=Strict")
            staleCookies.forEach { add("Set-Cookie", "$it=; Path=/; Max-Age=0") }
            add("Cache-Control", "no-store")
            add("Referrer-Policy", "no-referrer")
            add("Content-Length", "0")
            add("Connection", "close")
        }
        return ResponseHead("HTTP/1.1 302 Found", 302, head).encode()
    }

    fun reason(status: Int): String = when (status) {
        302 -> "Found"
        400 -> "Bad Request"
        403 -> "Forbidden"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        421 -> "Misdirected Request"
        431 -> "Request Header Fields Too Large"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Error"
    }

    private fun escape(text: String) = buildString {
        for (c in text) {
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }
}
