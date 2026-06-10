package dev.ehr.observation

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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class ObservationController(
    private val observationService: ObservationService,
) {
    @PostMapping("/patients/{patientId}/observations")
    @ResponseStatus(HttpStatus.CREATED)
    fun record(
        authentication: Authentication,
        @PathVariable patientId: UUID,
        @Valid @RequestBody request: RecordObservationRequest,
    ): ObservationResponse {
        val principal = securityPrincipal(authentication)
        return observationService.record(
            principal = principal,
            command = ObservationCreateCommand(
                organizationId = principal.organization.organizationId,
                patientId = PatientId(patientId),
                category = request.category!!,
                codeConceptId = CodeableConceptId(request.codeConceptId!!),
                value = request.toObservationValue(),
                effectiveAt = request.effectiveAt!!,
                encounterId = request.encounterId?.let(::EncounterId),
                status = request.status ?: ObservationStatus.FINAL,
                createdBy = principal.subject.userId,
            ),
        ).toResponse()
    }

    @GetMapping("/observations/{observationId}")
    fun get(
        authentication: Authentication,
        @PathVariable observationId: UUID,
    ): ObservationResponse =
        observationService.get(
            principal = securityPrincipal(authentication),
            observationId = ObservationId(observationId),
        ).toResponse()

    @GetMapping("/patients/{patientId}/observations")
    fun listForPatient(
        authentication: Authentication,
        @PathVariable patientId: UUID,
        @RequestParam category: ObservationCategory?,
    ): ObservationListResponse =
        ObservationListResponse(
            observations = observationService.listForPatient(
                principal = securityPrincipal(authentication),
                patientId = PatientId(patientId),
                category = category,
            ).map { it.toResponse() },
        )

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")
}

data class QuantityValueRequest(
    @field:NotNull
    val value: BigDecimal?,
    @field:NotNull
    val unit: String?,
)

data class RecordObservationRequest(
    @field:NotNull
    val codeConceptId: UUID?,
    @field:NotNull
    val category: ObservationCategory?,
    @field:NotNull
    val effectiveAt: Instant?,
    val encounterId: UUID? = null,
    val status: ObservationStatus? = null,
    val valueQuantity: QuantityValueRequest? = null,
    val valueConceptId: UUID? = null,
    val valueText: String? = null,
) {
    fun toObservationValue(): ObservationValue {
        val provided = listOfNotNull(valueQuantity, valueConceptId, valueText)
        if (provided.size != 1) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Exactly one of valueQuantity, valueConceptId, or valueText is required",
            )
        }
        return try {
            when {
                valueQuantity != null -> ObservationValue.Quantity(
                    value = valueQuantity.value!!,
                    unit = valueQuantity.unit!!,
                )
                valueConceptId != null -> ObservationValue.Coded(CodeableConceptId(valueConceptId))
                else -> ObservationValue.Text(valueText!!)
            }
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message)
        }
    }
}

data class ObservationValueResponse(
    val quantity: BigDecimal? = null,
    val unit: String? = null,
    val conceptId: String? = null,
    val text: String? = null,
)

data class ObservationResponse(
    val id: String,
    val organizationId: String,
    val patientId: String,
    val encounterId: String?,
    val status: String,
    val category: String,
    val codeConceptId: String,
    val value: ObservationValueResponse,
    val effectiveAt: Instant,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ObservationListResponse(
    val observations: List<ObservationResponse>,
)

private fun Observation.toResponse(): ObservationResponse =
    ObservationResponse(
        id = id.value.toString(),
        organizationId = organizationId.value.toString(),
        patientId = patientId.value.toString(),
        encounterId = encounterId?.value?.toString(),
        status = status.dbValue,
        category = category.dbValue,
        codeConceptId = codeConceptId.value.toString(),
        value = when (val observationValue = value) {
            is ObservationValue.Quantity -> ObservationValueResponse(
                quantity = observationValue.value,
                unit = observationValue.unit,
            )
            is ObservationValue.Coded -> ObservationValueResponse(
                conceptId = observationValue.conceptId.value.toString(),
            )
            is ObservationValue.Text -> ObservationValueResponse(
                text = observationValue.value,
            )
        },
        effectiveAt = effectiveAt,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
