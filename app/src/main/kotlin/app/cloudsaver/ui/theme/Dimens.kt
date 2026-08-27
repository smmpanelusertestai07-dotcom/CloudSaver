package app.cloudsaver.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing rhythm, in one place.
 *
 * Screens written months apart drift: 14 dp here, 18 dp there, and nothing
 * lines up when two of them sit next to each other. These are the only
 * spacings the app uses, so the grid is the same on every screen and a new
 * screen has nothing to invent.
 */
object Dimens {
    /** Screen edge to content. */
    val Screen = 16.dp

    /** Between cards inside one group. */
    val CardGap = 12.dp

    /** Between one group of cards and the next. */
    val GroupGap = 24.dp

    /** Inside a card, edge to content. */
    val CardPadding = 16.dp

    /**
     * The floor for a list row, and separately for anything tappable.
     *
     * They differ on purpose: a row may be taller than the minimum and often
     * is, but nothing interactive may be smaller than the touch target, which
     * is a physical constraint about fingers rather than a visual one.
     */
    val RowMin = 56.dp
    val TouchTarget = 48.dp

    /** Corner radii: cards, and the smaller controls inside them. */
    val CardCorner = 20.dp
    val ControlCorner = 12.dp

    /**
     * The widest the content of any screen is allowed to get.
     *
     * Every phone is narrower than this, in either orientation, so on a phone
     * it does nothing whatsoever - which is the point: the app is not one
     * layout on a small screen and a different one on a large screen. It only
     * takes effect where a screen is wide enough that a single column of text
     * would run the full width of the glass, on a tablet or a foldable opened
     * flat, where a line that long is genuinely hard to read. There the
     * content simply centres itself and keeps the proportions it has
     * everywhere else.
     *
     * 600 dp is where Android's own breakpoints put the boundary between a
     * phone-shaped layout and a larger one, so it is the width at which this
     * app stops being asked to fill the screen.
     */
    val ContentMaxWidth = 600.dp
}
