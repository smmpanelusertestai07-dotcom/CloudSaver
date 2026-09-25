package com.pocketide.ui.work

import com.pocketide.ui.web.ExternalOpen
import com.pocketide.ui.web.FilePick
import com.pocketide.ui.web.PickerKind
import com.pocketide.ui.web.WebPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPolicyTest {
    @Test
    fun originKeepsSchemeHostAndPort() {
        assertEquals("http://127.0.0.1:41234", WebPolicy.originOf("http://127.0.0.1:41234/__pocket/enter?t=abc"))
        assertEquals("https://github.com", WebPolicy.originOf("https://github.com/login/device"))
        assertEquals("http://localhost", WebPolicy.originOf("HTTP://localhost/"))
    }

    @Test
    fun onlyHttpLinksAreWebLinks() {
        assertTrue(WebPolicy.isWebLink("https://claude.ai/oauth"))
        assertFalse(WebPolicy.isWebLink("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(WebPolicy.isWebLink("javascript:alert(1)"))
        assertFalse(WebPolicy.isWebLink("file:///data/data/com.pocketide/files/secure"))
        assertFalse(WebPolicy.isWebLink("content://com.pocketide.files/share/x"))
        assertFalse(WebPolicy.isWebLink("not a url"))
        assertNull(WebPolicy.originOf("mailto:someone@example.com"))
    }

    @Test
    fun frameLocalDocuments() {
        assertTrue(WebPolicy.isFrameLocal("about:blank"))
        assertTrue(WebPolicy.isFrameLocal("about:srcdoc"))
        assertFalse(WebPolicy.isFrameLocal("about:config"))
        assertFalse(WebPolicy.isFrameLocal("https://example.com"))
    }

    @Test
    fun safeViewerLoadsOnlyItsOwnDocument() {
        assertTrue(WebPolicy.safeViewerAllows("data:text/html;charset=utf-8;base64,PGgxPg==", mainFrame = true))
        assertTrue(WebPolicy.safeViewerAllows("data:text/html,<h1>hi there</h1>", mainFrame = true))
        assertTrue(WebPolicy.safeViewerAllows("about:blank", mainFrame = true))
        assertFalse(WebPolicy.safeViewerAllows("https://evil.example/track.png", mainFrame = false))
        assertFalse(WebPolicy.safeViewerAllows("https://evil.example/", mainFrame = true))
        assertFalse(WebPolicy.safeViewerAllows("file:///sdcard/x", mainFrame = true))
        assertFalse(WebPolicy.safeViewerAllows("data:image/png;base64,AAAA", mainFrame = false))
    }

    @Test
    fun onlyATapOpensChromeAtOnce() {
        assertEquals(ExternalOpen.OPEN, WebPolicy.externalOpen("https://claude.ai/oauth/authorize?x=1", userGesture = true))
        assertEquals("a script on its own only asks", ExternalOpen.ASK, WebPolicy.externalOpen("https://evil.example/", userGesture = false))
        assertEquals(ExternalOpen.IGNORE, WebPolicy.externalOpen("intent://x#Intent;end", userGesture = true))
        assertEquals(ExternalOpen.IGNORE, WebPolicy.externalOpen("javascript:alert(1)", userGesture = true))
        assertEquals(ExternalOpen.IGNORE, WebPolicy.externalOpen("file:///data/data/com.pocketide/", userGesture = true))
        assertEquals("github.com", WebPolicy.hostOf("https://GitHub.com/login/device"))
        assertNull(WebPolicy.hostOf("::not a url::"))
    }

    @Test
    fun codeServersPortAddressGoesToThePortItself() {
        assertEquals(
            "http://localhost:1455/auth/callback?code=a%2Fb&state=s#done",
            WebPolicy.withoutEngineProxy("http://127.0.0.1:41234/proxy/1455/auth/callback?code=a%2Fb&state=s#done"),
        )
        assertEquals("http://localhost:3000/", WebPolicy.withoutEngineProxy("http://localhost:41234/proxy/3000/"))
        assertEquals("http://localhost:3000/", WebPolicy.withoutEngineProxy("http://LOCALHOST:41234/proxy/3000"))
        assertEquals("http://localhost:65535/x", WebPolicy.withoutEngineProxy("http://127.0.0.1:41234/proxy/65535/x"))
        assertEquals("http://localhost:1/a%20b/c", WebPolicy.withoutEngineProxy("http://127.0.0.1:41234/proxy/1/a%20b/c"))
    }

    @Test
    fun everyOtherAddressIsLeftAsItWas() {
        val unchanged = listOf(
            "http://127.0.0.1:41234/proxy/0/",
            "http://127.0.0.1:41234/proxy/65536/",
            "http://127.0.0.1:41234/proxy/123456/",
            "http://127.0.0.1:41234/proxy/12a/",
            "http://127.0.0.1:41234/proxy/-1/",
            "http://127.0.0.1:41234/proxy//x",
            "http://127.0.0.1:41234/proxy/",
            "http://127.0.0.1:41234/proxyx/1455/",
            "http://127.0.0.1:41234/app/proxy/1455/",
            "http://127.0.0.1:41234/?folder=/proxy/1455/",
            "https://127.0.0.1:41234/proxy/1455/",
            "http://example.com/proxy/1455/",
            "http://localhost.example.com:41234/proxy/1455/",
            "http://user@127.0.0.1:41234/proxy/1455/",
            "http://[::1]:41234/proxy/1455/",
            "https://claude.ai/oauth/authorize?x=1",
            "intent://x#Intent;end",
            "::not a url::",
        )
        for (url in unchanged) assertEquals(url, url, WebPolicy.withoutEngineProxy(url))
    }

    @Test
    fun pickerFollowsAcceptTypes() {
        assertEquals(PickerKind.IMAGES_AND_VIDEOS, WebPolicy.pickerKind(emptyList()))
        assertEquals(PickerKind.IMAGES_AND_VIDEOS, WebPolicy.pickerKind(listOf("")))
        assertEquals(PickerKind.IMAGES, WebPolicy.pickerKind(listOf("image/*")))
        assertEquals(PickerKind.IMAGES, WebPolicy.pickerKind(listOf("image/png,image/jpeg", ".webp")))
        assertEquals(PickerKind.VIDEOS, WebPolicy.pickerKind(listOf("video/mp4")))
        assertEquals(PickerKind.IMAGES_AND_VIDEOS, WebPolicy.pickerKind(listOf("image/*", "video/*")))
        assertEquals(PickerKind.IMAGES_AND_VIDEOS, WebPolicy.pickerKind(listOf("*/*")))
        assertEquals(PickerKind.IMAGES_AND_VIDEOS, WebPolicy.pickerKind(listOf("application/pdf")))
    }

    @Test
    fun pictureOnlyInputsStillOfferVideosAsKeyFrames() {
        assertEquals(FilePick(PickerKind.IMAGES_AND_VIDEOS, 4), WebPolicy.filePick(listOf("image/*"), multiple = true))
        assertEquals(FilePick(PickerKind.IMAGES_AND_VIDEOS, 1), WebPolicy.filePick(listOf("image/png"), multiple = false))
        // An input that takes videos gets them as they are.
        assertEquals(FilePick(PickerKind.IMAGES_AND_VIDEOS, 0), WebPolicy.filePick(listOf("image/*", "video/*"), multiple = true))
        assertEquals(FilePick(PickerKind.VIDEOS, 0), WebPolicy.filePick(listOf("video/mp4"), multiple = false))
        assertEquals(FilePick(PickerKind.IMAGES_AND_VIDEOS, 0), WebPolicy.filePick(emptyList(), multiple = true))
    }

    @Test
    fun unreadableVideosAreNamedOnlyWhenThereAreAny() {
        assertNull(WebPolicy.unreadableVideos(0))
        assertEquals("The video could not be read, so it was not sent.", WebPolicy.unreadableVideos(1))
        assertEquals("2 videos could not be read, so they were not sent.", WebPolicy.unreadableVideos(2))
    }
}
