package app.novacalc.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.novacalc.AppContainer
import app.novacalc.data.CalculatorSettings
import app.novacalc.data.HistoryEntry
import app.novacalc.data.HistoryRepository
import app.novacalc.data.SettingsRepository
import app.novacalc.data.ThemeMode
import app.novacalc.engine.AngleUnit
import app.novacalc.engine.CalcException
import app.novacalc.engine.CalculatorEditor
import app.novacalc.engine.Evaluator
import app.novacalc.engine.InputToken
import app.novacalc.engine.NumberFormatter
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** Everything a key can do. The keypad maps labels to these; the view model applies them. */
sealed interface CalcAction {
    data class Digit(val digit: Char) : CalcAction
    data object DecimalPoint : CalcAction
    data class Operator(val symbol: Char) : CalcAction
    data class Function(val name: String) : CalcAction
    data object SmartParenthesis : CalcAction
    data object OpenParenthesis : CalcAction
    data object CloseParenthesis : CalcAction
    data class Constant(val symbol: Char) : CalcAction
    data class Postfix(val symbol: String) : CalcAction
    data object TenPower : CalcAction
    data object ToggleSign : CalcAction
    data object Backspace : CalcAction
    data object Clear : CalcAction
    data object Equals : CalcAction
    data object Inverse : CalcAction
    data object ToggleAngleUnit : CalcAction
    data object ToggleScientific : CalcAction
    data object MemoryClear : CalcAction
    data object MemoryRecall : CalcAction
    data object MemoryAdd : CalcAction
    data object MemorySubtract : CalcAction
    data object MemoryStore : CalcAction
}

data class CalculatorUiState(
    /** Pretty expression as typed. */
    val expression: String = "",
    /** Live result of the expression so far, or empty. */
    val preview: String = "",
    /** Committed result after '=', or null while typing. */
    val result: String? = null,
    val error: CalcException.Kind? = null,
    /** Formatted memory value, or null when memory is empty. */
    val memory: String? = null,
    val inverse: Boolean = false,
    val scientific: Boolean = false,
    val angleUnit: AngleUnit = AngleUnit.DEGREES,
    val history: List<HistoryEntry> = emptyList(),
    val settings: CalculatorSettings = CalculatorSettings(),
) {
    val hasInput: Boolean get() = expression.isNotEmpty()
    /** What "copy" puts on the clipboard. */
    val copyText: String?
        get() = result ?: preview.ifEmpty { expression.takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() } } }
}

