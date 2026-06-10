package dev.ehr.medication

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
class MedicationStatementController(
    private val medicationStatementService: MedicationStatementService,
) {
    @PostMapping("/patients/{patientId}/medication-statements")
    @ResponseStatus(HttpStatus.CREATED)
    fun record(
        authentication: Authentication,
        @PathVariable patientId: UUID,
        @Valid @RequestBody request: RecordMedicationStatementRequest,
    ): MedicationStatementResponse {
        val principal = securityPrincipal(authentication)
        return medicationStatementService.record(
            principal = principal,
            command = MedicationStatementCreateCommand(
                organizationId = principal.organization.organizationId,
                patientId = PatientId(patientId),
                medicationConceptId = CodeableConceptId(request.medicationConceptId!!),
                encounterId = request.encounterId?.let(::EncounterId),
                status = request.status ?: MedicationStatementStatus.ACTIVE,
                dosageText = request.dosageText,
                effectiveStart = request.effectiveStart,
                effectiveEnd = request.effectiveEnd,
                createdBy = principal.subject.userId,
            ),
        ).toResponse()
    }

    @GetMapping("/medication-statements/{medicationStatementId}")
    fun get(
        authentication: Authentication,
        @PathVariable medicationStatementId: UUID,
    ): MedicationStatementResponse =
        medicationStatementService.get(
            principal = securityPrincipal(authentication),
            medicationStatementId = MedicationStatementId(medicationStatementId),
        ).toResponse()

    @GetMapping("/patients/{patientId}/medication-statements")
    fun listForPatient(
        authentication: Authentication,
        @PathVariable patientId: UUID,
    ): MedicationStatementListResponse =
        MedicationStatementListResponse(
            medicationStatements = medicationStatementService.listForPatient(
                principal = securityPrincipal(authentication),
                patientId = PatientId(patientId),
            ).map { it.toResponse() },
        )

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")
}

data class RecordMedicationStatementRequest(
    @field:NotNull
    val medicationConceptId: UUID?,
    val encounterId: UUID? = null,
    val status: MedicationStatementStatus? = null,
    val dosageText: String? = null,
    val effectiveStart: LocalDate? = null,
    val effectiveEnd: LocalDate? = null,
)

data class MedicationStatementResponse(
    val id: String,
    val organizationId: String,
    val patientId: String,
    val encounterId: String?,
    val status: String,
    val medicationConceptId: String,
    val dosageText: String?,
    val effectiveStart: LocalDate?,
    val effectiveEnd: LocalDate?,
    val recordedAt: Instant,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class MedicationStatementListResponse(
    val medicationStatements: List<MedicationStatementResponse>,
)

fun MedicationStatement.toResponse(): MedicationStatementResponse =
    MedicationStatementResponse(
        id = id.value.toString(),
        organizationId = organizationId.value.toString(),
        patientId = patientId.value.toString(),
        encounterId = encounterId?.value?.toString(),
        status = status.dbValue,
        medicationConceptId = medicationConceptId.value.toString(),
        dosageText = dosageText,
        effectiveStart = effectiveStart,
        effectiveEnd = effectiveEnd,
        recordedAt = recordedAt,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
