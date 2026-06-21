package dev.ehr.authz

import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserId
import dev.ehr.identity.UserRepository
import dev.ehr.identity.MembershipRepository
import dev.ehr.patient.PatientId
import dev.ehr.patient.PatientRepository
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
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
import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.util.UUID

object PatientLaunchSession {
    const val PENDING_TRANSACTION = "ehr.launch.pendingTransaction"
    const val SELECTED_TRANSACTION = "ehr.launch.selectedTransaction"
    const val LAUNCH_TRANSACTION_ID_PARAM = "launchTransactionId"
    const val LAUNCH_PATIENT_SCOPE = "launch/patient"
    const val ACTIVE_TRANSACTION_REQUEST_ATTRIBUTE = "dev.ehr.launch.activeTransaction"
    const val ACTIVE_SELECTION_REQUEST_ATTRIBUTE = "dev.ehr.launch.activeSelection"

    private const val LEGACY_SELECTED_PATIENT = "ehr.launch.selectedPatient"
    private const val LEGACY_RESUME_AUTHORIZE = "ehr.launch.resumeAuthorize"
    private val SCOPE_SPLITTER = Regex("\\s+")

    fun authorizeRequest(request: HttpServletRequest): PatientLaunchAuthorizeRequest? {
        if (request.method != "GET" || request.requestURI != "/oauth/authorize") {
            return null
        }
        val requestedScopes = normalizedScopes(request.getParameter("scope"))
        if (!requestedScopes.contains(LAUNCH_PATIENT_SCOPE)) {
            return null
        }
        return PatientLaunchAuthorizeRequest(
            requestTarget = requestTarget(request),
            clientId = normalizedParameter(request, "client_id"),
            requestedScopes = requestedScopes,
            state = normalizedParameter(request, "state"),
        )
    }

    fun pendingTransaction(session: HttpSession): PatientLaunchTransaction? {
        val transaction = session.getAttribute(PENDING_TRANSACTION) as? PatientLaunchTransaction ?: return null
        if (transaction.isExpired()) {
            clear(session)
            return null
        }
        return transaction
    }

    fun selectedTransaction(session: HttpSession): PatientLaunchSelection? {
        val selection = session.getAttribute(SELECTED_TRANSACTION) as? PatientLaunchSelection ?: return null
        if (selection.isExpired()) {
            clear(session)
            return null
        }
        return selection
    }

    fun clear(session: HttpSession) {
        session.removeAttribute(PENDING_TRANSACTION)
        session.removeAttribute(SELECTED_TRANSACTION)
        session.removeAttribute(LEGACY_SELECTED_PATIENT)
        session.removeAttribute(LEGACY_RESUME_AUTHORIZE)
    }

    private fun normalizedScopes(rawScopes: String?): Set<String> =
        rawScopes
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.split(SCOPE_SPLITTER)
            ?.filter { it.isNotBlank() }
            ?.toSortedSet()
            ?: emptySet()

    private fun normalizedParameter(
        request: HttpServletRequest,
        name: String,
    ): String? =
        request.getParameter(name)?.trim()?.takeIf { it.isNotEmpty() }

    private fun requestTarget(request: HttpServletRequest): String =
        request.queryString
            ?.takeIf { it.isNotEmpty() }
            ?.let { "${request.requestURI}?$it" }
            ?: request.requestURI
}

data class PatientLaunchAuthorizeRequest(
    val requestTarget: String,
    val clientId: String?,
    val requestedScopes: Set<String>,
    val state: String?,
) : Serializable

data class PatientLaunchTransaction(
    val id: UUID,
    val resumeAuthorize: String,
    val clientId: String?,
    val requestedScopes: Set<String>,
    val state: String?,
    val createdAt: Instant = Instant.now(),
) : Serializable {
    fun matches(request: PatientLaunchAuthorizeRequest): Boolean =
        resumeAuthorize == request.requestTarget &&
            clientId == request.clientId &&
            requestedScopes == request.requestedScopes &&
            state == request.state

    fun isExpired(now: Instant = Instant.now()): Boolean =
        createdAt.plus(Duration.ofMinutes(5)).isBefore(now)

    companion object {
        fun from(request: PatientLaunchAuthorizeRequest): PatientLaunchTransaction =
            PatientLaunchTransaction(
                id = UUID.randomUUID(),
                resumeAuthorize = request.requestTarget,
                clientId = request.clientId,
                requestedScopes = request.requestedScopes,
                state = request.state,
            )
    }
}

data class PatientLaunchSelection(
    val transactionId: UUID,
    val patientId: UUID,
    val clientId: String?,
    val requestedScopes: Set<String>,
    val state: String?,
    val createdAt: Instant = Instant.now(),
) : Serializable {
    fun matches(transaction: PatientLaunchTransaction): Boolean =
        transactionId == transaction.id &&
            clientId == transaction.clientId &&
            requestedScopes == transaction.requestedScopes &&
            state == transaction.state

    fun isExpired(now: Instant = Instant.now()): Boolean =
        createdAt.plus(Duration.ofMinutes(5)).isBefore(now)

    companion object {
        fun from(
            transaction: PatientLaunchTransaction,
            patientId: UUID,
        ): PatientLaunchSelection =
            PatientLaunchSelection(
                transactionId = transaction.id,
                patientId = patientId,
                clientId = transaction.clientId,
                requestedScopes = transaction.requestedScopes,
                state = transaction.state,
            )
    }
}

