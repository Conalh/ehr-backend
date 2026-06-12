package dev.ehr.fhir

import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.endsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class CapabilityStatementIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `metadata is public and accurately lists the served resources`() {
        mockMvc.get("/fhir/r4/metadata")
            .andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith("application/fhir+json") }
                jsonPath("$.resourceType") { value("CapabilityStatement") }
                jsonPath("$.status") { value("active") }
                jsonPath("$.kind") { value("instance") }
                jsonPath("$.fhirVersion") { value("4.0.1") }
                jsonPath("$.format[0]") { value("application/fhir+json") }
                jsonPath("$.software.name") { value("ehr-core") }
                jsonPath("$.rest[0].mode") { value("server") }
                jsonPath("$.rest[0].security.service[0].coding[0].code") { value("SMART-on-FHIR") }
                jsonPath("$.rest[0].security.extension[0].url") {
                    value("http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris")
                }
                jsonPath("$.rest[0].security.extension[0].extension[*].url") {
                    value(containsInAnyOrder("authorize", "token"))
                }
                jsonPath("$.rest[0].security.extension[0].extension[*].valueUri") {
                    value(org.hamcrest.Matchers.hasItem(endsWith("/oauth/token")))
                }
                // exactly the eleven served resources, nothing more
                jsonPath("$.rest[0].resource.length()") { value(11) }
                jsonPath("$.rest[0].resource[*].type") {
                    value(
                        containsInAnyOrder(
                            "Patient",
                            "Encounter",
                            "Condition",
                            "AllergyIntolerance",
                            "Observation",
                            "MedicationStatement",
                            "DocumentReference",
                            "DiagnosticReport",
                            "Provenance",
                            "CareTeam",
                            "Practitioner",
                        ),
                    )
                }
                jsonPath("$.rest[0].resource[?(@.type=='Patient')].searchParam[0].name") { value("identifier") }
                // Profiles are declared only where the conformance suite proves them.
                jsonPath("$.rest[0].resource[?(@.type=='Patient')].supportedProfile[0]") {
                    value("http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient")
                }
                jsonPath("$.rest[0].resource[?(@.type=='Observation')].supportedProfile[*]") {
                    value(
                        containsInAnyOrder(
                            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-vital-signs",
                            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-observation-lab",
                        ),
                    )
                }
                jsonPath("$.rest[0].resource[?(@.type=='DiagnosticReport')].supportedProfile[0]") {
                    value("http://hl7.org/fhir/us/core/StructureDefinition/us-core-diagnosticreport-lab")
                }
                jsonPath("$.rest[0].resource[?(@.type=='Condition')].supportedProfile[0]") {
                    value("http://hl7.org/fhir/us/core/StructureDefinition/us-core-condition-problems-health-concerns")
                }
                jsonPath("$.rest[0].resource[?(@.type=='AllergyIntolerance')].supportedProfile[0]") {
                    value("http://hl7.org/fhir/us/core/StructureDefinition/us-core-allergyintolerance")
                }
                jsonPath("$.rest[0].resource[?(@.type=='CareTeam')].supportedProfile[0]") {
                    value("http://hl7.org/fhir/us/core/StructureDefinition/us-core-careteam")
                }
                jsonPath("$.rest[0].resource[?(@.type=='Provenance')].supportedProfile[0]") {
                    value("http://hl7.org/fhir/us/core/StructureDefinition/us-core-provenance")
                }
                // The provenance revinclude is declared exactly where implemented.
                jsonPath("$.rest[0].resource[?(@.type=='Condition')].searchRevInclude[0]") {
                    value("Provenance:target")
                }
                jsonPath("$.rest[0].resource[?(@.type=='Observation')].searchRevInclude[0]") {
                    value("Provenance:target")
                }
                jsonPath("$.rest[0].resource[?(@.type=='Encounter')].searchRevInclude") {
                    value(org.hamcrest.Matchers.empty<Any>())
                }
                // Recorded demotions claim nothing.
                jsonPath("$.rest[0].resource[?(@.type=='Encounter')].supportedProfile") {
                    value(org.hamcrest.Matchers.empty<Any>())
                }
                jsonPath("$.rest[0].resource[?(@.type=='Practitioner')].supportedProfile") {
                    value(org.hamcrest.Matchers.empty<Any>())
                }
                jsonPath("$.rest[0].resource[?(@.type=='Observation')].searchParam[*].name") {
                    value(containsInAnyOrder("patient", "category", "code", "date"))
                }
                jsonPath("$.rest[0].resource[?(@.type=='Patient')].searchParam[*].name") {
                    value(containsInAnyOrder("identifier", "_id"))
                }
                jsonPath("$.rest[0].resource[?(@.type=='Condition')].searchParam[*].name") {
                    value(containsInAnyOrder("patient", "category", "clinical-status"))
                }
                jsonPath("$.rest[0].resource[?(@.type=='DiagnosticReport')].searchParam[*].name") {
                    value(containsInAnyOrder("patient", "category", "code", "date"))
                }
                jsonPath("$.rest[0].resource[?(@.type=='CareTeam')].searchParam[*].name") {
                    value(containsInAnyOrder("patient", "status"))
                }
                jsonPath("$.rest[0].resource[?(@.type=='Provenance')].searchParam[*].name") {
                    value(containsInAnyOrder("target", "patient"))
                }
                jsonPath("$.rest[0].resource[?(@.type=='Encounter')].interaction[*].code") {
                    value(containsInAnyOrder("read", "search-type"))
                }
                // Read-only resources never advertise search.
                jsonPath("$.rest[0].resource[?(@.type=='Practitioner')].interaction[*].code") {
                    value(containsInAnyOrder("read"))
                }
            }
    }

    @Test
    fun `other fhir routes remain protected`() {
        mockMvc.get("/fhir/r4/Patient/${UUID.randomUUID()}")
            .andExpect {
                status { isUnauthorized() }
            }
    }
}
