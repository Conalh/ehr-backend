package dev.ehr.allergy

import dev.ehr.encounter.EncounterId
import dev.ehr.patient.PatientId
import dev.ehr.security.securityPrincipal
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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class AllergyController(
    private val allergyService: AllergyService,
) {
    @PostMapping("/patients/{patientId}/allergies")
    @ResponseStatus(HttpStatus.CREATED)
    fun record(
        authentication: Authentication,
        @PathVariable patientId: UUID,
        @Valid @RequestBody request: RecordAllergyRequest,
    ): AllergyResponse {
        val principal = authentication.securityPrincipal()
        return allergyService.record(
            principal = principal,
            command = AllergyCreateCommand(
                organizationId = principal.organization.organizationId,
                patientId = PatientId(patientId),
                codeConceptId = CodeableConceptId(request.codeConceptId!!),
                encounterId = request.encounterId?.let(::EncounterId),
                clinicalStatus = request.clinicalStatus ?: AllergyClinicalStatus.ACTIVE,
                verificationStatus = request.verificationStatus ?: AllergyVerificationStatus.CONFIRMED,
                category = request.category,
                criticality = request.criticality,
                onsetDate = request.onsetDate,
                createdBy = principal.subject.userId,
            ),
        ).toResponse()
    }

    @GetMapping("/allergies/{allergyId}")
    fun get(
        authentication: Authentication,
        @PathVariable allergyId: UUID,
    ): AllergyResponse =
        allergyService.get(
            principal = authentication.securityPrincipal(),
            allergyId = AllergyId(allergyId),
        ).toResponse()

    @GetMapping("/patients/{patientId}/allergies")
    fun allergyList(
        authentication: Authentication,
        @PathVariable patientId: UUID,
    ): AllergyListResponse =
        AllergyListResponse(
            allergies = allergyService.allergyList(
                principal = authentication.securityPrincipal(),
                patientId = PatientId(patientId),
            ).map { it.toResponse() },
        )

}

data class RecordAllergyRequest(
    @field:NotNull
    val codeConceptId: UUID?,
    val encounterId: UUID? = null,
    val clinicalStatus: AllergyClinicalStatus? = null,
    val verificationStatus: AllergyVerificationStatus? = null,
    val category: AllergyCategory? = null,
    val criticality: AllergyCriticality? = null,
    val onsetDate: LocalDate? = null,
)

data class AllergyResponse(
    val id: String,
    val organizationId: String,
    val patientId: String,
    val encounterId: String?,
    val clinicalStatus: String,
    val verificationStatus: String,
    val codeConceptId: String,
    val category: String?,
    val criticality: String?,
    val onsetDate: LocalDate?,
    val recordedAt: Instant,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AllergyListResponse(
    val allergies: List<AllergyResponse>,
)

fun Allergy.toResponse(): AllergyResponse =
    AllergyResponse(
        id = id.value.toString(),
        organizationId = organizationId.value.toString(),
        patientId = patientId.value.toString(),
        encounterId = encounterId?.value?.toString(),
        clinicalStatus = clinicalStatus.dbValue,
        verificationStatus = verificationStatus.dbValue,
        codeConceptId = codeConceptId.value.toString(),
        category = category?.dbValue,
        criticality = criticality?.dbValue,
        onsetDate = onsetDate,
        recordedAt = recordedAt,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
