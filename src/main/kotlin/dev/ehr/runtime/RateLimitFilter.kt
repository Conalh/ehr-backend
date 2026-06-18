package dev.ehr.runtime

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixed-window request throttling per client address for the API and FHIR
 * surfaces. Single-node in-memory by design; horizontally scaled deployments
 * need shared state (documented deferral). Registered as a bean in
 * SecurityConfiguration so web-slice tests do not instantiate it.
 */
class RateLimitFilter(
    private val properties: EhrProperties,
    private val clock: () -> Long = System::currentTimeMillis,
) : OncePerRequestFilter() {
    private data class Window(val windowIndex: Long, val count: AtomicInteger)

    private val windows = ConcurrentHashMap<String, Window>()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestURI
        if (!path.startsWith("/api/") && !path.startsWith("/fhir/") &&
            path != "/oauth/token" && path != "/login"
        ) {
            filterChain.doFilter(request, response)
            return
        }

        val now = clock()
        val windowIndex = now / WINDOW_MILLIS
        val key = request.remoteAddr ?: "unknown"
        val window = windows.compute(key) { _, existing ->
            if (existing == null || existing.windowIndex != windowIndex) {
                Window(windowIndex, AtomicInteger(0))
            } else {
                existing
            }
        }!!

        if (window.count.incrementAndGet() > properties.rateLimit.requestsPerMinute) {
            val retryAfterSeconds = ((windowIndex + 1) * WINDOW_MILLIS - now + 999) / 1000
            response.status = 429
            response.setHeader("Retry-After", retryAfterSeconds.toString())
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write("""{"error":"rate_limited","retryAfterSeconds":$retryAfterSeconds}""")
            return
        }

        // Opportunistic cleanup keeps the map from accumulating stale windows.
        if (windows.size > MAX_TRACKED_CLIENTS) {
            windows.entries.removeIf { it.value.windowIndex != windowIndex }
        }

        filterChain.doFilter(request, response)
    }

    private companion object {
        const val WINDOW_MILLIS = 60_000L
        const val MAX_TRACKED_CLIENTS = 10_000
    }
}
