package dev.ehr.authz

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.TenantScope
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
import dev.ehr.patient.IdentifierUse
import dev.ehr.patient.Patient
import dev.ehr.patient.PatientCreateCommand
import dev.ehr.patient.PatientIdentifierCreateCommand
import dev.ehr.patient.PatientRepository
import dev.ehr.terminology.CanonicalCodeSystems
import dev.ehr.terminology.CodeableConceptRepository
import dev.ehr.terminology.CodingRepository
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import dev.ehr.testsupport.TerminologyTestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Slice AS3: standalone patient launch. The synthetic picker interposes on
 * launch/patient authorize requests; the token carries the launched patient;
 * patient-context scopes authorize exactly that patient's record and
 * nothing else.
 */
@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class PatientLaunchIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var conditionRepository: dev.ehr.condition.ConditionRepository

    @Autowired
    lateinit var codingRepository: CodingRepository

    @Autowired
    lateinit var codeableConceptRepository: CodeableConceptRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jwtEncoder: org.springframework.security.oauth2.jwt.JwtEncoder

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `the launch dance binds the token to the picked patient and nothing else`() {
        val fixture = createFixture()
        val launchedPatient = createPatient(fixture.organization, "Launched")
        val otherPatient = createPatient(fixture.organization, "Other")
        val clientIdentifier = registerLaunchClient(fixture)
        val verifier = "verifier-${UUID.randomUUID()}-${UUID.randomUUID()}"
        val session = devLogin(fixture.clinician)

        // 1. The authorize request parks at the picker.
        val authorizeUrl = authorizeUrl(clientIdentifier, challengeFor(verifier))
        mockMvc.get(URI.create(authorizeUrl)) {
            this.session = session
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/launch/patient-picker")
        }

        // 2. The picker lists the org's patients and accepts a selection.
        mockMvc.get("/launch/patient-picker") {
            this.session = session
        }.andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString(launchedPatient.id.value.toString())) }
        }
        mockMvc.post("/launch/patient-picker") {
            this.session = session
            with(csrf())
            param(PatientLaunchSession.LAUNCH_TRANSACTION_ID_PARAM, launchTransactionId(session))
            param("patientId", launchedPatient.id.value.toString())
        }.andExpect {
            status { is3xxRedirection() }
        }

        // 3. The resumed authorize request issues a code; the token carries
        //    the launch context.
        val code = authorizeForCode(session, authorizeUrl)
        val tokens = objectMapper.readTree(
            mockMvc.post("/oauth/token") {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                content = "grant_type=authorization_code&code=$code" +
                    "&redirect_uri=$REDIRECT_URI&client_id=$clientIdentifier&code_verifier=$verifier"
            }.andExpect {
                status { isOk() }
                jsonPath("$.access_token") { exists() }
                jsonPath("$.patient") { value(launchedPatient.id.value.toString()) }
            }.andReturn().response.contentAsString,
        )
        val accessToken = tokens["access_token"].asText()

        // 4. The launched patient's record is readable...
        mockMvc.get("/api/v1/patients/${launchedPatient.id.value}/conditions") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
        }
        mockMvc.get("/api/v1/patients/${launchedPatient.id.value}") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
        }

        // ...and any other patient is outside the launch context.
        val deniedCorrelationId = "launch-denied-${UUID.randomUUID()}"
        mockMvc.get("/api/v1/patients/${otherPatient.id.value}/conditions") {
            header("Authorization", "Bearer $accessToken")
            header("X-Correlation-Id", deniedCorrelationId)
        }.andExpect {
            status { isForbidden() }
        }
        assertEquals(
            "OUTSIDE_PATIENT_CONTEXT",
            jdbcTemplate.queryForObject(
                "select policy_reason_code from audit_events where correlation_id = ?",
                String::class.java,
                deniedCorrelationId,
            ),
        )
        mockMvc.get("/api/v1/patients/${otherPatient.id.value}") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isForbidden() }
        }

        // 5. Fetch-first paths deny at the post-fetch check: another
        //    patient's condition by id.
        val conceptId = TerminologyTestFixtures(codingRepository, codeableConceptRepository)
            .findOrCreateConcept(
                system = CanonicalCodeSystems.SNOMED_CT,
                code = "38341003",
                display = "Hypertensive disorder",
            ).id
        val foreignCondition = conditionRepository.create(
            dev.ehr.condition.ConditionCreateCommand(
                organizationId = fixture.organization.id,
                patientId = otherPatient.id,
                codeConceptId = conceptId,
            ),
        )
        mockMvc.get("/api/v1/conditions/${foreignCondition.id.value}") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isForbidden() }
        }

        // 6. Identifier search never reveals other patients.
        patientRepository.addIdentifier(
            TenantScope(fixture.organization.id),
            otherPatient.id,
            PatientIdentifierCreateCommand(
                system = "urn:ehr:mrn",
                value = "launch-mrn-${UUID.randomUUID()}",
                use = IdentifierUse.OFFICIAL,
            ),
        )
        mockMvc.get("/api/v1/patients") {
            param("identifierSystem", "urn:ehr:mrn")
            param("identifierValue", "launch-mrn-nonexistent")
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.patients.length()") { value(0) }
        }
    }

    @Test
    fun `launch selection is bound to the parked authorize transaction`() {
        val fixture = createFixture()
        val launchedPatient = createPatient(fixture.organization, "Bound")
        val firstClient = registerLaunchClient(fixture)
        val secondClient = registerLaunchClient(fixture)
        val session = devLogin(fixture.clinician)
        val firstAuthorizeUrl = authorizeUrl(firstClient, challengeFor("v-${UUID.randomUUID()}"))

        mockMvc.get(URI.create(firstAuthorizeUrl)) {
            this.session = session
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/launch/patient-picker")
        }
        mockMvc.post("/launch/patient-picker") {
            this.session = session
            with(csrf())
            param(PatientLaunchSession.LAUNCH_TRANSACTION_ID_PARAM, launchTransactionId(session))
            param("patientId", launchedPatient.id.value.toString())
        }.andExpect {
            status { is3xxRedirection() }
        }

        val secondAuthorizeUrl = authorizeUrl(secondClient, challengeFor("v-${UUID.randomUUID()}"))
        mockMvc.get(URI.create(secondAuthorizeUrl)) {
            this.session = session
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/launch/patient-picker")
        }
    }

    @Test
    fun `launch selection is consumed after authorization resumes`() {
        val fixture = createFixture()
        val launchedPatient = createPatient(fixture.organization, "Consumed")
        val clientIdentifier = registerLaunchClient(fixture)
        val session = devLogin(fixture.clinician)
        val firstAuthorizeUrl = authorizeUrl(clientIdentifier, challengeFor("v-${UUID.randomUUID()}"))

        mockMvc.get(URI.create(firstAuthorizeUrl)) {
            this.session = session
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/launch/patient-picker")
        }
        mockMvc.post("/launch/patient-picker") {
            this.session = session
            with(csrf())
            param(PatientLaunchSession.LAUNCH_TRANSACTION_ID_PARAM, launchTransactionId(session))
            param("patientId", launchedPatient.id.value.toString())
        }.andExpect {
            status { is3xxRedirection() }
        }
        authorizeForCode(session, firstAuthorizeUrl)

        val secondAuthorizeUrl = authorizeUrl(clientIdentifier, challengeFor("v-${UUID.randomUUID()}"))
        mockMvc.get(URI.create(secondAuthorizeUrl)) {
            this.session = session
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrl("/launch/patient-picker")
        }
    }

    @Test
    fun `the picker rejects patients outside the user's organization`() {
        val fixture = createFixture()
        val foreignOrganization = organizationRepository.create(
            slug = "launch-foreign-${UUID.randomUUID()}",
            displayName = "Foreign Org",
        )
        val foreignPatient = createPatient(foreignOrganization, "Foreign")
        val clientIdentifier = registerLaunchClient(fixture)
        val session = devLogin(fixture.clinician)

        // Park an authorize request so the picker has something to resume.
        mockMvc.get(URI.create(authorizeUrl(clientIdentifier, challengeFor("v-${UUID.randomUUID()}")))) {
            this.session = session
        }.andExpect {
            status { is3xxRedirection() }
        }

        mockMvc.post("/launch/patient-picker") {
            this.session = session
            with(csrf())
            param(PatientLaunchSession.LAUNCH_TRANSACTION_ID_PARAM, launchTransactionId(session))
            param("patientId", foreignPatient.id.value.toString())
        }.andExpect {
            status { isBadRequest() }
        }
    }

    private fun authorizeForCode(
        session: MockHttpSession,
        authorizeUrl: String,
    ): String {
        val result = mockMvc.get(URI.create(authorizeUrl)) {
            this.session = session
        }.andExpect {
            status { is3xxRedirection() }
        }.andReturn()
        val redirect = result.response.redirectedUrl!!
        assertTrue(redirect.startsWith(REDIRECT_URI), "expected redirect to the registered URI, got $redirect")
        val code = UriComponentsBuilder.fromUriString(redirect).build().queryParams.getFirst("code")
        assertNotNull(code, "expected an authorization code in $redirect")
        return code!!
    }

    private fun authorizeUrl(
        clientIdentifier: String,
        challenge: String,
    ): String =
        UriComponentsBuilder.fromPath("/oauth/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", clientIdentifier)
            .queryParam("redirect_uri", REDIRECT_URI)
            .queryParam("scope", "openid launch/patient patient/*.read")
            .queryParam("state", "state-${UUID.randomUUID()}")
            .queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256")
            .encode()
            .build()
            .toUriString()

    private fun devLogin(user: User): MockHttpSession {
        val result = mockMvc.perform(
            formLogin().user(user.externalSubject).password(DEV_LOGIN_PASSWORD),
        ).andExpect(authenticated()).andReturn()
        return result.request.getSession(false) as MockHttpSession
    }

    private fun registerLaunchClient(fixture: LaunchFixture): String {
        val identifier = "launch-app-${UUID.randomUUID()}"
        mockMvc.post("/api/v1/oauth-clients") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "clientIdentifier": "$identifier",
                  "displayName": "Synthetic Patient App",
                  "clientType": "PUBLIC",
                  "grantedScopes": "openid launch/patient patient/*.read",
                  "redirectUris": "$REDIRECT_URI"
                }
            """.trimIndent()
            header("Authorization", "Bearer ${fixture.adminToken}")
        }.andExpect {
            status { isCreated() }
        }
        return identifier
    }

    private fun launchTransactionId(session: MockHttpSession): String {
        val transaction = session.getAttribute(PatientLaunchSession.PENDING_TRANSACTION) as? PatientLaunchTransaction
        assertNotNull(transaction, "expected a pending patient launch transaction")
        return transaction!!.id.toString()
    }

    private fun createPatient(
        organization: Organization,
        givenName: String,
    ): Patient =
        patientRepository.create(
            PatientCreateCommand(
                organizationId = organization.id,
                givenName = givenName,
                familyName = "Synthetic",
            ),
        )

    private fun createFixture(): LaunchFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "launch-org-$suffix",
            displayName = "Launch Org $suffix",
        )
        val clinician = userRepository.create(
            externalSubject = "launch-clinician-$suffix",
            email = "launch-clinician-$suffix@example.test",
            displayName = "Launch Clinician $suffix",
        )
        val clinicianMembership = membershipRepository.create(
            organizationId = organization.id,
            userId = clinician.id,
        )
        membershipRepository.addRole(clinicianMembership.id, MembershipRole.CLINICIAN)

        val admin = userRepository.create(
            externalSubject = "launch-admin-$suffix",
            email = "launch-admin-$suffix@example.test",
            displayName = "Launch Admin $suffix",
        )
        val adminMembership = membershipRepository.create(
            organizationId = organization.id,
            userId = admin.id,
        )
        membershipRepository.addRole(adminMembership.id, MembershipRole.ORG_ADMIN)

        return LaunchFixture(
            organization = organization,
            clinician = clinician,
            adminToken = dev.ehr.testsupport.DevJwtFactory(jwtEncoder).tokenFor(
                user = admin,
                organization = organization,
                scopes = "user/*.read user/*.write",
            ),
        )
    }

    private fun challengeFor(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )

    private companion object {
        const val REDIRECT_URI = "https://app.example.test/callback"
        const val DEV_LOGIN_PASSWORD = "ehr-core-local-dev-login"
    }
}

data class LaunchFixture(
    val organization: Organization,
    val clinician: User,
    val adminToken: String,
)
