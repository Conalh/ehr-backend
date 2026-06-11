package dev.ehr.fhir

import dev.ehr.careteam.CareTeamMembership
import dev.ehr.careteam.CareTeamService
import dev.ehr.identity.User
import dev.ehr.identity.UserId
import dev.ehr.identity.UserRepository
import dev.ehr.patient.PatientId
import dev.ehr.security.SecurityPrincipal
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.OperationOutcome
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

@RestController
@RequestMapping("/fhir/r4")
class CareTeamFhirController(
    private val careTeamService: CareTeamService,
    private val careTeamFhirMapper: CareTeamFhirMapper,
    private val userRepository: UserRepository,
    private val responses: FhirResponseFactory,
) {
    @GetMapping("/CareTeam/{id}", produces = [FHIR_JSON])
    fun read(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        // The team's logical id is the patient's logical id.
        val patientId = parseUuid(id)?.let(::PatientId)
            ?: return responses.operationOutcome(
                HttpStatus.NOT_FOUND,
                OperationOutcome.IssueType.NOTFOUND,
                "CareTeam not found",
            )

        return try {
            val memberships = careTeamService.listForPatient(principal, patientId)
            responses.resource(
                HttpStatus.OK,
                careTeamFhirMapper.toFhirCareTeam(patientId, memberships, usersFor(memberships)),
            )
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    @GetMapping("/CareTeam", produces = [FHIR_JSON])
    fun search(
        authentication: Authentication,
        @RequestParam patient: String?,
    ): ResponseEntity<String> {
        val principal = securityPrincipal(authentication)
        val patientId = parsePatientParam(patient)
            ?: return responses.operationOutcome(
                HttpStatus.BAD_REQUEST,
                OperationOutcome.IssueType.INVALID,
                "The patient search parameter is required as a logical id or Patient/{id} reference",
            )

        return try {
            val memberships = careTeamService.listForPatient(principal, patientId)
            val team = careTeamFhirMapper.toFhirCareTeam(patientId, memberships, usersFor(memberships))
            val bundle = Bundle()
            bundle.type = Bundle.BundleType.SEARCHSET
            bundle.total = 1
            bundle.addLink(
                Bundle.BundleLinkComponent()
                    .setRelation("self")
                    .setUrl(ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString()),
            )
            bundle.addEntry(
                Bundle.BundleEntryComponent()
                    .setFullUrl(careTeamFullUrl(team.idElement.idPart))
                    .setResource(team)
                    .setSearch(
                        Bundle.BundleEntrySearchComponent()
                            .setMode(Bundle.SearchEntryMode.MATCH),
                    ),
            )
            responses.resource(HttpStatus.OK, bundle)
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    private fun usersFor(memberships: List<CareTeamMembership>): Map<UserId, User> =
        memberships.map { it.userId }.distinct()
            .mapNotNull { userRepository.findById(it) }
            .associateBy { it.id }

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")

    private fun parsePatientParam(patient: String?): PatientId? {
        if (patient.isNullOrBlank()) {
            return null
        }
        return parseUuid(patient.removePrefix("Patient/"))?.let(::PatientId)
    }

    private fun parseUuid(value: String): UUID? =
        runCatching { UUID.fromString(value) }.getOrNull()

    private fun careTeamFullUrl(idPart: String): String =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/fhir/r4/CareTeam/{id}")
            .buildAndExpand(idPart)
            .toUriString()
}
