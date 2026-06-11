package dev.ehr.careteam

import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.security.SecurityPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class CareTeamController(
    private val careTeamService: CareTeamService,
) {
    @PostMapping("/patients/{patientId}/care-team")
    @ResponseStatus(HttpStatus.CREATED)
    fun addMember(
        authentication: Authentication,
        @PathVariable patientId: UUID,
        @Valid @RequestBody request: AddCareTeamMemberRequest,
    ): CareTeamMembershipResponse =
        careTeamService.addMember(
            principal = securityPrincipal(authentication),
            patientId = PatientId(patientId),
            userId = UserId(request.userId!!),
            role = request.role ?: CareTeamRole.CARE_TEAM,
        ).toResponse()

    @GetMapping("/patients/{patientId}/care-team")
    fun listForPatient(
        authentication: Authentication,
        @PathVariable patientId: UUID,
    ): CareTeamListResponse =
        CareTeamListResponse(
            members = careTeamService.listForPatient(
                principal = securityPrincipal(authentication),
                patientId = PatientId(patientId),
            ).map { it.toResponse() },
        )

    @PostMapping("/care-team-memberships/{membershipId}/end")
    fun endMembership(
        authentication: Authentication,
        @PathVariable membershipId: UUID,
    ): CareTeamMembershipResponse =
        careTeamService.endMembership(
            principal = securityPrincipal(authentication),
            membershipId = CareTeamMembershipId(membershipId),
        ).toResponse()

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")
}

data class AddCareTeamMemberRequest(
    @field:NotNull
    val userId: UUID?,
    val role: CareTeamRole? = null,
)

data class CareTeamMembershipResponse(
    val id: String,
    val organizationId: String,
    val patientId: String,
    val userId: String,
    val role: String,
    val origin: String,
    val periodStart: Instant,
    val periodEnd: Instant?,
)

data class CareTeamListResponse(
    val members: List<CareTeamMembershipResponse>,
)

fun CareTeamMembership.toResponse(): CareTeamMembershipResponse =
    CareTeamMembershipResponse(
        id = id.value.toString(),
        organizationId = organizationId.value.toString(),
        patientId = patientId.value.toString(),
        userId = userId.value.toString(),
        role = role.dbValue,
        origin = origin.dbValue,
        periodStart = periodStart,
        periodEnd = periodEnd,
    )
