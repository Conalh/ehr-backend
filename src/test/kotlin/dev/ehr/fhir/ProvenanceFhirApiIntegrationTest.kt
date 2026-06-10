package dev.ehr.fhir

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.TenantScope
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
import dev.ehr.provenance.ProvenanceRepository
import dev.ehr.terminology.CanonicalCodeSystems
import dev.ehr.terminology.CodeableConcept
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import dev.ehr.testsupport.TerminologyTestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class ProvenanceFhirApiIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var membershipRepository: MembershipRepository

    @Autowired
    lateinit var provenanceRepository: ProvenanceRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    lateinit var snomedConcept: CodeableConcept

    @BeforeEach
    fun setUpConcepts() {
        snomedConcept = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
            .findOrCreateConcept(
                system = CanonicalCodeSystems.SNOMED_CT,
                code = "38341003",
                display = "Hypertensive disorder",
            )
    }

    @Test
    fun `fhir provenance endpoints reject unauthenticated requests`() {
        mockMvc.get("/fhir/r4/Provenance/${UUID.randomUUID()}")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `clinician can read provenance by id with fhir shape`() {
        val member = createMember(MembershipRole.CLINICIAN)
        val patientId = createPatient(member)
        val event = provenanceRepository
            .findByTarget(TenantScope(member.organization.id), "PATIENT", patientId)
            .single()

        mockMvc.get("/fhir/r4/Provenance/${event.id}") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/fhir+json") }
            jsonPath("$.resourceType") { value("Provenance") }
            jsonPath("$.id") { value(event.id.toString()) }
            jsonPath("$.target[0].reference") { value("Patient/$patientId") }
            jsonPath("$.recorded") { exists() }
            jsonPath("$.activity.coding[0].system") { value("http://terminology.hl7.org/CodeSystem/v3-DataOperation") }
            jsonPath("$.activity.coding[0].code") { value("CREATE") }
            jsonPath("$.activity.text") { value("created") }
            jsonPath("$.agent[0].who.identifier.system") { value("urn:ehr:user-id") }
            jsonPath("$.agent[0].who.identifier.value") { value(member.user.id.value.toString()) }
        }
    }

    @Test
    fun `target search returns the provenance chain for a condition including amendments`() {
        val member = createMember(MembershipRole.CLINICIAN)
        val patientId = createPatient(member)
        val conditionId = postForId(
            member,
            "/api/v1/patients/$patientId/conditions",
            """{"codeConceptId":"${snomedConcept.id.value}"}""",
        )
        mockMvc.patch("/api/v1/conditions/$conditionId") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":1,"clinicalStatus":"RESOLVED"}"""
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
        }

        mockMvc.get("/fhir/r4/Provenance") {
            param("target", "Condition/$conditionId")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.resourceType") { value("Bundle") }
            jsonPath("$.type") { value("searchset") }
            jsonPath("$.total") { value(2) }
            jsonPath("$.entry[0].resource.activity.coding[0].code") { value("CREATE") }
            jsonPath("$.entry[1].resource.activity.coding[0].code") { value("UPDATE") }
            jsonPath("$.entry[1].resource.target[0].reference") { value("Condition/$conditionId") }
        }
    }

    @Test
    fun `patient search returns the compartment provenance`() {
        val member = createMember(MembershipRole.CLINICIAN)
        val patientId = createPatient(member)
        postForId(
            member,
            "/api/v1/patients/$patientId/conditions",
            """{"codeConceptId":"${snomedConcept.id.value}"}""",
        )

        mockMvc.get("/fhir/r4/Provenance") {
            param("patient", patientId.toString())
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.total") { value(2) }
        }
    }

    @Test
    fun `provenance search errors and access control`() {
        val member = createMember(MembershipRole.CLINICIAN)
        val staff = createMember(MembershipRole.STAFF)
        val patientId = createPatient(member)

        // unsupported target type
        mockMvc.get("/fhir/r4/Provenance") {
            param("target", "Practitioner/${UUID.randomUUID()}")
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.issue[0].code") { value("invalid") }
        }

        // missing params
        mockMvc.get("/fhir/r4/Provenance") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        // staff forbidden
        mockMvc.get("/fhir/r4/Provenance") {
            param("patient", patientId.toString())
            header("Authorization", "Bearer ${staff.token}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.issue[0].code") { value("forbidden") }
        }

        // cross-tenant patient search
        val outsider = createMember(MembershipRole.CLINICIAN)
        mockMvc.get("/fhir/r4/Provenance") {
            param("patient", patientId.toString())
            header("Authorization", "Bearer ${outsider.token}")
        }.andExpect {
            status { isNotFound() }
        }

        // cross-tenant read by id
        val event = provenanceRepository
            .findByTarget(TenantScope(member.organization.id), "PATIENT", patientId)
            .single()
        mockMvc.get("/fhir/r4/Provenance/${event.id}") {
            header("Authorization", "Bearer ${outsider.token}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.issue[0].code") { value("not-found") }
        }
    }

    private fun createPatient(member: ProvenanceFhirMemberFixture): UUID =
        postForId(
            member,
            "/api/v1/patients",
            """{"givenName":"Synthetic","familyName":"Patient"}""",
        )

    private fun postForId(
        member: ProvenanceFhirMemberFixture,
        path: String,
        body: String,
    ): UUID {
        val response = mockMvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = body
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isCreated() }
        }.andReturn().response.contentAsString
        return UUID.fromString(Regex("\"id\":\"([0-9a-f-]+)\"").find(response)!!.groupValues[1])
    }

    private fun createMember(role: MembershipRole): ProvenanceFhirMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "prov-fhir-org-$suffix",
            displayName = "Prov Fhir Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "prov-fhir-user-$suffix",
            email = "prov-fhir-user-$suffix@example.test",
            displayName = "Prov Fhir User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return ProvenanceFhirMemberFixture(
            organization = organization,
            user = user,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = "user/*.read user/*.write",
            ),
        )
    }
}

data class ProvenanceFhirMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)
