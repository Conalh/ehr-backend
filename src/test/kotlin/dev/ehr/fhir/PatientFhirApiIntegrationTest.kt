package dev.ehr.fhir

import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.TenantScope
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
import dev.ehr.patient.Patient
import dev.ehr.patient.PatientCreateCommand
import dev.ehr.patient.PatientIdentifierCreateCommand
import dev.ehr.patient.PatientRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class PatientFhirApiIntegrationTest : PostgresIntegrationTest() {
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
    fun `fhir patient endpoints reject unauthenticated requests without audit`() {
        val correlationId = "fhir-unauth-${UUID.randomUUID()}"

        mockMvc.get("/fhir/r4/Patient/${UUID.randomUUID()}") {
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isUnauthorized() }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `clinician can read a fhir patient and the read is audited`() {
        val correlationId = "fhir-read-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Patient.read")
        val patient = createPatient(
            member.organization,
            birthDate = LocalDate.of(1985, 7, 14),
        )
        val identifierSystem = "urn:ehr:mrn:${UUID.randomUUID()}"
        patientRepository.addIdentifier(
            TenantScope(member.organization.id),
            patient.id,
            PatientIdentifierCreateCommand(system = identifierSystem, value = "MRN-FHIR-1"),
        )

        mockMvc.get("/fhir/r4/Patient/${patient.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/fhir+json") }
            jsonPath("$.resourceType") { value("Patient") }
            jsonPath("$.id") { value(patient.id.value.toString()) }
            jsonPath("$.meta.versionId") { value("1") }
            jsonPath("$.active") { value(true) }
            jsonPath("$.name[0].family") { value("Patient") }
            jsonPath("$.name[0].given[0]") { value("Synthetic") }
            jsonPath("$.birthDate") { value("1985-07-14") }
            jsonPath("$.identifier[0].system") { value(identifierSystem) }
            jsonPath("$.identifier[0].value") { value("MRN-FHIR-1") }
        }

        val audit = auditRow(correlationId)
        assertEquals("PATIENT", audit.resourceType)
        assertEquals("READ", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
    }

    @Test
    fun `cross organization fhir read returns operation outcome not found and audits failure`() {
        val correlationId = "fhir-read-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Patient.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)

        mockMvc.get("/fhir/r4/Patient/${otherPatient.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith("application/fhir+json") }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].severity") { value("error") }
            jsonPath("$.issue[0].code") { value("not-found") }
        }

        val audit = auditRow(correlationId)
        assertEquals("READ", audit.operation)
        assertEquals("FAILURE", audit.outcome)
        assertEquals(null, audit.patientId)
        assertEquals(otherPatient.id.value.toString(), audit.resourceId)
    }

    @Test
    fun `non uuid fhir patient id returns operation outcome not found without audit`() {
        val correlationId = "fhir-read-badid-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.CLINICIAN, "user/Patient.read")

        mockMvc.get("/fhir/r4/Patient/not-a-uuid") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("not-found") }
        }

        assertEquals(0, auditCount(correlationId))
    }

    @Test
    fun `org admin fhir read returns operation outcome forbidden and audits denial`() {
        val correlationId = "fhir-read-admin-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.ORG_ADMIN, "user/*.read")
        val patient = createPatient(member.organization)

        mockMvc.get("/fhir/r4/Patient/${patient.id.value}") {
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isForbidden() }
            content { contentTypeCompatibleWith("application/fhir+json") }
            jsonPath("$.resourceType") { value("OperationOutcome") }
            jsonPath("$.issue[0].code") { value("forbidden") }
        }

        val audit = auditRow(correlationId)
        assertEquals("AUTHORIZATION_DENIED", audit.operation)
        assertEquals("DENIED", audit.outcome)
        assertEquals("INSUFFICIENT_ROLE", audit.policyReasonCode)
    }

    @Test
    fun `fhir identifier search returns searchset bundle and audits the search`() {
        val correlationId = "fhir-search-hit-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/Patient.read")
        val patient = createPatient(member.organization)
        val identifierSystem = "urn:ehr:mrn:${UUID.randomUUID()}"
        patientRepository.addIdentifier(
            TenantScope(member.organization.id),
            patient.id,
            PatientIdentifierCreateCommand(system = identifierSystem, value = "MRN-FHIR-7"),
        )

        mockMvc.get("/fhir/r4/Patient") {
            param("identifier", "$identifierSystem|MRN-FHIR-7")
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/fhir+json") }
            jsonPath("$.resourceType") { value("Bundle") }
            jsonPath("$.type") { value("searchset") }
            jsonPath("$.total") { value(1) }
            jsonPath("$.entry[0].fullUrl") { value(org.hamcrest.Matchers.endsWith("/fhir/r4/Patient/${patient.id.value}")) }
            jsonPath("$.entry[0].search.mode") { value("match") }
            jsonPath("$.entry[0].resource.resourceType") { value("Patient") }
            jsonPath("$.entry[0].resource.id") { value(patient.id.value.toString()) }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(patient.id.value.toString(), audit.patientId)
    }

    @Test
    fun `fhir identifier search does not return other organizations patients`() {
        val correlationId = "fhir-search-cross-${UUID.randomUUID()}"
        val member = createMember(MembershipRole.STAFF, "user/Patient.read")
        val otherOrganization = createOrganization()
        val otherPatient = createPatient(otherOrganization)
        val identifierSystem = "urn:ehr:mrn:${UUID.randomUUID()}"
        patientRepository.addIdentifier(
            TenantScope(otherOrganization.id),
            otherPatient.id,
            PatientIdentifierCreateCommand(system = identifierSystem, value = "MRN-OTHER"),
        )

        mockMvc.get("/fhir/r4/Patient") {
            param("identifier", "$identifierSystem|MRN-OTHER")
            header("Authorization", "Bearer ${member.token}")
            header("X-Correlation-Id", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.resourceType") { value("Bundle") }
            jsonPath("$.type") { value("searchset") }
            jsonPath("$.total") { value(0) }
        }

        val audit = auditRow(correlationId)
        assertEquals("SEARCH", audit.operation)
        assertEquals("SUCCESS", audit.outcome)
        assertEquals(null, audit.patientId)
    }

    @Test
    fun `fhir search without usable identifier token returns operation outcome invalid`() {
        val member = createMember(MembershipRole.STAFF, "user/Patient.read")

        listOf(
            null,
            "missing-separator",
            "|value-only",
            "system-only|",
        ).forEach { identifier ->
            mockMvc.get("/fhir/r4/Patient") {
                if (identifier != null) {
                    param("identifier", identifier)
                }
                header("Authorization", "Bearer ${member.token}")
            }.andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith("application/fhir+json") }
                jsonPath("$.resourceType") { value("OperationOutcome") }
                jsonPath("$.issue[0].code") { value("invalid") }
            }
        }
    }

    private fun createOrganization(): Organization {
        val suffix = UUID.randomUUID()
        return organizationRepository.create(
            slug = "fhir-org-$suffix",
            displayName = "Fhir Org $suffix",
        )
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): FhirMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = createOrganization()
        val user = userRepository.create(
            externalSubject = "fhir-user-$suffix",
            email = "fhir-user-$suffix@example.test",
            displayName = "Fhir User $suffix",
        )
        val membership = membershipRepository.create(
            organizationId = organization.id,
            userId = user.id,
        )
        membershipRepository.addRole(membership.id, role)

        return FhirMemberFixture(
            organization = organization,
            user = user,
            token = DevJwtFactory(jwtEncoder).tokenFor(
                user = user,
                organization = organization,
                scopes = scopes,
            ),
        )
    }

    private fun createPatient(
        organization: Organization,
        birthDate: LocalDate? = null,
    ): Patient =
        patientRepository.create(
            PatientCreateCommand(
                organizationId = organization.id,
                givenName = "Synthetic",
                familyName = "Patient",
                birthDate = birthDate,
            ),
        )

    private fun auditCount(correlationId: String): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from audit_events where correlation_id = ?",
            Int::class.java,
            correlationId,
        )!!

    private fun auditRow(correlationId: String): FhirAuditRow {
        assertEquals(1, auditCount(correlationId), "expected exactly one audit row for $correlationId")
        return jdbcTemplate.queryForObject(
            """
            select
              patient_id::text,
              resource_type,
              resource_id::text,
              operation,
              outcome,
              policy_reason_code
            from audit_events
            where correlation_id = ?
            """.trimIndent(),
            { rs, _ ->
                FhirAuditRow(
                    patientId = rs.getString("patient_id"),
                    resourceType = rs.getString("resource_type"),
                    resourceId = rs.getString("resource_id"),
                    operation = rs.getString("operation"),
                    outcome = rs.getString("outcome"),
                    policyReasonCode = rs.getString("policy_reason_code"),
                )
            },
            correlationId,
        )!!
    }
}

data class FhirMemberFixture(
    val organization: Organization,
    val user: User,
    val token: String,
)

data class FhirAuditRow(
    val patientId: String?,
    val resourceType: String,
    val resourceId: String?,
    val operation: String,
    val outcome: String,
    val policyReasonCode: String?,
)
