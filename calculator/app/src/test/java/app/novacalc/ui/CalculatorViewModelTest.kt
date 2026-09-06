package app.novacalc.ui

import app.novacalc.data.CalculatorSettings
import app.novacalc.data.HistoryEntry
import app.novacalc.data.HistoryRepository
import app.novacalc.data.SettingsRepository
import app.novacalc.data.ThemeMode
import app.novacalc.engine.AngleUnit
import app.novacalc.engine.CalcException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class CalculatorViewModelTest {

    private class FakeSettings : SettingsRepository {
        val state = MutableStateFlow(CalculatorSettings())
        val memoryState = MutableStateFlow<BigDecimal?>(null)
        val last = MutableStateFlow("")
        override val settings: Flow<CalculatorSettings> = state
        override val memory: Flow<BigDecimal?> = memoryState
        override val lastExpression: Flow<String> = last
        override suspend fun setThemeMode(mode: ThemeMode) = state.update { it.copy(themeMode = mode) }
        override suspend fun setDynamicColor(enabled: Boolean) = state.update { it.copy(dynamicColor = enabled) }
        override suspend fun setHaptics(enabled: Boolean) = state.update { it.copy(haptics = enabled) }
        override suspend fun setKeepScreenOn(enabled: Boolean) = state.update { it.copy(keepScreenOn = enabled) }
        override suspend fun setAngleUnit(unit: AngleUnit) = state.update { it.copy(angleUnit = unit) }
        override suspend fun setMaxFractionDigits(digits: Int) = state.update { it.copy(maxFractionDigits = digits) }
        override suspend fun setGrouping(enabled: Boolean) = state.update { it.copy(grouping = enabled) }
        override suspend fun setMemory(value: BigDecimal?) { memoryState.value = value }
        override suspend fun setLastExpression(expression: String) { last.value = expression }
    }

    private class FakeHistory : HistoryRepository {
        val entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
        override val history: Flow<List<HistoryEntry>> = entries.map { it }
        private var nextId = 1L
        override suspend fun add(expression: String, expressionLiteral: String, result: String, resultLiteral: String) {
            entries.update { listOf(HistoryEntry(nextId++, expression, expressionLiteral, result, resultLiteral, 0L)) + it }
        }
        override suspend fun delete(id: Long) = entries.update { list -> list.filterNot { it.id == id } }
        override suspend fun clear() { entries.value = emptyList() }
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var settings: FakeSettings
    private lateinit var history: FakeHistory
    private lateinit var vm: CalculatorViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        settings = FakeSettings()
        history = FakeHistory()
        vm = CalculatorViewModel(settings, history)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.subscribe() {
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
    }

    private fun TestScope.press(vararg actions: CalcAction) {
        actions.forEach { vm.onAction(it) }
        advanceUntilIdle()
    }

    private fun digits(s: String): Array<CalcAction> = s.map { CalcAction.Digit(it) }.toTypedArray()

    @Test fun typingShowsExpressionAndLivePreview() = runTest(dispatcher) {
        subscribe()
        press(*digits("12"), CalcAction.Operator('+'), *digits("30"))
        assertEquals("12 + 30", vm.uiState.value.expression)
        assertEquals("42", vm.uiState.value.preview)
        assertNull(vm.uiState.value.result)
    }

    @Test fun equalsCommitsResultAndHistory() = runTest(dispatcher) {
        subscribe()
        press(*digits("7"), CalcAction.Operator('×'), *digits("8"), CalcAction.Equals)
        assertEquals("56", vm.uiState.value.result)
        assertEquals(1, vm.uiState.value.history.size)
        assertEquals("7 × 8", vm.uiState.value.history[0].expression)
        assertEquals("56", vm.uiState.value.history[0].result)
    }

    @Test fun afterResultOperatorContinuesAndDigitStartsFresh() = runTest(dispatcher) {
        subscribe()
        press(*digits("7"), CalcAction.Operator('+'), *digits("8"), CalcAction.Equals)
        press(CalcAction.Operator('×'), *digits("2"))
        assertEquals("15 × 2", vm.uiState.value.expression)
        assertEquals("30", vm.uiState.value.preview)
        press(CalcAction.Equals, *digits("4"))
        assertEquals("4", vm.uiState.value.expression)
        assertNull(vm.uiState.value.result)
    }

    @Test fun divideByZeroShowsErrorAndRecovers() = runTest(dispatcher) {
        subscribe()
        press(*digits("5"), CalcAction.Operator('÷'), *digits("0"), CalcAction.Equals)
        assertEquals(CalcException.Kind.DIVIDE_BY_ZERO, vm.uiState.value.error)
        assertEquals(0, vm.uiState.value.history.size)
        press(CalcAction.Backspace)
        assertNull(vm.uiState.value.error)
        assertEquals("5 ÷", vm.uiState.value.expression)
    }

    @Test fun clearAndBackspace() = runTest(dispatcher) {
        subscribe()
        press(*digits("123"), CalcAction.Backspace)
        assertEquals("12", vm.uiState.value.expression)
        press(CalcAction.Clear)
        assertEquals("", vm.uiState.value.expression)
        press(*digits("9"), CalcAction.Operator('+'), *digits("1"), CalcAction.Equals, CalcAction.Backspace)
        assertEquals("", vm.uiState.value.expression)
        assertNull(vm.uiState.value.result)
    }

    @Test fun memoryOperations() = runTest(dispatcher) {
        subscribe()
        press(*digits("25"), CalcAction.MemoryStore)
        assertEquals("25", vm.uiState.value.memory)
        press(CalcAction.Clear, *digits("5"), CalcAction.MemoryAdd)
        assertEquals("30", vm.uiState.value.memory)
        press(CalcAction.MemorySubtract)
        assertEquals("25", vm.uiState.value.memory)
        press(CalcAction.Clear, *digits("2"), CalcAction.Operator('×'), CalcAction.MemoryRecall)
        assertEquals("2 × 25", vm.uiState.value.expression)
        assertEquals("50", vm.uiState.value.preview)
        press(CalcAction.MemoryClear)
        assertNull(vm.uiState.value.memory)
    }

    @Test fun scientificFunctionsAndInverse() = runTest(dispatcher) {
        subscribe()
        press(CalcAction.Function("sin"), *digits("30"), CalcAction.Equals)
        assertEquals("0.5", vm.uiState.value.result)
        press(CalcAction.Inverse)
        assertTrue(vm.uiState.value.inverse)
        press(CalcAction.Function("asin"), *digits("1"), CalcAction.Equals)
        assertFalse(vm.uiState.value.inverse)
        assertEquals("90", vm.uiState.value.result)
        press(CalcAction.ToggleAngleUnit)
        assertEquals(AngleUnit.RADIANS, vm.uiState.value.angleUnit)
        press(CalcAction.Clear, CalcAction.Function("cos"), CalcAction.Constant('π'), CalcAction.Equals)
        assertEquals("−1", vm.uiState.value.result)
    }

    @Test fun percentFactorialPowerAndConstants() = runTest(dispatcher) {
        subscribe()
        press(*digits("200"), CalcAction.Operator('+'), *digits("10"), CalcAction.Postfix("%"), CalcAction.Equals)
        assertEquals("220", vm.uiState.value.result)
        press(*digits("5"), CalcAction.Postfix("!"), CalcAction.Equals)
        assertEquals("120", vm.uiState.value.result)
        press(*digits("2"), CalcAction.Operator('^'), *digits("10"), CalcAction.Equals)
        assertEquals("1,024", vm.uiState.value.result)
        press(CalcAction.Inverse, CalcAction.TenPower, *digits("3"), CalcAction.Equals)
        assertEquals("1,000", vm.uiState.value.result)
        press(*digits("2"), CalcAction.Constant('π'), CalcAction.Equals)
        assertEquals("6.2831853072", vm.uiState.value.result)
    }

    @Test fun settingsChangeFormatting() = runTest(dispatcher) {
        subscribe()
        press(*digits("1"), CalcAction.Operator('÷'), *digits("3"), CalcAction.Equals)
        assertEquals("0.3333333333", vm.uiState.value.result)
        vm.setMaxFractionDigits(2); advanceUntilIdle()
        assertEquals("0.33", vm.uiState.value.result)
        press(*digits("1234"), CalcAction.Operator('×'), *digits("1000"))
        assertEquals("1,234,000", vm.uiState.value.preview)
        vm.setGrouping(false); advanceUntilIdle()
        assertEquals("1234000", vm.uiState.value.preview)
        assertEquals("1234 × 1000", vm.uiState.value.expression)
        vm.setThemeMode(ThemeMode.DARK); vm.setHaptics(false); advanceUntilIdle()
        assertEquals(ThemeMode.DARK, vm.uiState.value.settings.themeMode)
        assertFalse(vm.uiState.value.settings.haptics)
    }

    @Test fun historyReuseDeleteAndClear() = runTest(dispatcher) {
        subscribe()
        press(*digits("6"), CalcAction.Operator('×'), *digits("7"), CalcAction.Equals)
        press(*digits("1"), CalcAction.Operator('+'), *digits("1"), CalcAction.Equals)
        assertEquals(2, vm.uiState.value.history.size)
        val entry = vm.uiState.value.history[1]
        vm.useHistoryResult(entry); advanceUntilIdle()
        assertEquals("42", vm.uiState.value.expression)
        vm.useHistoryExpression(entry); advanceUntilIdle()
        assertEquals("6 × 7", vm.uiState.value.expression)
        vm.deleteHistoryEntry(entry.id); advanceUntilIdle()
        assertEquals(1, vm.uiState.value.history.size)
        vm.clearHistory(); advanceUntilIdle()
        assertEquals(0, vm.uiState.value.history.size)
    }

    @Test fun pasteAndCopyText() = runTest(dispatcher) {
        subscribe()
        assertTrue(vm.paste("12*(3+4)")); advanceUntilIdle()
        assertEquals("12 × (3 + 4)", vm.uiState.value.expression)
        assertEquals("84", vm.uiState.value.copyText)
        assertFalse(vm.paste("hello"))
        press(CalcAction.Equals)
        assertEquals("84", vm.uiState.value.copyText)
    }

    @Test fun lastExpressionIsRestored() = runTest(dispatcher) {
        settings.last.value = "2+3"
        vm = CalculatorViewModel(settings, history)
        subscribe()
        assertEquals("2 + 3", vm.uiState.value.expression)
        assertEquals("5", vm.uiState.value.preview)
    }

    @Test fun scientificToggleAndParentheses() = runTest(dispatcher) {
        subscribe()
        press(CalcAction.ToggleScientific)
        assertTrue(vm.uiState.value.scientific)
        press(CalcAction.SmartParenthesis, *digits("2"), CalcAction.Operator('+'), *digits("3"), CalcAction.SmartParenthesis, CalcAction.Postfix("²"), CalcAction.Equals)
        assertEquals("25", vm.uiState.value.result)
        press(CalcAction.ToggleSign)
        assertEquals("−25", vm.uiState.value.expression)
    }
}
