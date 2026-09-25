package com.pocketide.rooms

/**
 * Line-level edits of a TOML file that keep everything else exactly as written: the owner's
 * keys, tables, comments and blank lines. Enough TOML is understood to find where each key and
 * table is (multi-line strings and arrays, quoted and dotted keys); values are not interpreted.
 */
internal class TomlDocument(text: String) {
    private val lines: MutableList<String> = text.removePrefix("﻿").replace("\r\n", "\n").removeSuffix("\n").let {
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

    /**
     * What is defined under [prefix], one setting per name after it (a server, a hook event), in
     * the order the names first appear; a name of "" is [prefix] written whole as one value
     * (`mcp_servers = { … }`). Each comes as TOML text that defines it on its own, wherever the
     * file wrote its parts: keys written outside its own tables first, in full (`a.b.c = …`),
     * then its tables with their keys as written. Comments and blank lines are left out.
     */
    fun settings(prefix: List<String>): List<Pair<String, String>> {
        val keys = LinkedHashMap<String, MutableList<String>>()
        val tables = LinkedHashMap<String, MutableList<String>>()
        for (statement in statements()) {
            if (statement.path != prefix && !startsWith(statement.path, prefix)) continue
            // A table that only holds the settings, like [mcp_servers], is not one itself.
            if (statement.header && statement.path == prefix) continue
            val name = statement.path.getOrNull(prefix.size).orEmpty()
            keys.getOrPut(name) { mutableListOf() }
            val ownTable = statement.header || (statement.table.size > prefix.size && startsWith(statement.table, prefix))
            if (ownTable) {
                tables.getOrPut(name) { mutableListOf() } += (statement.first..statement.last).map { lines[it].trimEnd() }
            } else {
                keys.getValue(name) += "${written(statement.path)} = ${valueText(statement)}"
            }
        }
        return keys.map { (name, lines) -> name to (lines + tables[name].orEmpty()).joinToString("\n") }
    }

    /** Adds [fragment], as [settings] gives it: its keys at the top level, its tables at the end. */
    fun add(fragment: String) {
        val incoming = TomlDocument(fragment)
        val split = incoming.statements().firstOrNull { it.header }?.first ?: incoming.lines.size
        val keys = incoming.lines.subList(0, split).filter { it.isNotBlank() }
        val tables = incoming.lines.subList(split, incoming.lines.size)
        if (keys.isNotEmpty()) {
            val at = statements().firstOrNull { it.header }?.first?.let(::endOfBlankRun) ?: lines.size
            lines.addAll(at, keys)
        }
        if (tables.isNotEmpty()) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            if (lines.isNotEmpty()) lines.add("")
            lines.addAll(tables)
        }
    }