/**
 * Interposes the synthetic patient picker: an authenticated authorize
 * request asking for `launch/patient` is parked until that exact authorize
 * transaction carries a selected patient.
 */
class PatientLaunchFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val launchRequest = PatientLaunchSession.authorizeRequest(request)
        if (launchRequest != null &&
            SecurityContextHolder.getContext().authentication?.isAuthenticated == true
        ) {
            val session = request.session
            val transaction = PatientLaunchSession.pendingTransaction(session)
            val selection = PatientLaunchSession.selectedTransaction(session)
            if (transaction != null &&
                selection != null &&
                transaction.matches(launchRequest) &&
                selection.matches(transaction)
            ) {
                request.setAttribute(PatientLaunchSession.ACTIVE_TRANSACTION_REQUEST_ATTRIBUTE, transaction)
                request.setAttribute(PatientLaunchSession.ACTIVE_SELECTION_REQUEST_ATTRIBUTE, selection)
                PatientLaunchSession.clear(session)
                try {
                    filterChain.doFilter(request, response)
                } finally {
                    PatientLaunchSession.clear(session)
                    request.removeAttribute(PatientLaunchSession.ACTIVE_TRANSACTION_REQUEST_ATTRIBUTE)
                    request.removeAttribute(PatientLaunchSession.ACTIVE_SELECTION_REQUEST_ATTRIBUTE)
                }
                return
            }
            PatientLaunchSession.clear(session)
            session.setAttribute(PatientLaunchSession.PENDING_TRANSACTION, PatientLaunchTransaction.from(launchRequest))
            response.sendRedirect("/launch/patient-picker")
            return
        }
        filterChain.doFilter(request, response)
    }
}

/**
 * The deliberately plain synthetic patient picker (accepted design decision
 * 5): lists the logged-in user's organization's patients; the selection is
 * validated against that organization and parked against one authorize
 * transaction for the authorization service to pick up.
 */
@Controller
class PatientLaunchController(
    private val userRepository: UserRepository,
    private val membershipRepository: MembershipRepository,
    private val patientRepository: PatientRepository,
    private val auditEventService: AuditEventService,
) {
    @GetMapping("/launch/patient-picker", produces = [MediaType.TEXT_HTML_VALUE])
    @org.springframework.web.bind.annotation.ResponseBody
    fun picker(
        authentication: Authentication,
        request: HttpServletRequest,
    ): String {
        val transaction = pendingTransaction(request)
        val (scope, userId) = resolve(authentication)
        val patients = patientRepository.findRecentByOrganization(scope)
        auditEventService.recordBackgroundEvent(
            organizationId = scope.organizationId,
            subjectUserId = userId,
            resourceType = "PATIENT",
            operation = AuditOperation.SEARCH,
            outcome = AuditOutcome.SUCCESS,
        )
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
              <input type="hidden" name="${PatientLaunchSession.LAUNCH_TRANSACTION_ID_PARAM}" value="${transaction.id}"/>
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
        @RequestParam(PatientLaunchSession.LAUNCH_TRANSACTION_ID_PARAM) launchTransactionId: UUID,
        @RequestParam patientId: UUID,
    ): String {
        val session = request.getSession(false)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No authorization request to resume")
        val transaction = PatientLaunchSession.pendingTransaction(session)
        if (transaction == null || transaction.id != launchTransactionId) {
            PatientLaunchSession.clear(session)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No authorization request to resume")
        }
        val (scope, userId) = resolve(authentication)
        if (patientRepository.findById(scope, PatientId(patientId)) == null) {
            PatientLaunchSession.clear(session)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Patient is not in your organization")
        }

        auditEventService.recordBackgroundEvent(
            organizationId = scope.organizationId,
            subjectUserId = userId,
            resourceType = "PATIENT",
            operation = AuditOperation.READ,
            outcome = AuditOutcome.SUCCESS,
            resourceId = patientId,
        )
        session.setAttribute(
            PatientLaunchSession.SELECTED_TRANSACTION,
            PatientLaunchSelection.from(transaction, patientId),
        )
        return "redirect:${transaction.resumeAuthorize}"
    }

    private fun resolve(authentication: Authentication): Pair<TenantScope, UserId?> {
        val user = userRepository.findByExternalSubject(authentication.name)
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Unknown user")
        val memberships = membershipRepository.findActiveByUser(user.id)
        val membership = when {
            memberships.isEmpty() -> throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Patient launch requires an active organization membership",
            )
            memberships.size > 1 -> throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Patient launch requires exactly one active organization membership; user has ${memberships.size}",
            )
            else -> memberships.first()
        }
        return TenantScope(membership.organizationId) to user.id
    }

    private fun pendingTransaction(request: HttpServletRequest): PatientLaunchTransaction {
        val session = request.getSession(false)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No authorization request to resume")
        return PatientLaunchSession.pendingTransaction(session)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No authorization request to resume")
    }
}
