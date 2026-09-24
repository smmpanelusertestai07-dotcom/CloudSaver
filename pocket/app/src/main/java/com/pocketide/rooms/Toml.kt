package com.pocketide.rooms

/**
 * Line-level edits of a TOML file that keep everything else exactly as written: the owner's
 * keys, tables, comments and blank lines. Enough TOML is understood to find where each key and
 * table is (multi-line strings and arrays, quoted and dotted keys); values are not interpreted.
 */
internal class TomlDocument(text: String) {
    private val lines: MutableList<String> = text.replace("\r\n", "\n").removeSuffix("\n").let {
        if (it.isEmpty()) mutableListOf() else it.split('\n').toMutableList()
    }

    /**
     * One header or key/value, spanning [first]..[last] lines. [path] is the full dotted name;
     * [table] is the header it sits under (empty at the top level).
     */
    private data class Statement(val first: Int, val last: Int, val header: Boolean, val path: List<String>, val table: List<String>)

    fun text(): String = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"

    /** True when a key or table at [path] is defined anywhere. */
    fun has(path: List<String>): Boolean = statements().any { it.path == path || startsWith(it.path, path) }

    /** Sets a top-level key, replacing any definition of it (including dotted ones under it). */
    fun setTopLevel(key: String, value: String) = set(emptyList(), key, value)

    /**
     * Sets [key] in [table]. An existing definition is replaced where it is; other definitions
     * that would clash (a dotted form, an inline table holding it) are removed. A new key goes
     * under the table's header when there is one, else it is written as a top-level dotted key,
     * which is valid whatever other dotted keys exist.
     */
    fun set(table: List<String>, key: String, value: String) {
        val path = table + key
        val statements = statements()
        val target = statements.firstOrNull { !it.header && it.path == path }
        val clashes = statements.filter { it !== target && clashesWith(it, path) }
        rewrite(statements, clashes, target, target?.let { "${written(path.drop(it.table.size))} = $value" })
        if (target != null) return
        val updated = statements()
        val header = updated.firstOrNull { it.header && it.path == table && table.isNotEmpty() }
        val insertAt = if (header != null) {
            val next = updated.firstOrNull { it.header && it.first > header.first }
            updated.filter { !it.header && it.first > header.first && (next == null || it.first < next.first) }
                .maxOfOrNull { it.last + 1 } ?: (header.last + 1)
        } else {
            updated.firstOrNull { it.header }?.first?.let(::endOfBlankRun) ?: lines.size
        }
        lines.add(insertAt, "${written(if (header != null) listOf(key) else path)} = $value")
    }

    /** Replaces the table at [table] (and everything defined under it) with [entries], at the end. */
    fun replaceTable(table: List<String>, entries: List<Pair<String, String>>) {
        removeTable(table)
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
        if (lines.isNotEmpty()) lines.add("")
        lines.add("[${written(table)}]")
        entries.forEach { (name, value) -> lines.add("${key(name)} = $value") }
    }

    fun removeTable(table: List<String>) {
        val statements = statements()
        rewrite(statements, statements.filter { it.path == table || startsWith(it.path, table) }, null, null)
    }

    /** A key at [path] clashes with a statement that defines it, a part of it, or holds it inline. */
    private fun clashesWith(statement: Statement, path: List<String>): Boolean =
        statement.path == path || startsWith(statement.path, path) || (!statement.header && startsWith(path, statement.path))

    /** Removes [doomed] (a header takes the keys under it along) and puts [replacement] in place of [target]. */
    private fun rewrite(statements: List<Statement>, doomed: List<Statement>, target: Statement?, replacement: String?) {
        val removed = HashSet<Int>()
        for (statement in doomed) {
            val end = if (statement.header) {
                statements.firstOrNull { it.header && it.first > statement.first }?.first?.minus(1) ?: (lines.size - 1)
            } else {
                statement.last
            }
            (statement.first..end).forEach { removed += it }
        }
        val kept = mutableListOf<String>()
        lines.forEachIndexed { index, line ->
            when {
                target != null && index in target.first..target.last -> if (index == target.first) kept += replacement.orEmpty()
                index !in removed -> kept += line
            }
        }
        lines.clear()
        lines.addAll(kept)
    }

    private fun written(path: List<String>) = path.joinToString(".") { key(it) }

    /** Just after the last top-level line, so a table's leading comment stays with its header. */
    private fun endOfBlankRun(firstHeader: Int): Int {
        var at = firstHeader
        while (at > 0 && lines[at - 1].trim().let { it.isEmpty() || it.startsWith("#") }) at--
        return at
    }

