package dev.ehr.patient

import dev.ehr.security.SecurityPrincipal
import dev.ehr.terminology.CodeableConceptId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/patients")
class PatientController(
    private val patientService: PatientService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        authentication: Authentication,
        @Valid @RequestBody request: CreatePatientRequest,
    ): PatientResponse {
        val principal = securityPrincipal(authentication)
        return patientService.create(
            principal = principal,
            command = PatientCreateCommand(
                organizationId = principal.organization.organizationId,
                givenName = request.givenName,
                familyName = request.familyName,
                birthDate = request.birthDate,
                administrativeGender = request.administrativeGender,
                createdBy = principal.subject.userId,
            ),
            identifierCommands = request.identifiers.map { identifier ->
                PatientIdentifierCreateCommand(
                    system = identifier.system,
                    value = identifier.value,
                    use = identifier.use,
                    typeConceptId = identifier.typeConceptId?.let(::CodeableConceptId),
                    assignerText = identifier.assignerText,
                    periodStart = identifier.periodStart,
                    periodEnd = identifier.periodEnd,
                )
            },
        ).toResponse()
    }

    @GetMapping("/{patientId}")
    fun get(
        authentication: Authentication,
        @PathVariable patientId: UUID,
    ): PatientResponse =
        patientService.get(
            principal = securityPrincipal(authentication),
            patientId = PatientId(patientId),
        ).toResponse()

    @GetMapping
    fun searchByIdentifier(
        authentication: Authentication,
        @RequestParam identifierSystem: String,
        @RequestParam identifierValue: String,
    ): PatientSearchResponse {
        if (identifierSystem.isBlank() || identifierValue.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Identifier system and value must not be blank")
        }
        return PatientSearchResponse(
            patients = patientService.searchByIdentifier(
                principal = securityPrincipal(authentication),
                system = identifierSystem,
                value = identifierValue,
            ).map { it.toResponse() },
        )
    }

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")
}

data class CreatePatientRequest(
    @field:NotBlank
    val givenName: String,
    @field:NotBlank
    val familyName: String,
    val birthDate: LocalDate? = null,
    val administrativeGender: PatientAdministrativeGender? = null,
    @field:Valid
    val identifiers: List<CreatePatientIdentifierRequest> = emptyList(),
)

data class CreatePatientIdentifierRequest(
    @field:NotBlank
    val system: String,
    @field:NotBlank
    val value: String,
    val use: IdentifierUse? = null,
    val typeConceptId: UUID? = null,
    val assignerText: String? = null,
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
)

data class PatientResponse(
    val id: String,
    val organizationId: String,
    val status: String,
    val givenName: String,
    val familyName: String,
    val birthDate: LocalDate?,
    val administrativeGender: String?,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val identifiers: List<PatientIdentifierResponse>,
)

data class PatientIdentifierResponse(
    val id: String,
    val system: String,
    val value: String,
    val use: String?,
    val typeConceptId: String?,
    val assignerText: String?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
)

data class PatientSearchResponse(
    val patients: List<PatientResponse>,
)

fun PatientWithIdentifiers.toResponse(): PatientResponse =
    PatientResponse(
        id = patient.id.value.toString(),
        organizationId = patient.organizationId.value.toString(),
        status = patient.status.dbValue,
        givenName = patient.givenName,
        familyName = patient.familyName,
        birthDate = patient.birthDate,
        administrativeGender = patient.administrativeGender?.dbValue,
        version = patient.version,
        createdAt = patient.createdAt,
        updatedAt = patient.updatedAt,
        identifiers = identifiers.map { identifier ->
            PatientIdentifierResponse(
                id = identifier.id.value.toString(),
                system = identifier.system,
                value = identifier.value,
                use = identifier.use?.dbValue,
                typeConceptId = identifier.typeConceptId?.value?.toString(),
                assignerText = identifier.assignerText,
                periodStart = identifier.periodStart,
                periodEnd = identifier.periodEnd,
            )
        },
    )
