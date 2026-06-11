package dev.ehr.security

/**
 * Surfaces the current request's break-glass assertion, if any. Returns the
 * mandatory free-text reason, or null when no (or a blank) assertion was made.
 */
fun interface BreakGlassAccessor {
    fun currentReason(): String?
}
