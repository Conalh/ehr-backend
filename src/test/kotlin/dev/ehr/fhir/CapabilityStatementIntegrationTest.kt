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
                // exactly the nine served resources, nothing more
                jsonPath("$.rest[0].resource.length()") { value(9) }
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
                        ),
                    )
                }
                jsonPath("$.rest[0].resource[?(@.type=='Patient')].searchParam[0].name") { value("identifier") }
                jsonPath("$.rest[0].resource[?(@.type=='Observation')].searchParam[*].name") {
                    value(containsInAnyOrder("patient", "category"))
                }
                jsonPath("$.rest[0].resource[?(@.type=='Provenance')].searchParam[*].name") {
                    value(containsInAnyOrder("target", "patient"))
                }
                jsonPath("$.rest[0].resource[?(@.type=='Encounter')].interaction[*].code") {
                    value(containsInAnyOrder("read", "search-type"))
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
