package app.novacalc.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.novacalc.engine.AngleUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal

interface SettingsRepository {
    val settings: Flow<CalculatorSettings>
    val memory: Flow<BigDecimal?>
    val lastExpression: Flow<String>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setHaptics(enabled: Boolean)
    suspend fun setKeepScreenOn(enabled: Boolean)
    suspend fun setAngleUnit(unit: AngleUnit)
    suspend fun setMaxFractionDigits(digits: Int)
    suspend fun setGrouping(enabled: Boolean)
    suspend fun setMemory(value: BigDecimal?)
    suspend fun setLastExpression(expression: String)
}

interface HistoryRepository {
    val history: Flow<List<HistoryEntry>>
    suspend fun add(expression: String, expressionLiteral: String, result: String, resultLiteral: String)
    suspend fun delete(id: Long)
    suspend fun clear()
}

private val Context.novaDataStore: DataStore<Preferences> by preferencesDataStore(name = "novacalc")

/** One Preferences DataStore backs both repositories; it is process-wide and crash-safe. */
fun Context.appDataStore(): DataStore<Preferences> = applicationContext.novaDataStore

private fun Flow<Preferences>.safe(): Flow<Preferences> = catch { e ->
    if (e is IOException) emit(emptyPreferences()) else throw e
}

class DataStoreSettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsRepository {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val HAPTICS = booleanPreferencesKey("haptics")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val ANGLE = stringPreferencesKey("angle_unit")
        val FRACTION_DIGITS = intPreferencesKey("max_fraction_digits")
        val GROUPING = booleanPreferencesKey("grouping")
        val MEMORY = stringPreferencesKey("memory")
        val LAST_EXPRESSION = stringPreferencesKey("last_expression")
    }

    override val settings: Flow<CalculatorSettings> = dataStore.data.safe().map { p ->
        val defaults = CalculatorSettings()
        CalculatorSettings(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.themeMode,
            dynamicColor = p[Keys.DYNAMIC] ?: defaults.dynamicColor,
            haptics = p[Keys.HAPTICS] ?: defaults.haptics,
            keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            angleUnit = p[Keys.ANGLE]?.let { runCatching { AngleUnit.valueOf(it) }.getOrNull() } ?: defaults.angleUnit,
            maxFractionDigits = (p[Keys.FRACTION_DIGITS] ?: defaults.maxFractionDigits).coerceIn(0, 12),
            grouping = p[Keys.GROUPING] ?: defaults.grouping,
        )
    }

    override val memory: Flow<BigDecimal?> = dataStore.data.safe().map { p -> p[Keys.MEMORY]?.toBigDecimalOrNull() }

    override val lastExpression: Flow<String> = dataStore.data.safe().map { p -> p[Keys.LAST_EXPRESSION].orEmpty() }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    override suspend fun setThemeMode(mode: ThemeMode) = put(Keys.THEME, mode.name)
    override suspend fun setDynamicColor(enabled: Boolean) = put(Keys.DYNAMIC, enabled)
    override suspend fun setHaptics(enabled: Boolean) = put(Keys.HAPTICS, enabled)
    override suspend fun setKeepScreenOn(enabled: Boolean) = put(Keys.KEEP_SCREEN_ON, enabled)
    override suspend fun setAngleUnit(unit: AngleUnit) = put(Keys.ANGLE, unit.name)
    override suspend fun setMaxFractionDigits(digits: Int) = put(Keys.FRACTION_DIGITS, digits.coerceIn(0, 12))
    override suspend fun setGrouping(enabled: Boolean) = put(Keys.GROUPING, enabled)
    override suspend fun setMemory(value: BigDecimal?) {
        dataStore.edit { p -> if (value == null) p.remove(Keys.MEMORY) else p[Keys.MEMORY] = value.toPlainString() }
    }
    override suspend fun setLastExpression(expression: String) = put(Keys.LAST_EXPRESSION, expression)
}

class DataStoreHistoryRepository(private val dataStore: DataStore<Preferences>) : HistoryRepository {

    private val key = stringPreferencesKey("history_json")

    override val history: Flow<List<HistoryEntry>> = dataStore.data.safe().map { p -> parse(p[key]) }

    override suspend fun add(expression: String, expressionLiteral: String, result: String, resultLiteral: String) {
        dataStore.edit { p ->
            val current = parse(p[key])
            val entry = HistoryEntry(
                id = System.currentTimeMillis(),
                expression = expression,
                expressionLiteral = expressionLiteral,
                result = result,
                resultLiteral = resultLiteral,
                timestamp = System.currentTimeMillis(),
            )
            p[key] = serialize((listOf(entry) + current).take(MAX_ENTRIES))
        }
    }

    override suspend fun delete(id: Long) {
        dataStore.edit { p -> p[key] = serialize(parse(p[key]).filterNot { it.id == id }) }
    }

    override suspend fun clear() {
        dataStore.edit { p -> p.remove(key) }
    }

    private fun parse(json: String?): List<HistoryEntry> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                HistoryEntry(
                    id = o.getLong("id"),
                    expression = o.getString("expression"),
                    expressionLiteral = o.optString("exprLiteral", ""),
                    result = o.getString("result"),
                    resultLiteral = o.optString("literal", o.getString("result")),
                    timestamp = o.getLong("time"),
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun serialize(entries: List<HistoryEntry>): String {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject()
                    .put("id", e.id)
                    .put("expression", e.expression)
                    .put("exprLiteral", e.expressionLiteral)
                    .put("result", e.result)
                    .put("literal", e.resultLiteral)
                    .put("time", e.timestamp)
            )
        }
        return array.toString()
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}
