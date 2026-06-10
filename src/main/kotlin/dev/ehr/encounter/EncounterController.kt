package dev.ehr.encounter

import dev.ehr.patient.PatientId
import dev.ehr.security.SecurityPrincipal
import dev.ehr.terminology.CodeableConceptId
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
class EncounterController(
    private val encounterService: EncounterService,
) {
    @PostMapping("/patients/{patientId}/encounters")
    @ResponseStatus(HttpStatus.CREATED)
    fun open(
        authentication: Authentication,
        @PathVariable patientId: UUID,
        @Valid @RequestBody request: OpenEncounterRequest,
    ): EncounterResponse {
        val principal = securityPrincipal(authentication)
        return encounterService.open(
            principal = principal,
            patientId = PatientId(patientId),
            command = EncounterCreateCommand(
                organizationId = principal.organization.organizationId,
                patientId = PatientId(patientId),
                classConceptId = CodeableConceptId(request.classConceptId!!),
                periodStart = request.periodStart!!,
                status = request.status ?: EncounterStatus.PLANNED,
                createdBy = principal.subject.userId,
            ),
        ).toResponse()
    }

    @GetMapping("/encounters/{encounterId}")
    fun get(
        authentication: Authentication,
        @PathVariable encounterId: UUID,
    ): EncounterResponse =
        encounterService.get(
            principal = securityPrincipal(authentication),
            encounterId = EncounterId(encounterId),
        ).toResponse()

    @GetMapping("/patients/{patientId}/encounters")
    fun timeline(
        authentication: Authentication,
        @PathVariable patientId: UUID,
    ): EncounterListResponse =
        EncounterListResponse(
            encounters = encounterService.timeline(
                principal = securityPrincipal(authentication),
                patientId = PatientId(patientId),
            ).map { it.toResponse() },
        )

    @PostMapping("/encounters/{encounterId}/status")
    fun transition(
        authentication: Authentication,
        @PathVariable encounterId: UUID,
        @Valid @RequestBody request: TransitionEncounterRequest,
    ): EncounterResponse {
        val principal = securityPrincipal(authentication)
        return encounterService.transition(
            principal = principal,
            encounterId = EncounterId(encounterId),
            command = EncounterTransitionCommand(
                targetStatus = request.targetStatus!!,
                periodEnd = request.periodEnd,
                updatedBy = principal.subject.userId,
                expectedVersion = request.expectedVersion,
            ),
        ).toResponse()
    }

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")
}

data class OpenEncounterRequest(
    @field:NotNull
    val classConceptId: UUID?,
    @field:NotNull
    val periodStart: Instant?,
    val status: EncounterStatus? = null,
)

data class TransitionEncounterRequest(
    @field:NotNull
    val targetStatus: EncounterStatus?,
    val periodEnd: Instant? = null,
    val expectedVersion: Int? = null,
)

data class EncounterResponse(
    val id: String,
    val organizationId: String,
    val patientId: String,
    val status: String,
    val classConceptId: String,
    val periodStart: Instant,
    val periodEnd: Instant?,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class EncounterListResponse(
    val encounters: List<EncounterResponse>,
)

private fun Encounter.toResponse(): EncounterResponse =
    EncounterResponse(
        id = id.value.toString(),
        organizationId = organizationId.value.toString(),
        patientId = patientId.value.toString(),
        status = status.dbValue,
        classConceptId = classConceptId.value.toString(),
        periodStart = periodStart,
        periodEnd = periodEnd,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
