package dev.ehr.patient

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.TenantScope
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class PatientApiIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var membershipRepository: MembershipRepository

    @Autowired
    lateinit var patientRepository: PatientRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    @Test
    fun `patient endpoints reject unauthenticated requests without audit`() {
        val correlationId = "patient-unauth-${UUID.randomUUID()}"

        mockMvc.get("/api/v1/patients/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        mockMvc.post("/api/v1/patients") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"givenName":"Synthetic","familyName":"Patient"}"""
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can create a patient with identifiers and the create is audited`() {
        val correlationId = "patient-create-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Patient.write user/Patient.read")
        val identifierSystem = "urn:ehr:mrn:${UUID.randomUUID()}"

        val responseBody = mockMvc.post("/api/v1/patients") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "givenName": "Synthetic",
                  "familyName": "Patient",
                  "birthDate": "1990-04-02",
                  "administrativeGender": "FEMALE",
                  "identifiers": [
                    {"system": "$identifierSystem", "value": "MRN-001", "use": "OFFICIAL"}
                  ]
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
            jsonPath("$.organizationId") { value(member.organization.id.value.toString()) }
            jsonPath("$.status") { value("active") }
            jsonPath("$.givenName") { value("Synthetic") }
            jsonPath("$.familyName") { value("Patient") }
            jsonPath("$.birthDate") { value("1990-04-02") }
            jsonPath("$.administrativeGender") { value("female") }
            jsonPath("$.version") { value(1) }
            jsonPath("$.identifiers[0].system") { value(identifierSystem) }
            jsonPath("$.identifiers[0].value") { value("MRN-001") }
            jsonPath("$.identifiers[0].use") { value("official") }
        }.andReturn().response.contentAsString

        val patientId = UUID.fromString(Regex("\"id\":\"([0-9a-f-]+)\"").find(responseBody)!!.groupValues[1])
        val stored = patientRepository.findById(TenantScope(member.organization.id), PatientId(patientId))!!
        assertEquals("Synthetic", stored.givenName)
        assertEquals(member.user.id, stored.createdBy)

        val audit = auditRow(correlationId)
        assertEquals(member.organization.id.value.toString(), audit.organizationId)
        assertEquals(member.user.id.value.toString(), audit.subjectUserId)
        assertEquals(patientId.toString(), audit.patientId)
        assertEquals("PATIENT", audit.resourceType)
        assertEquals(patientId.toString(), audit.resourceId)
        assertEquals("CREATE", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals("policy-spine-v20", audit.policyVersion)
        assertEquals("ALLOWED", audit.policyReasonCode)
    }

    @Test
    fun `staff cannot create a patient and the denial is audited`() {
        val correlationId = "patient-create-deny-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/Patient.write user/Patient.read")

        mockMvc.post("/api/v1/patients") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"givenName":"Synthetic","familyName":"Patient"}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isForbidden() }
        }

        assertEquals(0, patientCount(member.organization))

        val audit = auditRow(correlationId)
        assertEquals("PATIENT", audit.resourceType)
        assertEquals("AUTHORIZATION_DENIED", audit.operation)
        assertEquals("DENIED", audit.outcome)
        assertEquals("INSUFFICIENT_ROLE", audit.policyReasonCode)
        assertEquals(null, audit.patientId)
    }

    @Test
    fun `clinician without write scope cannot create a patient`() {
        val correlationId = "patient-create-scope-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Patient.read")

        mockMvc.post("/api/v1/patients") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"givenName":"Synthetic","familyName":"Patient"}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isForbidden() }
        }

        val audit = auditRow(correlationId)
        assertEquals("AUTHORIZATION_DENIED", audit.operation)
        assertEquals("INSUFFICIENT_SCOPE", audit.policyReasonCode)
    }

    @Test
    fun `clinician and staff can read a patient in their organization and reads are audited`() {
        listOf(
            MembershipRole.CLINICIAN,
            MembershipRole.STAFF,
        ).forEach { role ->
            val correlationId = "patient-read-$role-${UUID.randomUUID()}"
            val member = createMember(role, "user/Patient.read")
            val patient = createPatient(member.organization)

            mockMvc.get("/api/v1/patients/${patient.id.value}") {
                header("Authorization", "Bearer ${member.token}")
                header("X-Correlation-Id", correlationId)
            }.andExpect {
                status { isOk() }
                jsonPath("$.id") { value(patient.id.value.toString()) }
                jsonPath("$.givenName") { value(patient.givenName) }
                jsonPath("$.identifiers") { isArray() }
            }

            val audit = auditRow(correlationId)
            assertEquals(patient.id.value.toString(), audit.patientId)
            assertEquals(patient.id.value.toString(), audit.resourceId)
            assertEquals("READ", audit.operation)
            assertEquals("SUCCESS", audit.outcome)
        }
    }

    @Test
    fun `cross organization patient read returns 404 and audits a failed read`() {
        val correlationId = "patient-read-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Patient.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)

        mockMvc.get("/api/v1/patients/${otherPatient.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
        }

        val audit = auditRow(correlationId)
        assertEquals(member.organization.id.value.toString(), audit.organizationId)
        assertEquals(null, audit.patientId)
        assertEquals(otherPatient.id.value.toString(), audit.resourceId)
        assertEquals("READ", audit.operation)
        assertEquals("FAILURE", audit.outcome)
    }

    @Test
    fun `org admin cannot read patients by default`() {
        val correlationId = "patient-read-admin-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.ORG_ADMIN, "user/*.read")
        val patient = createPatient(member.organization)

        mockMvc.get("/api/v1/patients/${patient.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isForbidden() }
        }

        val audit = auditRow(correlationId)
        assertEquals("AUTHORIZATION_DENIED", audit.operation)
        assertEquals("INSUFFICIENT_ROLE", audit.policyReasonCode)
    }

    @Test
    fun `identifier search returns tenant scoped match and audits the search`() {
        val hitCorrelationId = "patient-search-hit-${UUID.randomUUID()}"
        val missCorrelationId = "patient-search-miss-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/Patient.read")
        val patient = createPatient(member.organization)
        val identifierSystem = "urn:ehr:mrn:${UUID.randomUUID()}"
        patientRepository.addIdentifier(
            TenantScope(member.organization.id),
            patient.id,
            PatientIdentifierCreateCommand(system = identifierSystem, value = "MRN-77"),
        )

        // Same identifier in another organization must stay invisible to this tenant.
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        patientRepository.addIdentifier(
            TenantScope(otherOrganization.id),
            otherPatient.id,
            PatientIdentifierCreateCommand(system = identifierSystem, value = "MRN-99"),
        )

        mockMvc.get("/api/v1/patients") {
            param("identifierSystem", identifierSystem)
            param("identifierValue", "MRN-77")
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", hitCorrelationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.patients.length()") { value(1) }
            jsonPath("$.patients[0].id") { value(patient.id.value.toString()) }
            jsonPath("$.patients[0].identifiers[0].system") { value(identifierSystem) }
        }

        val hitAudit = auditRow(hitCorrelationId)
        assertEquals(patient.id.value.toString(), hitAudit.patientId)
        assertEquals("SEARCH", hitAudit.operation)
        assertEquals("SUCCESS", hitAudit.outcome)

        mockMvc.get("/api/v1/patients") {
            param("identifierSystem", identifierSystem)
            param("identifierValue", "MRN-99")
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", missCorrelationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.patients.length()") { value(0) }
        }

        val missAudit = auditRow(missCorrelationId)
        assertEquals(null, missAudit.patientId)
        assertEquals("SEARCH", missAudit.operation)
        assertEquals("SUCCESS", missAudit.outcome)
    }

    @Test
    fun `create rejects blank names without writing patient rows`() {
        val correlationId = "patient-create-invalid-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Patient.write")

        mockMvc.post("/api/v1/patients") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"givenName":"  ","familyName":"Patient"}"""
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isBadRequest() }
        }

        assertEquals(0, patientCount(member.organization))
    }

    @Test
    fun `create rejects invalid identifier period without writing patient rows`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Patient.write")

        mockMvc.post("/api/v1/patients") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "givenName": "Synthetic",
                  "familyName": "Patient",
                  "identifiers": [
                    {"system": "urn:ehr:mrn", "value": "MRN-1", "periodStart": "2024-05-01", "periodEnd": "2024-01-01"}
                  ]
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
        }

        assertEquals(0, patientCount(member.organization))
    }

    @Test
    fun `create rejects duplicate identifier in same organization with conflict`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/Patient.write")
        val patient = createPatient(member.organization)
        val identifierSystem = "urn:ehr:mrn:${UUID.randomUUID()}"
        patientRepository.addIdentifier(
            TenantScope(member.organization.id),
            patient.id,
            PatientIdentifierCreateCommand(system = identifierSystem, value = "MRN-DUP"),
        )

        mockMvc.post("/api/v1/patients") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "givenName": "Synthetic",
                  "familyName": "Patient",
                  "identifiers": [
                    {"system": "$identifierSystem", "value": "MRN-DUP"}
                  ]
                }
            """.trimIndent()
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isConflict() }
        }

        assertEquals(1, patientCount(member.organization))
    }

    private fun createOrganization(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "patient-api-org-$suffix",
            displayName = "Patient Api Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): MemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "patient-api-user-$suffix",
            email = "patient-api-user-$suffix@example.test",
            displayName = "Patient Api User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return MemberFixture(
            organization = organization,
            user = user,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = scopes,
            ),
        )
    }

    private fun createPatient(organization: Organization): Patient =
        patientRepository.create(
            PatientCreateCommand(
                organizationId = organization.id,
                givenName = "Synthetic",
                familyName = "Patient",
            ),
        )

    private fun patientCount(organization: Organization): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from patients where organization_id = ?",
            Int::class.java,
            organization.id.value,
        )!!

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): PatientAuditRow {
        assertEquals(1, auditCount(correlationId), "expected exactly one audit row for $correlationId")
        return jdbcTemplate.queryForObject(
            """
            select
              organization_id::text,
              subject_user_id::text,
              patient_id::text,
              resource_type,
              resource_id::text,
              operation,
              outcome,
              policy_version,
              policy_reason_code,
              metadata::text
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                PatientAuditRow(
                    organizationId = rs.getString("organization_id"),
                    subjectUserId = rs.getString("subject_user_id"),
                    patientId = rs.getString("patient_id"),
                    resourceType = rs.getString("resource_type"),
                    resourceId = rs.getString("resource_id"),
                    operation = rs.getString("operation"),
                    outcome = rs.getString("outcome"),
                    policyVersion = rs.getString("policy_version"),
                    policyReasonCode = rs.getString("policy_reason_code"),
                    metadata = rs.getString("metadata"),
                )
            },
            correlationId,
        )!!
    }
}

data class MemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class PatientAuditRow(
    val organizationId: String?,
    val subjectUserId: String?,
    val patientId: String?,
    val resourceType: String,
    val resourceId: String?,
    val operation: String,
    val outcome: String,
    val policyVersion: String?,
    val policyReasonCode: String?,
    val metadata: String,
)
