package app.entesaver.core.logic

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeftoverRulesTest {

    @Test
    fun `a cancelled dialog does not end the card`() {
        assertFalse(LeftoverRules.allRemoved(listOf("a", "b"), emptyList()))
    }

    @Test
    fun `a partly approved delete does not end the card`() {
        assertFalse(LeftoverRules.allRemoved(listOf("a", "b", "c"), listOf("a", "c")))
    }

    @Test
    fun `every file gone ends the card`() {
        assertTrue(LeftoverRules.allRemoved(listOf("a", "b"), listOf("b", "a")))
    }

    /**
     * The Remove button must pass Android's answer on, not ignore it: a
     * callback that drops its argument is how a Cancel hid the card for good.
     */
    @Test
    fun `the Remove button hands the delete result to the rule`() {
        val home = File("src/main/kotlin/app/entesaver/ui/screens/HomeScreen.kt").readText()
        assertFalse(
            "Remove may not mark the leftovers cleaned whatever Android answered",
            Regex("""requestDelete\(\s*leftoverUris\s*\)\s*\{\s*vm\.onLeftoversCleaned\(\)""")
                .containsMatchIn(home)
        )
        assertTrue(home.contains("vm.onLeftoversRemoveResult("))
        val vm = File("src/main/kotlin/app/entesaver/ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("LeftoverRules.allRemoved("))
    }
}
