package dev.ehr.fhir

import dev.ehr.careteam.CareTeamMembership
import dev.ehr.careteam.CareTeamService
import dev.ehr.identity.User
import dev.ehr.identity.UserId
import dev.ehr.identity.UserRepository
import dev.ehr.patient.PatientId
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

@RestController
@RequestMapping("/fhir/r4")
class CareTeamFhirController(
    private val careTeamService: CareTeamService,
    private val careTeamFhirMapper: CareTeamFhirMapper,
    private val userRepository: UserRepository,
    private val responses: FhirResponseFactory,
    private val requestSupport: FhirRequestSupport,
) {
    @GetMapping("/CareTeam/{id}", produces = [FHIR_JSON])
    fun read(
        authentication: Authentication,
        @PathVariable id: String,
    ): ResponseEntity<String> {
        val principal = requestSupport.securityPrincipal(authentication)
        // The team's logical id is the patient's logical id.
        val patientId = requestSupport.parseUuid(id)?.let(::PatientId)
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
        @RequestParam(name = "status") status: String?,
    ): ResponseEntity<String> {
        val principal = requestSupport.securityPrincipal(authentication)
        val patientId = requestSupport.parsePatientParam(patient)
            ?: return responses.operationOutcome(
                HttpStatus.BAD_REQUEST,
                OperationOutcome.IssueType.INVALID,
                "The patient search parameter is required as a logical id or Patient/{id} reference",
            )

        return try {
            // The served team is always active: a matching status returns it,
            // any other an honest empty bundle.
            val statusMatches = status?.let(FhirTokenParam::parse)
                ?.matches(null, "active")
                ?: true
            val memberships = careTeamService.listForPatient(principal, patientId)
            val bundle = Bundle()
            bundle.type = Bundle.BundleType.SEARCHSET
            bundle.addLink(
                Bundle.BundleLinkComponent()
                    .setRelation("self")
                    .setUrl(ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString()),
            )
            if (statusMatches) {
                val team = careTeamFhirMapper.toFhirCareTeam(patientId, memberships, usersFor(memberships))
                bundle.total = 1
                bundle.addEntry(
                    Bundle.BundleEntryComponent()
                        .setFullUrl(requestSupport.resourceFullUrl("CareTeam", team.idElement.idPart))
                        .setResource(team)
                        .setSearch(
                            Bundle.BundleEntrySearchComponent()
                                .setMode(Bundle.SearchEntryMode.MATCH),
                        ),
                )
            } else {
                bundle.total = 0
            }
            responses.resource(HttpStatus.OK, bundle)
        } catch (exception: ResponseStatusException) {
            responses.fromStatusException(exception)
        }
    }

    private fun usersFor(memberships: List<CareTeamMembership>): Map<UserId, User> =
        memberships.map { it.userId }.distinct()
            .mapNotNull { userRepository.findById(it) }
            .associateBy { it.id }
}
