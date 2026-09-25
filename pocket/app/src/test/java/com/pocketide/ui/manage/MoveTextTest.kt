package com.pocketide.ui.manage

import com.pocketide.sync.MoveState
import com.pocketide.ui.components.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveTextTest {
    @Test
    fun `idle says nothing`() {
        assertNull(ManageText.move(MoveState.Idle))
        assertNull(ManageText.moveProgress(MoveState.Idle))
    }

    @Test
    fun `copying counts files and never divides by zero`() {
        val copying = MoveState.Copying("new@example.com", 3, 12)
        assertEquals("Copying to new@example.com: 3 of 12 files.", ManageText.move(copying)?.text)
        assertEquals(0.25f, ManageText.moveProgress(copying))
        val empty = MoveState.Copying("new@example.com", 0, 0)
        assertTrue(ManageText.move(empty)!!.text.startsWith("Getting ready"))
        assertNull(ManageText.moveProgress(empty))
        assertEquals(1f, ManageText.moveProgress(MoveState.Copying("x", 20, 12)))
    }

    @Test
    fun `the old copy is named until the owner erases it`() {
        val ready = ManageText.move(MoveState.ReadyToEraseOld("old@example.com", "new@example.com"))!!
        assertEquals(Tone.OK, ready.tone)
        assertTrue(ready.text.contains("old@example.com"))
        assertTrue(ready.text.contains("new@example.com"))
    }

    @Test
    fun `a failure keeps the engine's sentence, or a general one when it is blank`() {
        assertEquals("Some files did not arrive.", ManageText.move(MoveState.Failed("Some files did not arrive."))?.text)
        assertEquals(PlainError.GENERIC, ManageText.move(MoveState.Failed(" "))?.text)
        assertEquals(Tone.ERROR, ManageText.move(MoveState.Failed("x"))?.tone)
    }
}
