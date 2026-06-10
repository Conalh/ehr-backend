package dev.ehr.fhir

import dev.ehr.diagnostics.DiagnosticReport
import dev.ehr.diagnostics.DiagnosticReportCreateCommand
import dev.ehr.diagnostics.DiagnosticReportRepository
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import dev.ehr.observation.Observation
import dev.ehr.observation.ObservationCategory
import dev.ehr.observation.ObservationCreateCommand
import dev.ehr.observation.ObservationRepository
import dev.ehr.observation.ObservationValue
import dev.ehr.order.Order
import dev.ehr.order.OrderCreateCommand
import dev.ehr.order.OrderRepository
import dev.ehr.patient.Patient
import dev.ehr.patient.PatientCreateCommand
import dev.ehr.patient.PatientRepository
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class DiagnosticReportFhirApiIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var orderRepository: OrderRepository

    @Autowired
    lateinit var observationRepository: ObservationRepository

    @Autowired
    lateinit var diagnosticReportRepository: DiagnosticReportRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: JwtEncoder

    lateinit var panelConcept: CodeableConcept

    @BeforeEach
    fun setUpConcept() {
        panelConcept = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
            .findOrCreateConcept(
                system = CanonicalCodeSystems.LOINC,
                code = "24323-8",
                display = "Comprehensive metabolic panel",
            )
    }

    @Test
    fun `clinician can read a fhir diagnostic report with result references`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/DiagnosticReport.read")
        val fixture = createReport(member.organization)

        mockMvc.get("/fhir/r4/DiagnosticReport/${fixture.report.id.value}") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/fhir+json") }
            jsonPath("$.resourceType") { value("DiagnosticReport") }
            jsonPath("$.id") { value(fixture.report.id.value.toString()) }
            jsonPath("$.status") { value("final") }
            jsonPath("$.code.coding[0].code") { value("24323-8") }
            jsonPath("$.subject.reference") { value("Patient/${fixture.patient.id.value}") }
            jsonPath("$.result[0].reference") { value("Observation/${fixture.observation.id.value}") }
            jsonPath("$.conclusion") { value("All values within normal limits.") }
            jsonPath("$.issued") { exists() }
        }
    }

    @Test
    fun `fhir diagnostic report compartment search and error handling`() {
        val member = createMember(MembershipRole.CLINICIAN, "user/DiagnosticReport.read")
        val staff = createMember(MembershipRole.STAFF, "user/*.read")
        val fixture = createReport(member.organization)

        mockMvc.get("/fhir/r4/DiagnosticReport") {
            param("patient", fixture.patient.id.value.toString())
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.resourceType") { value("Bundle") }
            jsonPath("$.type") { value("searchset") }
            jsonPath("$.total") { value(1) }
            jsonPath("$.entry[0].resource.id") { value(fixture.report.id.value.toString()) }
        }

        // missing patient param
        mockMvc.get("/fhir/r4/DiagnosticReport") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.resourceType") { value("OperationOutcome") }
        }

        // staff forbidden
        mockMvc.get("/fhir/r4/DiagnosticReport/${fixture.report.id.value}") {
            header("Authorization", "Bearer ${staff.token}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.issue[0].code") { value("forbidden") }
        }

        // cross-tenant read
        val outsider = createMember(MembershipRole.CLINICIAN, "user/DiagnosticReport.read")
        mockMvc.get("/fhir/r4/DiagnosticReport/${fixture.report.id.value}") {
            header("Authorization", "Bearer ${outsider.token}")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.issue[0].code") { value("not-found") }
        }
    }

    private data class ReportFixture(
        val patient: Patient,
        val order: Order,
        val observation: Observation,
        val report: DiagnosticReport,
    )

    private fun createReport(organization: Organization): ReportFixture {
        val patient = patientRepository.create(
            PatientCreateCommand(
                organizationId = organization.id,
                givenName = "Synthetic",
                familyName = "Patient",
            ),
        )
        val order = orderRepository.create(
            OrderCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                codeConceptId = panelConcept.id,
            ),
        )
        val observation = observationRepository.create(
            ObservationCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                category = ObservationCategory.LABORATORY,
                codeConceptId = panelConcept.id,
                value = ObservationValue.Quantity(BigDecimal("4.5"), "mmol/L"),
                effectiveAt = Instant.parse("2026-06-01T09:30:00Z"),
            ),
        )
        val report = diagnosticReportRepository.create(
            DiagnosticReportCreateCommand(
                organizationId = organization.id,
                patientId = patient.id,
                orderId = order.id,
                codeConceptId = panelConcept.id,
                resultObservationIds = listOf(observation.id),
                conclusionText = "All values within normal limits.",
            ),
        )
        return ReportFixture(patient, order, observation, report)
    }

    private fun createMember(
        role: MembershipRole,
        scopes: String,
    ): FhirMemberFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "fhir-report-org-$suffix",
            displayName = "Fhir Report Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "fhir-report-user-$suffix",
            email = "fhir-report-user-$suffix@example.test",
            displayName = "Fhir Report User $suffix",
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
}
