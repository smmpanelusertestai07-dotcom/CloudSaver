package com.pocketide.docs

/**
 * The fix-it ladder, cheapest first. The Computer screen shows it with a button for each level
 * it can run, and Help lists the same levels as steps, so the two can never disagree.
 */
internal object FixLadder {

    /** The button the Computer screen shows for a level. */
    enum class Action { RESTART_COMPUTER, REPAIR, RESET_COMPUTER }

    /**
     * One level: what it is for and what it costs. A level the Computer screen has no button
     * for ([action] null) says [how] to reach it instead.
     */
    data class Rung(val title: String, val fixes: String, val cost: String, val action: Action? = null, val how: String? = null)

    /** The agent menu's items the first two levels send the owner to; the menu shows these exact words. */
    const val RELOAD_ITEM = "Reload screen"
    const val RESTART_ITEM = "Restart agent"

    val rungs = listOf(
        Rung(
            "Reload the agent screen",
            "For a blank or frozen agent screen.",
            "Takes seconds.",
            how = "The agent's menu → $RELOAD_ITEM.",
        ),
        Rung("Restart the agent", "For an agent that stopped answering.", "The chat is kept.", how = "Its menu → $RESTART_ITEM."),
        Rung(
            "Restart the computer",
            "For several stuck agents.",
            "Every room closes; files, sign-ins and history stay.",
            Action.RESTART_COMPUTER,
        ),
        Rung(
            "Repair",
            "For a missing or broken tool.",
            "Installs what is missing and updates the rest; your files are not touched.",
            Action.REPAIR,
        ),
        Rung(
            "Reset computer",
            "For what Repair could not fix.",
            "A big download; tools you installed are removed, everything of yours stays.",
            Action.RESET_COMPUTER,
        ),
    )

    /** The level's heading on the Computer screen, numbered as in Help. */
    fun heading(index: Int): String = "${index + 1}. ${rungs[index].title}"

    /** What the level is for, how to reach it when it has no button, and what it costs. */
    fun text(rung: Rung): String = listOfNotNull(rung.fixes, rung.how, rung.cost).joinToString(" ")

    /** The level as one step in Help. */
    fun helpStep(rung: Rung): String = "${rung.title}. ${text(rung)}"

    /** Help's line before the steps: the order, and where the buttons are. */
    val helpIntro: String
        get() {
            val buttons = rungs.indices.filter { rungs[it].action != null }
            return "Start at the top. Steps ${buttons.first() + 1} to ${buttons.last() + 1} are buttons on the Computer screen."
        }
}
