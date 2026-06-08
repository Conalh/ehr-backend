package dev.ehr.security

import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/security")
class CurrentSecurityController {
    @GetMapping("/whoami")
    fun whoami(authentication: Authentication): CurrentSecurityResponse {
        val principal = authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")

        return CurrentSecurityResponse(
            externalSubject = principal.subject.externalSubject,
            userId = principal.subject.userId?.value?.toString(),
            organizationId = principal.organization.organizationId.value.toString(),
            membershipId = principal.membership.membershipId.value.toString(),
            roles = principal.membership.roles.map { it.dbValue },
            scopes = principal.subject.scopes.map { it.rawValue },
            clientId = principal.subject.clientId?.value?.toString(),
        )
    }
}

data class CurrentSecurityResponse(
    val externalSubject: String,
    val userId: String?,
    val organizationId: String,
    val membershipId: String,
    val roles: List<String>,
    val scopes: List<String>,
    val clientId: String?,
)
