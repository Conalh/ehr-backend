package dev.ehr.fhir

import dev.ehr.patient.PatientId
import dev.ehr.security.SecurityPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

@Component
class FhirRequestSupport {
    fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")

    fun parsePatientParam(patient: String?): PatientId? {
        if (patient.isNullOrBlank()) {
            return null
        }
        return parseUuid(patient.removePrefix("Patient/"))?.let(::PatientId)
    }

    fun parseUuid(value: String): UUID? =
        runCatching { UUID.fromString(value) }.getOrNull()

    fun resourceFullUrl(
        resourceType: String,
        idPart: String,
    ): String =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/fhir/r4/{resourceType}/{id}")
            .buildAndExpand(resourceType, idPart)
            .toUriString()
}
