package dev.ehr.authz

import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserRepository
import dev.ehr.identity.MembershipRepository
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.util.HtmlUtils
import java.util.UUID

object PatientLaunchSession {
    const val SELECTED_PATIENT = "ehr.launch.selectedPatient"
    const val RESUME_AUTHORIZE = "ehr.launch.resumeAuthorize"
    const val LAUNCH_PATIENT_SCOPE = "launch/patient"
}

/**
 * Interposes the synthetic patient picker: an authenticated authorize
 * request asking for `launch/patient` is parked until the session carries a
 * selected patient.
 */
class PatientLaunchFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.method == "GET" &&
            request.requestURI == "/oauth/authorize" &&
            request.getParameter("scope")?.split(" ")?.contains(PatientLaunchSession.LAUNCH_PATIENT_SCOPE) == true &&
            SecurityContextHolder.getContext().authentication?.isAuthenticated == true &&
            request.session.getAttribute(PatientLaunchSession.SELECTED_PATIENT) == null
        ) {
            request.session.setAttribute(
                PatientLaunchSession.RESUME_AUTHORIZE,
                "${request.requestURI}?${request.queryString}",
            )
            response.sendRedirect("/launch/patient-picker")
            return
        }
        filterChain.doFilter(request, response)
    }
}

/**
 * The deliberately plain synthetic patient picker (accepted design decision
 * 5): lists the logged-in user's organization's patients; the selection is
 * validated against that organization and parked in the session for the
 * authorize request to pick up.
 */
@Controller
class PatientLaunchController(
    private val userRepository: UserRepository,
    private val membershipRepository: MembershipRepository,
    private val patientRepository: PatientRepository,
) {
    @GetMapping("/launch/patient-picker", produces = [MediaType.TEXT_HTML_VALUE])
    @org.springframework.web.bind.annotation.ResponseBody
    fun picker(
        authentication: Authentication,
        request: HttpServletRequest,
    ): String {
        val scope = tenantScopeFor(authentication)
        val patients = patientRepository.findRecentByOrganization(scope)
        val csrf = request.getAttribute(CsrfToken::class.java.name) as? CsrfToken
        val options = patients.joinToString("\n") { patient ->
            val label = HtmlUtils.htmlEscape("${patient.familyName}, ${patient.givenName} (${patient.id.value})")
            """<option value="${patient.id.value}">$label</option>"""
        }
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="utf-8"><title>Select patient (synthetic)</title></head>
            <body>
            <h1>Select a patient</h1>
            <p>Synthetic data only. The launched app will be bound to this patient.</p>
            <form method="post" action="/launch/patient-picker">
              <select name="patientId">$options</select>
              ${csrf?.let { """<input type="hidden" name="${it.parameterName}" value="${it.token}"/>""" } ?: ""}
              <button type="submit">Launch</button>
            </form>
            </body>
            </html>
        """.trimIndent()
    }

    @PostMapping("/launch/patient-picker")
    fun select(
        authentication: Authentication,
        request: HttpServletRequest,
        @RequestParam patientId: UUID,
    ): String {
        val scope = tenantScopeFor(authentication)
        patientRepository.findById(scope, PatientId(patientId))
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Patient is not in your organization")

        request.session.setAttribute(PatientLaunchSession.SELECTED_PATIENT, patientId)
        val resume = (request.session.getAttribute(PatientLaunchSession.RESUME_AUTHORIZE) as? String)
            ?.takeIf { it.startsWith("/oauth/authorize?") }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No authorization request to resume")
        return "redirect:$resume"
    }

    private fun tenantScopeFor(authentication: Authentication): TenantScope {
        val user = userRepository.findByExternalSubject(authentication.name)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Unknown user")
        val membership = membershipRepository.findActiveByUser(user.id).singleOrNull()
            ?: throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Patient launch requires exactly one active organization membership",
            )
        return TenantScope(membership.organizationId)
    }
}
