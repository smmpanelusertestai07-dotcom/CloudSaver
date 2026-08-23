package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStepsTest {

    @Test
    fun `the header number and the step list can never disagree`() {
        // The bug this guards: the header counted from a loop index while each
        // card carried its number in the copy, so "Step 6 of 7" sat above a
        // card headed "5.". One list, one number.
        for ((index, step) in OnboardingSteps.ALL.withIndex()) {
            assertEquals(index + 1, OnboardingSteps.humanNumber(step))
            assertEquals(step, OnboardingSteps.at(index))
        }
    }

    @Test
    fun `the total is the list, not a literal`() {
        assertEquals(OnboardingSteps.ALL.size, OnboardingSteps.TOTAL)
        assertEquals(OnboardingSteps.TOTAL, OnboardingSteps.humanNumber(OnboardingSteps.ALL.last()))
    }

    @Test
    fun `walking off either end stays inside the list`() {
        assertEquals(OnboardingSteps.ALL.first(), OnboardingSteps.previous(OnboardingSteps.ALL.first()))
        assertEquals(OnboardingSteps.ALL.last(), OnboardingSteps.next(OnboardingSteps.ALL.last()))
        assertEquals(OnboardingSteps.ALL.first(), OnboardingSteps.at(-5))
        assertEquals(OnboardingSteps.ALL.last(), OnboardingSteps.at(99))
    }

    @Test
    fun `setup starts at welcome and ends at the test run`() {
        assertEquals(OnboardingSteps.Step.WELCOME, OnboardingSteps.ALL.first())
        assertTrue(OnboardingSteps.isLast(OnboardingSteps.Step.TRY_IT))
    }
}
