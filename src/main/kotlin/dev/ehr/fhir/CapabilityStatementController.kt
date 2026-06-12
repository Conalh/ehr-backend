package dev.ehr.fhir

import org.hl7.fhir.r4.model.CapabilityStatement
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.UriType
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.Date

@RestController
@RequestMapping("/fhir/r4")
class CapabilityStatementController(
    private val responses: FhirResponseFactory,
) {
    @GetMapping("/metadata", produces = [FHIR_JSON])
    fun metadata(): ResponseEntity<String> {
        val base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()
        val capability = CapabilityStatement()

        capability.status = Enumerations.PublicationStatus.ACTIVE
        capability.date = Date()
        capability.kind = CapabilityStatement.CapabilityStatementKind.INSTANCE
        capability.software = CapabilityStatement.CapabilityStatementSoftwareComponent()
            .setName("ehr-core")
        capability.fhirVersion = Enumerations.FHIRVersion._4_0_1
        capability.addFormat("application/fhir+json")

        val rest = CapabilityStatement.CapabilityStatementRestComponent()
        rest.mode = CapabilityStatement.RestfulCapabilityMode.SERVER
        val security = CapabilityStatement.CapabilityStatementRestSecurityComponent()
        security.addService(
            CodeableConcept().addCoding(
                Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/restful-security-service")
                    .setCode("SMART-on-FHIR"),
            ),
        )
        val oauthUris = Extension("http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris")
        oauthUris.addExtension(Extension("authorize", UriType("$base/oauth/authorize")))
        oauthUris.addExtension(Extension("token", UriType("$base/oauth/token")))
        security.addExtension(oauthUris)
        rest.security = security

        FhirCapabilityRegistry.supportedResources.forEach { supported ->
            val resource = CapabilityStatement.CapabilityStatementRestResourceComponent()
            resource.type = supported.type
            supported.profile?.let { resource.addSupportedProfile(it) }
            resource.addInteraction(
                CapabilityStatement.ResourceInteractionComponent()
                    .setCode(CapabilityStatement.TypeRestfulInteraction.READ),
            )
            // Never advertise an interaction that does not exist: search is
            // claimed only for resources with declared search parameters.
            if (supported.searchParams.isNotEmpty()) {
                resource.addInteraction(
                    CapabilityStatement.ResourceInteractionComponent()
                        .setCode(CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE),
                )
            }
            supported.searchParams.forEach { param ->
                resource.addSearchParam(
                    CapabilityStatement.CapabilityStatementRestResourceSearchParamComponent()
                        .setName(param.name)
                        .setType(
                            when (param.type) {
                                FhirCapabilityRegistry.SearchParamType.TOKEN -> Enumerations.SearchParamType.TOKEN
                                FhirCapabilityRegistry.SearchParamType.REFERENCE -> Enumerations.SearchParamType.REFERENCE
                            },
                        )
                        .setDocumentation(param.documentation),
                )
            }
            rest.addResource(resource)
        }
        capability.addRest(rest)

        return responses.resource(HttpStatus.OK, capability)
    }
}
