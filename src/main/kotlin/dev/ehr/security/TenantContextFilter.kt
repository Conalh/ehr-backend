package dev.ehr.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Binds the authenticated principal's organization into TenantContextHolder
 * for the duration of the request. Registered as a bean in
 * SecurityConfiguration (not @Component) so MVC test slices stay unaffected.
 */
class TenantContextFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? SecurityPrincipal
        if (principal == null) {
            filterChain.doFilter(request, response)
            return
        }
        try {
            TenantContextHolder.set(principal.organization.organizationId)
            filterChain.doFilter(request, response)
        } finally {
            TenantContextHolder.clear()
        }
    }
}
