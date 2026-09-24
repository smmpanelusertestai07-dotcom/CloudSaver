package com.pocketide.ui.work

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
}
