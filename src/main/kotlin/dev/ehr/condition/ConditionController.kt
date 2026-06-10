package dev.ehr.condition

import dev.ehr.encounter.EncounterId
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
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class ConditionController(
    private val conditionService: ConditionService,
) {
    @PostMapping("/patients/{patientId}/conditions")
    @ResponseStatus(HttpStatus.CREATED)
    fun record(
        authentication: Authentication,
        @PathVariable patientId: UUID,
        @Valid @RequestBody request: RecordConditionRequest,
    ): ConditionResponse {
        val principal = securityPrincipal(authentication)
        return conditionService.record(
            principal = principal,
            command = ConditionCreateCommand(
                organizationId = principal.organization.organizationId,
                patientId = PatientId(patientId),
                codeConceptId = CodeableConceptId(request.codeConceptId!!),
                encounterId = request.encounterId?.let(::EncounterId),
                clinicalStatus = request.clinicalStatus ?: ConditionClinicalStatus.ACTIVE,
                verificationStatus = request.verificationStatus ?: ConditionVerificationStatus.CONFIRMED,
                onsetDate = request.onsetDate,
                abatementDate = request.abatementDate,
                createdBy = principal.subject.userId,
            ),
        ).toResponse()
    }

    @GetMapping("/conditions/{conditionId}")
    fun get(
        authentication: Authentication,
        @PathVariable conditionId: UUID,
    ): ConditionResponse =
        conditionService.get(
            principal = securityPrincipal(authentication),
            conditionId = ConditionId(conditionId),
        ).toResponse()

    @GetMapping("/patients/{patientId}/conditions")
    fun problemList(
        authentication: Authentication,
        @PathVariable patientId: UUID,
    ): ConditionListResponse =
        ConditionListResponse(
            conditions = conditionService.problemList(
                principal = securityPrincipal(authentication),
                patientId = PatientId(patientId),
            ).map { it.toResponse() },
        )

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")
}

data class RecordConditionRequest(
    @field:NotNull
    val codeConceptId: UUID?,
    val encounterId: UUID? = null,
    val clinicalStatus: ConditionClinicalStatus? = null,
    val verificationStatus: ConditionVerificationStatus? = null,
    val onsetDate: LocalDate? = null,
    val abatementDate: LocalDate? = null,
)

data class ConditionResponse(
    val id: String,
    val organizationId: String,
    val patientId: String,
    val encounterId: String?,
    val clinicalStatus: String,
    val verificationStatus: String,
    val codeConceptId: String,
    val onsetDate: LocalDate?,
    val abatementDate: LocalDate?,
    val recordedAt: Instant,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ConditionListResponse(
    val conditions: List<ConditionResponse>,
)

fun Condition.toResponse(): ConditionResponse =
    ConditionResponse(
        id = id.value.toString(),
        organizationId = organizationId.value.toString(),
        patientId = patientId.value.toString(),
        encounterId = encounterId?.value?.toString(),
        clinicalStatus = clinicalStatus.dbValue,
        verificationStatus = verificationStatus.dbValue,
        codeConceptId = codeConceptId.value.toString(),
        onsetDate = onsetDate,
        abatementDate = abatementDate,
        recordedAt = recordedAt,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
