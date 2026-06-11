package dev.ehr.security

import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * Reads the break-glass assertion from the current HTTP request. A blank
 * header is treated as no assertion (the reason is mandatory).
 */
@Component
class HttpHeaderBreakGlassAccessor : BreakGlassAccessor {
    override fun currentReason(): String? {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            ?: return null
        val reason = attributes.request.getHeader(BREAK_GLASS_HEADER)?.trim()
        return reason?.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val BREAK_GLASS_HEADER = "X-Break-Glass-Reason"
    }
}