    /** The value of a key/value [statement] as written, over all its lines, without the key. */
    private fun valueText(statement: Statement): String {
        val first = lines[statement.first]
        val rest = (statement.first + 1..statement.last).map { lines[it] }
        return (listOf(first.substring(valueStart(first)).trim()) + rest).joinToString("\n").trimEnd()
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

    /**
     * False when a line is neither blank, a comment, a header nor a key and value this reader can
     * place, or a value runs unclosed to the end of the file. The agent's own TOML reader might
     * place what such a file holds elsewhere, where a setting could hide, so a file like that is
     * not edited line by line.
     */
    fun understood(): Boolean = scan().second

    private fun statements(): List<Statement> = scan().first

    private fun scan(): Pair<List<Statement>, Boolean> {
        val found = mutableListOf<Statement>()
        var understood = true
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
                    } else {
                        understood = false
                    }
                    i++
                }
                else -> {
                    val key = keyPath(trimmed)
                    val (last, closed) = valueEnd(i, trimmed.substring(valueStart(trimmed)))
                    if (key != null) found += Statement(i, last, header = false, path = table + key, table = table)
                    if (key == null || !closed) understood = false
                    i = last + 1
                }
            }
        }
        return found to understood
    }

    /**
     * The last line of a value that starts on line [start], following multi-line strings and
     * brackets as a TOML reader does: an escaped quote does not end a multi-line basic string, and
     * a run of up to five quotes ends one (the extra ones belong to the string). False with it
     * when the value is still open at the end of the file.
     */
    private fun valueEnd(start: Int, firstValue: String): Pair<Int, Boolean> {
        var depth = 0
        var multiline: Char? = null
        var line = start
        var text = firstValue
        while (true) {
            var i = 0
            while (i < text.length) {
                val quote = multiline
                if (quote != null) {
                    when {
                        quote == '"' && text[i] == '\\' -> i += 2
                        text[i] == quote -> {
                            var run = 0
                            while (i + run < text.length && text[i + run] == quote) run++
                            if (run >= 3) multiline = null
                            i += run
                        }
                        else -> i++
                    }
                    continue
                }
                val c = text[i]
                when {
                    text.startsWith("\"\"\"", i) || text.startsWith("'''", i) -> {
                        multiline = c
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
            if (depth <= 0 && multiline == null) return line to true
            if (line + 1 >= lines.size) return line to false
            line++
            text = lines[line]
        }
    }

    /**
     * The value of the top-level [key] as written (a string keeps its quotes), without a trailing
     * comment; null when it is not set on one line of its own.
     */
    fun topLevelValue(key: String): String? {
        val statement = statements().firstOrNull { !it.header && it.table.isEmpty() && it.path == listOf(key) } ?: return null
        if (statement.last != statement.first) return null
        val value = lines[statement.first].substringAfter('=').trim()
        val end = when {
            value.startsWith("\"") -> skipBasicString(value, 0)
            value.startsWith("'") -> value.indexOf('\'', 1).let { if (it < 0) value.length else it + 1 }
            else -> value.indexOf('#').let { if (it < 0) value.length else it }
        }
        return value.substring(0, end).trim()
    }

    /** Removes the top-level [key] (not a table of that name). */
    fun removeTopLevel(key: String) {
        val statements = statements()
        rewrite(statements, statements.filter { !it.header && it.table.isEmpty() && it.path == listOf(key) }, null, null)
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
            val equals = equalsAt(line)
            if (equals < 0) return null
            return dotted(line.substring(0, equals))
        }

        /** Where the value starts on a key/value line: just after its `=`. */
        private fun valueStart(line: String): Int = equalsAt(line).let { if (it < 0) line.length else it + 1 }

        /** The `=` after a key, outside the key's quotes; -1 when there is none. */
        private fun equalsAt(line: String): Int {
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
            return if (i >= line.length) -1 else i
        }

        /** The parts of a dotted key; null when one is empty without quotes (`a..b`), which TOML refuses. */
        private fun dotted(text: String): List<String>? {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var quoted = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '"' -> {
                        quoted = true
                        i++
                        while (i < text.length && text[i] != '"') {
                            if (text[i] == '\\' && i + 1 < text.length) {
                                i = unescape(text, i, current)
                            } else {
                                current.append(text[i])
                                i++
                            }
                        }
                    }
                    c == '\'' -> {
                        quoted = true
                        i++
                        while (i < text.length && text[i] != '\'') current.append(text[i++])
                    }
                    c == '.' -> {
                        parts += part(current, quoted) ?: return null
                        current.clear()
                        quoted = false
                    }
                    else -> current.append(c)
                }
                i++
            }
            parts += part(current, quoted) ?: return null
            return parts
        }

        /** One part of a dotted key; a quoted one may be empty (`""` is a key of its own). */
        private fun part(text: StringBuilder, quoted: Boolean): String? = text.toString().trim().takeIf { it.isNotEmpty() || quoted }

        /**
         * Appends the escape at [at] (a backslash in a quoted key) as a TOML reader reads it, so
         * `"mcp_servers"` is `mcp_servers` here too; returns where the key goes on.
         */
        private fun unescape(text: String, at: Int, out: StringBuilder): Int {
            val c = text[at + 1]
            val simple = when (c) {
                'b' -> '\b'
                't' -> '\t'
                'n' -> '\n'
                'f' -> '\u000C'
                'r' -> '\r'
                'e' -> '\u001B'
                '"' -> '"'
                '\\' -> '\\'
                else -> null
            }
            if (simple != null) {
                out.append(simple)
                return at + 2
            }
            val digits = when (c) {
                'x' -> 2
                'u' -> 4
                'U' -> 8
                else -> 0
            }
            val code = text.takeIf { digits > 0 && at + 2 + digits <= it.length }?.substring(at + 2, at + 2 + digits)?.toIntOrNull(16)
            if (code != null && Character.isValidCodePoint(code)) {
                out.appendCodePoint(code)
                return at + 2 + digits
            }
            // Not TOML: the agent's own reader refuses the whole file.
            out.append(c)
            return at + 2
        }
    }
}
