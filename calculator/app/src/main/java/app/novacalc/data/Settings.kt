package app.novacalc.data

import app.novacalc.engine.AngleUnit

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class CalculatorSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = false,
    val angleUnit: AngleUnit = AngleUnit.DEGREES,
    val maxFractionDigits: Int = 10,
    val grouping: Boolean = true,
)

data class HistoryEntry(
    val id: Long,
    /** Pretty expression as it was shown, e.g. "12 × (3 + 4)". */
    val expression: String,
    /** Engine-readable expression, used to load the entry back into the editor. */
    val expressionLiteral: String,
    /** Pretty result as it was shown, e.g. "84". */
    val result: String,
    /** Engine-readable result literal, used when the entry is reused. */
    val resultLiteral: String,
    val timestamp: Long,
)
