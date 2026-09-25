package com.pocketide.ui.screens.project

/** What a coloured stretch of source is. */
enum class TokenKind { KEYWORD, STRING, COMMENT, NUMBER }

/** [start] inclusive, [end] exclusive, in the file's characters. */
data class Token(val start: Int, val end: Int, val kind: TokenKind)

/** Just enough of a language to colour it: its keywords, comments and quotes. */
data class Syntax(
    val keywords: Set<String>,
    val lineComments: List<String> = emptyList(),
    val blockComment: Pair<String, String>? = null,
    val quotes: String = "\"'",
    /** Quotes whose strings may span lines, like JavaScript's template strings. Triple quotes always may. */
    val multilineQuotes: String = "",
)

/**
 * A small, forgiving highlighter for the read-only file viewer. It never parses: it finds
 * comments, strings, numbers and keywords in one pass, which is right for nearly every line an
 * owner reads on a phone and harmless when it is not.
 */
object SyntaxColors {
    private val cLike = setOf(
        "abstract", "break", "case", "catch", "class", "const", "continue", "default", "do", "else", "enum",
        "extends", "false", "final", "finally", "for", "if", "implements", "import", "interface", "new", "null",
        "package", "private", "protected", "public", "return", "static", "super", "switch", "this", "throw",
        "throws", "true", "try", "void", "while",
    )
    private val kotlin = Syntax(
        cLike + setOf(
            "as", "by", "companion", "data", "fun", "in", "init", "internal", "is", "lateinit", "object", "open",
            "override", "sealed", "suspend", "typealias", "val", "var", "when", "inline", "reified",
        ),
        lineComments = listOf("//"),
        blockComment = "/*" to "*/",
    )
    private val java = Syntax(
        cLike + setOf("boolean", "byte", "char", "double", "float", "instanceof", "int", "long", "short", "synchronized", "var"),
        lineComments = listOf("//"),
        blockComment = "/*" to "*/",
    )
    private val script = Syntax(
        cLike + setOf(
            "as", "async", "await", "delete", "export", "from", "function", "in", "instanceof", "let", "of", "type",
            "typeof", "undefined", "var", "yield",
        ),
        lineComments = listOf("//"),
        blockComment = "/*" to "*/",
        quotes = "\"'`",
        multilineQuotes = "`",
    )
    private val cFamily = Syntax(
        cLike + setOf(
            "auto", "bool", "char", "double", "float", "func", "go", "int", "let", "long", "match", "mut", "fn",
            "impl", "struct", "trait", "type", "unsigned", "use", "var", "defer", "chan", "map", "range", "pub",
            "self", "Self", "namespace", "template", "typedef", "sizeof", "nil", "async", "await", "late",
            "required", "dynamic", "mixin", "with",
        ),
        lineComments = listOf("//"),
        blockComment = "/*" to "*/",
    )
    private val python = Syntax(
        setOf(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else",
            "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None",
            "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while", "with", "yield",
        ),
        lineComments = listOf("#"),
    )
    private val shell = Syntax(
        setOf(
            "case", "do", "done", "elif", "else", "esac", "export", "fi", "for", "function", "if", "in", "local",
            "readonly", "return", "set", "then", "until", "while",
        ),
        lineComments = listOf("#"),
    )
    private val ruby = Syntax(
        setOf(
            "begin", "class", "def", "do", "else", "elsif", "end", "ensure", "false", "if", "module", "nil", "require",
            "rescue", "return", "self", "true", "unless", "until", "when", "while", "yield",
        ),
        lineComments = listOf("#"),
    )
    private val config = Syntax(setOf("true", "false", "null", "yes", "no", "on", "off"), lineComments = listOf("#"))
    private val json = Syntax(setOf("true", "false", "null"), quotes = "\"")
    private val markup = Syntax(emptySet(), blockComment = "<!--" to "-->")
    private val css = Syntax(setOf("important"), blockComment = "/*" to "*/")
    private val sql = Syntax(
        setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table",
            "drop", "alter", "join", "left", "right", "inner", "on", "and", "or", "not", "null", "as", "order", "by",
            "group", "having", "limit", "primary", "key", "index", "SELECT", "FROM", "WHERE", "INSERT", "INTO",
            "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "DROP", "ALTER", "JOIN", "LEFT", "RIGHT",
            "INNER", "ON", "AND", "OR", "NOT", "NULL", "AS", "ORDER", "BY", "GROUP", "HAVING", "LIMIT", "PRIMARY",
            "KEY", "INDEX",
        ),
        lineComments = listOf("--"),
        blockComment = "/*" to "*/",
    )

    private val byExtension: Map<String, Syntax> = buildMap {
        listOf("kt", "kts", "gradle", "groovy", "scala").forEach { put(it, kotlin) }
        put("java", java)
        listOf("js", "jsx", "mjs", "cjs", "ts", "tsx").forEach { put(it, script) }
        listOf("c", "h", "cc", "cpp", "hpp", "cs", "go", "rs", "swift", "dart", "m", "mm", "php").forEach { put(it, cFamily) }
        listOf("py", "pyi").forEach { put(it, python) }
        listOf("sh", "bash", "zsh").forEach { put(it, shell) }
        put("rb", ruby)
        listOf("yml", "yaml", "toml", "properties", "conf", "cfg", "ini", "env", "gitignore", "dockerignore").forEach { put(it, config) }
        listOf("json", "jsonc").forEach { put(it, json) }
        listOf("xml", "html", "htm", "svg", "vue").forEach { put(it, markup) }
        listOf("css", "scss", "less").forEach { put(it, css) }
        put("sql", sql)
    }

    private val byName: Map<String, Syntax> = mapOf(
        "Dockerfile" to shell,
        "Makefile" to shell,
        "Gemfile" to ruby,
        "Rakefile" to ruby,
        ".gitignore" to config,
        ".env" to config,
    )

    /** The syntax for a file name, or null for plain text (Markdown, logs, anything unknown). */
    fun forPath(path: String): Syntax? {
        val name = SourcePaths.name(path)
        byName[name]?.let { return it }
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return byExtension[extension]
    }

    fun tokens(text: String, syntax: Syntax): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val block = syntax.blockComment
            val lineComment = syntax.lineComments.firstOrNull { text.startsWith(it, i) }
            when {
                block != null && text.startsWith(block.first, i) -> {
                    val close = text.indexOf(block.second, i + block.first.length)
                    val end = if (close < 0) n else close + block.second.length
                    out += Token(i, end, TokenKind.COMMENT)
                    i = end
                }
                // A `#` inside a word (`a#b`, `$#`) does not start a comment.
                lineComment != null && (lineComment != "#" || i == 0 || !isWordChar(text[i - 1])) -> {
                    val end = lineEnd(text, i)
                    out += Token(i, end, TokenKind.COMMENT)
                    i = end
                }
                // An apostrophe after a letter (don't) is text, not the start of a string.
                c in syntax.quotes && (c != '\'' || i == 0 || !isWordChar(text[i - 1])) -> {
                    val end = stringEnd(text, i, c in syntax.multilineQuotes)
                    out += Token(i, end, TokenKind.STRING)
                    i = end
                }
                c.isDigit() && (i == 0 || !isWordChar(text[i - 1])) -> {
                    var end = i + 1
                    while (end < n && (text[end].isLetterOrDigit() || text[end] == '.' || text[end] == '_')) end++
                    out += Token(i, end, TokenKind.NUMBER)
                    i = end
                }
                isWordStart(c) -> {
                    var end = i + 1
                    while (end < n && isWordChar(text[end])) end++
                    if (text.substring(i, end) in syntax.keywords) out += Token(i, end, TokenKind.KEYWORD)
                    i = end
                }
                else -> i++
            }
        }
        return out
    }

    /** Each line's characters, without its line break (a CR before it included). */
    fun lines(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        while (true) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline
            val trimmed = if (end > start && text[end - 1] == '\r') end - 1 else end
            out += start until trimmed
            if (newline < 0) return out
            start = newline + 1
        }
    }

    /** The parts of [tokens] (sorted, as [tokens] makes them) inside [start]..<[end], counted from [start]. */
    fun inLine(tokens: List<Token>, start: Int, end: Int): List<Token> {
        // The first token that ends after the line starts; a comment or string may begin on an earlier line.
        var low = 0
        var high = tokens.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (tokens[mid].end <= start) low = mid + 1 else high = mid
        }
        val out = ArrayList<Token>()
        var i = low
        while (i < tokens.size && tokens[i].start < end) {
            val token = tokens[i]
            out += Token(maxOf(token.start, start) - start, minOf(token.end, end) - start, token.kind)
            i++
        }
        return out.filter { it.end > it.start }
    }

    private fun stringEnd(text: String, start: Int, multiline: Boolean): Int {
        val quote = text[start]
        val triple = quote.toString().repeat(3)
        if (text.startsWith(triple, start)) {
            val close = text.indexOf(triple, start + triple.length)
            return if (close < 0) text.length else close + triple.length
        }
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> i += 2
                c == quote -> return i + 1
                c == '\n' && !multiline -> return i
                else -> i++
            }
        }
        return text.length
    }

    private fun lineEnd(text: String, from: Int): Int = text.indexOf('\n', from).let { if (it < 0) text.length else it }

    private fun isWordStart(c: Char) = c.isLetter() || c == '_' || c == '$'

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}
