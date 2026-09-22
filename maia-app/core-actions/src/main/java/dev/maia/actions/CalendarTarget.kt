package dev.maia.actions

/**
 * Where a commit will land, and whether anybody chose it.
 *
 * The distinction is the whole point of the type. Section 9 of the M1 brief
 * settled the open question this way: when the user has never picked, guess
 * the most plausible calendar and say on the card that it was guessed, rather
 * than blocking the commit behind a chooser nobody asked for. That only works
 * if the guess is distinguishable from a choice afterwards, which is what
 * [chosen] records.
 */
data class CalendarTarget(
    val calendar: MaiaCalendar,
    val chosen: Boolean,
) {
    val guessed: Boolean get() = !chosen
}