    private fun statements(): List<Statement> {
        val found = mutableListOf<Statement>()
        var table = emptyList<String>()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            when {
                trimmed.isEmpty() || trimmed.startsWith("#") -> i++
                trimmed.startsWith("[") -> {
                    val name = headerName(trimmed)
                    if (name != null) {
                        table = name
                        found += Statement(i, i, header = true, path = name, table = emptyList())
                    }
                    i++
                }
                else -> {
                    val key = keyPath(trimmed)
                    val last = valueEnd(i, trimmed.substringAfter('=', ""))
                    if (key != null) found += Statement(i, last, header = false, path = table + key, table = table)
                    i = last + 1
                }
            }
        }
        return found
    }

    /** The last line of a value that starts on line [start], following multi-line strings and brackets. */
    private fun valueEnd(start: Int, firstValue: String): Int {
        var depth = 0
        var multiline: String? = null
        var line = start
        var text = firstValue
        while (true) {
            var i = 0
            while (i < text.length) {
                if (multiline != null) {
                    val close = text.indexOf(multiline, i)
                    if (close < 0) {
                        i = text.length
                    } else {
                        i = close + 3
                        multiline = null
                    }
                    continue
                }
                val c = text[i]
                when {
                    text.startsWith("\"\"\"", i) || text.startsWith("'''", i) -> {
                        multiline = text.substring(i, i + 3)
                        i += 3
                    }
                    c == '"' -> i = skipBasicString(text, i)
                    c == '\'' -> i = text.indexOf('\'', i + 1).let { if (it < 0) text.length else it + 1 }
                    c == '#' -> i = text.length
                    c == '[' || c == '{' -> { depth++; i++ }
                    c == ']' || c == '}' -> { depth--; i++ }
                    else -> i++
                }
            }
            if ((depth <= 0 && multiline == null) || line + 1 >= lines.size) return line
            line++
            text = lines[line]
        }
    }

    private fun skipBasicString(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return text.length
    }

    companion object {
        private val BARE = Regex("[A-Za-z0-9_-]+")

        /** A key as TOML writes it: bare when possible, otherwise quoted. */
        fun key(name: String): String = if (BARE.matches(name)) name else string(name)

        fun string(value: String): String = buildString {
            append('"')
            for (c in value) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\t' -> append("\\t")
                    c.code < 0x20 || c.code == 0x7f -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> append(c)
                }
            }
            append('"')
        }

        fun array(values: List<String>): String = values.joinToString(", ", "[", "]") { string(it) }

        fun inlineTable(values: Map<String, String>): String =
            values.entries.joinToString(", ", "{ ", " }") { (name, value) -> "${key(name)} = ${string(value)}" }.let {
                if (values.isEmpty()) "{}" else it
            }

        private fun startsWith(path: List<String>, prefix: List<String>) =
            path.size > prefix.size && path.subList(0, prefix.size) == prefix

        /** `[a.b]` or `[[a.b]]` → [a, b]; null when the line is not a header. */
        private fun headerName(line: String): List<String>? {
            val double = line.startsWith("[[")
            val close = if (double) line.indexOf("]]") else closingBracket(line)
            if (close < 0) return null
            val inner = line.substring(if (double) 2 else 1, close)
            return dotted(inner)
        }

        private fun closingBracket(line: String): Int {
            var i = 1
            while (i < line.length) {
                when (line[i]) {
                    '"' -> {
                        i++
                        while (i < line.length && line[i] != '"') i += if (line[i] == '\\') 2 else 1
                    }
                    '\'' -> {
                        i++
                        while (i < line.length && line[i] != '\'') i++
                    }
                    ']' -> return i
                }
                i++
            }
            return -1
        }

        /** The key before `=` on a key/value line, split at dots outside quotes. */
        private fun keyPath(line: String): List<String>? {
            var i = 0
            while (i < line.length && line[i] != '=') {
                when (line[i]) {
                    '"' -> {
                        i++
                        while (i < line.length && line[i] != '"') i += if (line[i] == '\\') 2 else 1
                    }
                    '\'' -> {
                        i++
                        while (i < line.length && line[i] != '\'') i++
                    }
                }
                i++
            }
            if (i >= line.length) return null
            return dotted(line.substring(0, i))
        }

        private fun dotted(text: String): List<String>? {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '"' -> {
                        i++
                        while (i < text.length && text[i] != '"') {
                            if (text[i] == '\\' && i + 1 < text.length) {
                                current.append(text[i + 1])
                                i += 2
                            } else {
                                current.append(text[i])
                                i++
                            }
                        }
                    }
                    c == '\'' -> {
                        i++
                        while (i < text.length && text[i] != '\'') current.append(text[i++])
                    }
                    c == '.' -> {
                        parts += current.toString().trim()
                        current.clear()
                    }
                    else -> current.append(c)
                }
                i++
            }
            parts += current.toString().trim()
            return parts.takeIf { list -> list.none { it.isEmpty() } }
        }
    }
}
