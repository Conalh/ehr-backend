package dev.ehr.fhir

import dev.ehr.patient.PatientId
import dev.ehr.security.SecurityPrincipal
import dev.ehr.security.securityPrincipal
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

@Component
class FhirRequestSupport {
    fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.securityPrincipal()

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
