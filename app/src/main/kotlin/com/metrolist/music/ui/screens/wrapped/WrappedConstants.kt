/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.wrapped

import java.time.LocalDate
import java.time.Month

object WrappedConstants {
    /** Wrapped is shown in December and January, so January still refers to the year that just ended. */
    val YEAR: Int get() = seasonYear(LocalDate.now())
    val PLAYLIST_NAME: String get() = "Interlude $YEAR"

    fun seasonYear(today: LocalDate): Int = if (today.month == Month.JANUARY) today.year - 1 else today.year

    fun isSeason(today: LocalDate): Boolean = today.month == Month.DECEMBER || today.month == Month.JANUARY
}
