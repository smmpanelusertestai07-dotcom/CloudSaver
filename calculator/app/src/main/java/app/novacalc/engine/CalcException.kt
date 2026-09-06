package app.novacalc.engine

/** A calculation that cannot produce a number. The [kind] maps to a user-facing message. */
class CalcException(val kind: Kind) : Exception(kind.name) {
    enum class Kind { SYNTAX, DIVIDE_BY_ZERO, DOMAIN, OVERFLOW, EMPTY }
}
