package app.cloudsaver.core.logic

/**
 * The one list of setup steps.
 *
 * The header used to count from the loop index while each card carried its
 * number in the copy, so "Step 6 of 7" sat above a card headed "5.". Both now
 * come from here, and nothing in the copy states a position.
 */
object OnboardingSteps {

    enum class Step { WELCOME, MEDIA, NOTIFICATIONS, BATTERY, USAGE, CLOUD, TRY_IT }

    val ALL: List<Step> = Step.entries.toList()

    val TOTAL: Int = ALL.size

    /** The position a person would read out loud: one-based. */
    fun humanNumber(step: Step): Int = ALL.indexOf(step) + 1

    fun at(index: Int): Step = ALL[index.coerceIn(0, TOTAL - 1)]

    fun indexOf(step: Step): Int = ALL.indexOf(step)

    fun next(step: Step): Step = at(indexOf(step) + 1)

    fun previous(step: Step): Step = at(indexOf(step) - 1)

    val isLast: (Step) -> Boolean = { it == ALL.last() }
}