@OptIn(FlowPreview::class)
class CalculatorViewModel(
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    private data class Internal(
        val tokens: List<InputToken> = emptyList(),
        val result: BigDecimal? = null,
        val error: CalcException.Kind? = null,
        val inverse: Boolean = false,
        val scientific: Boolean = false,
    )

    private val editor = CalculatorEditor()
    private val internal = MutableStateFlow(Internal())
    private val expressionToPersist = MutableStateFlow<String?>(null)

    @Volatile private var latestSettings = CalculatorSettings()
    @Volatile private var latestMemory: BigDecimal? = null

    val uiState: StateFlow<CalculatorUiState> = combine(
        internal, settingsRepository.settings, settingsRepository.memory, historyRepository.history,
    ) { st, settings, memory, history ->
        latestSettings = settings
        latestMemory = memory
        val expression = CalculatorEditor.displayText(st.tokens, settings.grouping)
        val preview = if (st.result == null && st.error == null && CalculatorEditor.hasOperation(st.tokens)) {
            previewOf(st.tokens, settings)
        } else ""
        CalculatorUiState(
            expression = expression,
            preview = preview,
            result = st.result?.let { NumberFormatter.format(it, settings.maxFractionDigits, settings.grouping) },
            error = st.error,
            memory = memory?.let { NumberFormatter.format(it, settings.maxFractionDigits, settings.grouping) },
            inverse = st.inverse,
            scientific = st.scientific,
            angleUnit = settings.angleUnit,
            history = history,
            settings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalculatorUiState())

    init {
        viewModelScope.launch {
            val saved = settingsRepository.lastExpression.first()
            if (saved.isNotEmpty() && editor.isEmpty && internal.value.tokens.isEmpty()) {
                editor.replaceWith(CalculatorEditor.fromEngineString(saved))
                internal.update { it.copy(tokens = editor.snapshot()) }
            }
        }
        viewModelScope.launch {
            expressionToPersist.debounce(400).collect { expr ->
                if (expr != null) settingsRepository.setLastExpression(expr)
            }
        }
    }

    private fun previewOf(tokens: List<InputToken>, settings: CalculatorSettings): String {
        val expr = CalculatorEditor.engineExpression(tokens)
        if (expr.isBlank()) return ""
        return try {
            NumberFormatter.format(Evaluator(settings.angleUnit).evaluate(expr), settings.maxFractionDigits, settings.grouping)
        } catch (e: CalcException) {
            ""
        } catch (e: ArithmeticException) {
            ""
        }
    }

    fun onAction(action: CalcAction) {
        val st = internal.value
        when (action) {
            is CalcAction.Digit -> edit(st, startsFresh = true) { editor.digit(action.digit) }
            CalcAction.DecimalPoint -> edit(st, startsFresh = true) { editor.decimalPoint() }
            is CalcAction.Operator -> edit(st, startsFresh = false) { editor.operator(action.symbol) }
            is CalcAction.Function -> edit(st, startsFresh = true, usesInverse = true) { editor.function(action.name) }
            CalcAction.SmartParenthesis -> edit(st, startsFresh = true) { editor.smartParenthesis() }
            CalcAction.OpenParenthesis -> edit(st, startsFresh = true) { editor.openParenthesis() }
            CalcAction.CloseParenthesis -> edit(st, startsFresh = false) { editor.closeParenthesis() }
            is CalcAction.Constant -> edit(st, startsFresh = true) { editor.constant(action.symbol) }
            is CalcAction.Postfix -> edit(st, startsFresh = false) { editor.postfix(action.symbol) }
            CalcAction.TenPower -> edit(st, startsFresh = true, usesInverse = true) { editor.tenPower() }
            CalcAction.ToggleSign -> edit(st, startsFresh = false) { editor.toggleSign() }
            CalcAction.Backspace -> {
                if (st.result != null || st.error != null && st.tokens.isEmpty()) editor.clear() else editor.backspace()
                publish(clearInverse = false)
            }
            CalcAction.Clear -> {
                editor.clear()
                publish(clearInverse = false)
            }
            CalcAction.Equals -> equals()
            CalcAction.Inverse -> internal.update { it.copy(inverse = !it.inverse) }
            CalcAction.ToggleScientific -> internal.update { it.copy(scientific = !it.scientific) }
            CalcAction.ToggleAngleUnit -> viewModelScope.launch {
                val next = if (latestSettings.angleUnit == AngleUnit.DEGREES) AngleUnit.RADIANS else AngleUnit.DEGREES
                settingsRepository.setAngleUnit(next)
            }
            CalcAction.MemoryClear -> setMemory(null)
            CalcAction.MemoryStore -> currentValue()?.let { setMemory(it) }
            CalcAction.MemoryAdd -> currentValue()?.let { setMemory((latestMemory ?: BigDecimal.ZERO).add(it, Evaluator.MC)) }
            CalcAction.MemorySubtract -> currentValue()?.let { setMemory((latestMemory ?: BigDecimal.ZERO).subtract(it, Evaluator.MC)) }
            CalcAction.MemoryRecall -> latestMemory?.let { m -> edit(st, startsFresh = true) { editor.literal(NumberFormatter.toLiteral(m)) } }
        }
    }

    /** Loads a history entry's result into the expression. */
    fun useHistoryResult(entry: HistoryEntry) {
        edit(internal.value, startsFresh = true) { editor.literal(entry.resultLiteral) }
    }

    /** Replaces the expression with a history entry's expression. */
    fun useHistoryExpression(entry: HistoryEntry) {
        val tokens = CalculatorEditor.fromEngineString(entry.expressionLiteral)
        if (tokens.isEmpty()) return useHistoryResult(entry)
        editor.replaceWith(tokens)
        publish(clearInverse = false)
    }

    /** Appends clipboard text; returns false when it is not a readable expression. */
    fun paste(text: String): Boolean {
        val tokens = CalculatorEditor.fromEngineString(text.trim())
        if (tokens.isEmpty()) return false
        var ok = false
        edit(internal.value, startsFresh = true) { ok = editor.append(tokens) }
        return ok
    }

    fun deleteHistoryEntry(id: Long) { viewModelScope.launch { historyRepository.delete(id) } }
    fun clearHistory() { viewModelScope.launch { historyRepository.clear() } }

    fun setThemeMode(mode: ThemeMode) { viewModelScope.launch { settingsRepository.setThemeMode(mode) } }
    fun setDynamicColor(enabled: Boolean) { viewModelScope.launch { settingsRepository.setDynamicColor(enabled) } }
    fun setHaptics(enabled: Boolean) { viewModelScope.launch { settingsRepository.setHaptics(enabled) } }
    fun setKeepScreenOn(enabled: Boolean) { viewModelScope.launch { settingsRepository.setKeepScreenOn(enabled) } }
    fun setAngleUnit(unit: AngleUnit) { viewModelScope.launch { settingsRepository.setAngleUnit(unit) } }
    fun setMaxFractionDigits(digits: Int) { viewModelScope.launch { settingsRepository.setMaxFractionDigits(digits) } }
    fun setGrouping(enabled: Boolean) { viewModelScope.launch { settingsRepository.setGrouping(enabled) } }

    // ---- internals -----------------------------------------------------------

    /**
     * Applies an edit. After '=', typing a value starts a fresh expression while an
     * operator continues from the result, as on every physical calculator.
     */
    private fun edit(st: Internal, startsFresh: Boolean, usesInverse: Boolean = false, block: () -> Unit) {
        if (st.result != null) {
            if (startsFresh) editor.clear()
            else editor.replaceWith(listOf(InputToken.Number(NumberFormatter.toLiteral(st.result))))
        }
        block()
        publish(clearInverse = usesInverse)
    }

    private fun publish(clearInverse: Boolean) {
        val tokens = editor.snapshot()
        internal.update {
            it.copy(tokens = tokens, result = null, error = null, inverse = if (clearInverse) false else it.inverse)
        }
        expressionToPersist.value = CalculatorEditor.engineExpression(tokens)
    }

    private fun equals() {
        val st = internal.value
        if (st.result != null) return
        val tokens = editor.snapshot()
        val expr = CalculatorEditor.engineExpression(tokens)
        if (expr.isBlank()) return
        val settings = latestSettings
        try {
            val value = Evaluator(settings.angleUnit).evaluate(expr)
            internal.update { it.copy(result = value, error = null) }
            if (CalculatorEditor.hasOperation(tokens)) {
                val shown = NumberFormatter.format(value, settings.maxFractionDigits, settings.grouping)
                val pretty = CalculatorEditor.displayText(tokens, settings.grouping)
                viewModelScope.launch { historyRepository.add(pretty, expr, shown, NumberFormatter.toLiteral(value)) }
            }
            expressionToPersist.value = ""
        } catch (e: CalcException) {
            internal.update { it.copy(error = if (e.kind == CalcException.Kind.EMPTY) CalcException.Kind.SYNTAX else e.kind, result = null) }
        } catch (e: ArithmeticException) {
            internal.update { it.copy(error = CalcException.Kind.OVERFLOW, result = null) }
        }
    }

    private fun currentValue(): BigDecimal? {
        internal.value.result?.let { return it }
        val expr = editor.engineExpression()
        if (expr.isBlank()) return null
        return try {
            Evaluator(latestSettings.angleUnit).evaluate(expr)
        } catch (e: CalcException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    private fun setMemory(value: BigDecimal?) {
        latestMemory = value
        viewModelScope.launch { settingsRepository.setMemory(value) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { CalculatorViewModel(container.settingsRepository, container.historyRepository) }
        }
    }
}
