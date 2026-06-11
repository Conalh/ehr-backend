package dev.ehr.authz

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ehr.identity.MembershipRepository
import dev.ehr.identity.MembershipRole
import dev.ehr.identity.Organization
import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.User
import dev.ehr.identity.UserRepository
import dev.ehr.patient.PatientCreateCommand
import dev.ehr.patient.PatientRepository
import dev.ehr.testsupport.DevJwtFactory
import dev.ehr.testsupport.DevJwtTestConfiguration
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin
import org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.util.UriComponentsBuilder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Slice AS2: the full SMART-shaped authorization-code dance — dev login,
 * PKCE, rotating refresh tokens, revocation, and an OIDC id_token.
 */
@AutoConfigureMockMvc
@Import(DevJwtTestConfiguration::class)
class AuthorizationCodeFlowIntegrationTest : PostgresIntegrationTest() {
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
    lateinit var practitionerRepository: dev.ehr.identity.PractitionerRepository

    @Autowired
    lateinit var jwtDecoder: JwtDecoder

    @Autowired
    lateinit var jwtEncoder: org.springframework.security.oauth2.jwt.JwtEncoder

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `a public client completes the pkce flow and uses its tokens against the api`() {
        val fixture = createClinicianFixture()
        val clientIdentifier = registerClient(fixture, type = "PUBLIC")
        val verifier = "verifier-${UUID.randomUUID()}-${UUID.randomUUID()}"

        // Unauthenticated browser-shaped authorize requests go through the
        // dev login.
        mockMvc.get(
            "/oauth/authorize?response_type=code&client_id={clientId}&redirect_uri={redirectUri}" +
                "&scope={scope}&code_challenge={challenge}&code_challenge_method=S256",
            clientIdentifier,
            REDIRECT_URI,
            "openid user/*.read",
            challengeFor(verifier),
        ) {
            accept = MediaType.TEXT_HTML
        }.andExpect {
            status { is3xxRedirection() }
            redirectedUrlPattern("**/login")
        }

        // The logged-in clinician has a practitioner identity, so fhirUser
        // can point at a resource this server actually serves.
        val practitioner = practitionerRepository.create(
            userId = fixture.user.id,
            displayName = fixture.user.displayName,
            npi = (1_000_000_000L..9_999_999_999L).random().toString(),
        )

        val session = devLogin(fixture.user)
        val code = authorize(session, clientIdentifier, challengeFor(verifier))
        val tokens = exchangeCode(clientIdentifier, code, verifier)

        // The id_token identifies the logged-in user and their FHIR identity.
        val idToken = tokens["id_token"].asText()
        val idClaims = jwtPayload(idToken)
        assertEquals(fixture.user.externalSubject, idClaims["sub"].asText())
        val fhirUser = idClaims["fhirUser"].asText()
        assertTrue(fhirUser.endsWith("/fhir/r4/Practitioner/${practitioner.id.value}"))

        // The access token works against the clinical API through the
        // existing converter path (org claim from the single membership).
        val patient = patientRepository.create(
            PatientCreateCommand(
                organizationId = fixture.organization.id,
                givenName = "Synthetic",
                familyName = "Patient",
            ),
        )
        mockMvc.get("/api/v1/patients/${patient.id.value}/conditions") {
            header("Authorization", "Bearer ${tokens["access_token"].asText()}")
        }.andExpect {
            status { isOk() }
        }

        // Public clients get no refresh token (the authorization server's
        // posture for unauthenticated clients); rotation is proven on the
        // confidential client below.
        assertEquals(null, tokens["refresh_token"])
    }

    @Test
    fun `users without a practitioner identity get no fhirUser claim`() {
        val fixture = createClinicianFixture()
        val clientIdentifier = registerClient(fixture, type = "PUBLIC")
        val verifier = "verifier-${UUID.randomUUID()}-${UUID.randomUUID()}"
        val session = devLogin(fixture.user)

        val code = authorize(session, clientIdentifier, challengeFor(verifier))
        val tokens = exchangeCode(clientIdentifier, code, verifier)

        val idClaims = jwtPayload(tokens["id_token"].asText())
        assertEquals(fixture.user.externalSubject, idClaims["sub"].asText())
        assertEquals(null, idClaims["fhirUser"])
    }

    @Test
    fun `wrong verifiers fail and confidential clients can revoke their refresh tokens`() {
        val fixture = createClinicianFixture()
        val publicClient = registerClient(fixture, type = "PUBLIC")
        val verifier = "verifier-${UUID.randomUUID()}-${UUID.randomUUID()}"
        val session = devLogin(fixture.user)

        // Wrong PKCE verifier: no tokens.
        val code = authorize(session, publicClient, challengeFor(verifier))
        mockMvc.post("/oauth/token") {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "grant_type=authorization_code&code=$code" +
                "&redirect_uri=$REDIRECT_URI&client_id=$publicClient&code_verifier=wrong-$verifier"
        }.andExpect {
            status { isBadRequest() }
        }

        // Confidential client: secret-authenticated flow, then revocation.
        val confidentialRegistration = registerClientWithSecret(fixture, type = "CONFIDENTIAL")
        val confidentialClient = confidentialRegistration.first
        val secret = confidentialRegistration.second
        val confidentialVerifier = "verifier-${UUID.randomUUID()}-${UUID.randomUUID()}"
        val confidentialCode = authorize(session, confidentialClient, challengeFor(confidentialVerifier))
        val tokens = objectMapper.readTree(
            mockMvc.post("/oauth/token") {
                header("Authorization", basicAuth(confidentialClient, secret))
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                content = "grant_type=authorization_code&code=$confidentialCode" +
                    "&redirect_uri=$REDIRECT_URI&code_verifier=$confidentialVerifier"
            }.andExpect {
                status { isOk() }
            }.andReturn().response.contentAsString,
        )

        // Rotation: refreshing issues a new token and the old one dies.
        val firstRefresh = tokens["refresh_token"].asText()
        val rotated = objectMapper.readTree(
            mockMvc.post("/oauth/token") {
                header("Authorization", basicAuth(confidentialClient, secret))
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                content = "grant_type=refresh_token&refresh_token=$firstRefresh"
            }.andExpect {
                status { isOk() }
                jsonPath("$.refresh_token") { exists() }
            }.andReturn().response.contentAsString,
        )
        val rotatedRefresh = rotated["refresh_token"].asText()
        assertNotEquals(firstRefresh, rotatedRefresh)
        mockMvc.post("/oauth/token") {
            header("Authorization", basicAuth(confidentialClient, secret))
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "grant_type=refresh_token&refresh_token=$firstRefresh"
        }.andExpect {
            status { isBadRequest() }
        }

        // Revocation kills the live refresh token.
        mockMvc.post("/oauth/revoke") {
            header("Authorization", basicAuth(confidentialClient, secret))
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "token=$rotatedRefresh"
        }.andExpect {
            status { isOk() }
        }
        mockMvc.post("/oauth/token") {
            header("Authorization", basicAuth(confidentialClient, secret))
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "grant_type=refresh_token&refresh_token=$rotatedRefresh"
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `users with multiple organization memberships are refused as-issued tokens`() {
        val fixture = createClinicianFixture()
        val secondOrganization = organizationRepository.create(
            slug = "asc-org2-${UUID.randomUUID()}",
            displayName = "Second Org",
        )
        val secondMembership = membershipRepository.create(
            organizationId = secondOrganization.id,
            userId = fixture.user.id,
        )
        membershipRepository.addRole(secondMembership.id, MembershipRole.CLINICIAN)

        val clientIdentifier = registerClient(fixture, type = "PUBLIC")
        val verifier = "verifier-${UUID.randomUUID()}-${UUID.randomUUID()}"
        val session = devLogin(fixture.user)
        val code = authorize(session, clientIdentifier, challengeFor(verifier))

        // The org claim is ambiguous: issuance fails closed.
        mockMvc.post("/oauth/token") {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "grant_type=authorization_code&code=$code" +
                "&redirect_uri=$REDIRECT_URI&client_id=$clientIdentifier&code_verifier=$verifier"
        }.andExpect {
            status { is4xxClientError() }
        }
    }

    @Test
    fun `the dev login rejects wrong passwords and unknown users`() {
        val fixture = createClinicianFixture()

        mockMvc.perform(
            formLogin().user(fixture.user.externalSubject).password("not-the-dev-password"),
        ).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/login?error"))

        mockMvc.perform(
            formLogin().user("nobody-${UUID.randomUUID()}").password(DEV_LOGIN_PASSWORD),
        ).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/login?error"))
    }

    private fun devLogin(user: User): MockHttpSession {
        val result = mockMvc.perform(
            formLogin().user(user.externalSubject).password(DEV_LOGIN_PASSWORD),
        ).andExpect(authenticated()).andReturn()
        return result.request.getSession(false) as MockHttpSession
    }

    private fun authorize(
        session: MockHttpSession,
        clientIdentifier: String,
        challenge: String,
        scope: String = "openid fhirUser user/*.read user/*.write",
    ): String {
        // The authorize converter reads GET parameters from the query string,
        // which MockMvc's param() does not populate — and a pre-encoded URI
        // string gets double-encoded. URI template variables encode exactly
        // once.
        val result = mockMvc.get(
            "/oauth/authorize?response_type=code&client_id={clientId}&redirect_uri={redirectUri}" +
                "&scope={scope}&state={state}&code_challenge={challenge}&code_challenge_method=S256",
            clientIdentifier,
            REDIRECT_URI,
            scope,
            "state-${UUID.randomUUID()}",
            challenge,
        ) {
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

    private fun exchangeCode(
        clientIdentifier: String,
        code: String,
        verifier: String,
    ) = objectMapper.readTree(
        mockMvc.post("/oauth/token") {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "grant_type=authorization_code&code=$code" +
                "&redirect_uri=$REDIRECT_URI&client_id=$clientIdentifier&code_verifier=$verifier"
        }.andExpect {
            status { isOk() }
            jsonPath("$.access_token") { exists() }
            jsonPath("$.id_token") { exists() }
        }.andReturn().response.contentAsString,
    )!!

    private fun registerClient(
        fixture: ClinicianFixture,
        type: String,
    ): String = registerClientWithSecret(fixture, type).first

    private fun registerClientWithSecret(
        fixture: ClinicianFixture,
        type: String,
    ): Pair<String, String?> {
        val identifier = "asc-app-${UUID.randomUUID()}"
        val response = objectMapper.readTree(
            mockMvc.post("/api/v1/oauth-clients") {
                contentType = MediaType.APPLICATION_JSON
                content = """
                    {
                      "clientIdentifier": "$identifier",
                      "displayName": "Synthetic User App",
                      "clientType": "$type",
                      "grantedScopes": "openid fhirUser user/*.read user/*.write",
                      "redirectUris": "$REDIRECT_URI"
                    }
                """.trimIndent()
                header("Authorization", "Bearer ${fixture.adminToken}")
            }.andExpect {
                status { isCreated() }
            }.andReturn().response.contentAsString,
        )
        val secret = response["clientSecret"]?.takeIf { !it.isNull }?.asText()
        return identifier to secret
    }

    private fun challengeFor(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )

    private fun jwtPayload(token: String) =
        objectMapper.readTree(Base64.getUrlDecoder().decode(token.split(".")[1]))!!

    private fun basicAuth(
        user: String,
        password: String?,
    ): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    private fun createClinicianFixture(): ClinicianFixture {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "asc-org-$suffix",
            displayName = "Asc Org $suffix",
        )
        val clinician = userRepository.create(
            externalSubject = "asc-clinician-$suffix",
            email = "asc-clinician-$suffix@example.test",
            displayName = "Asc Clinician $suffix",
        )
        val clinicianMembership = membershipRepository.create(
            organizationId = organization.id,
            userId = clinician.id,
        )
        membershipRepository.addRole(clinicianMembership.id, MembershipRole.CLINICIAN)

        val admin = userRepository.create(
            externalSubject = "asc-admin-$suffix",
            email = "asc-admin-$suffix@example.test",
            displayName = "Asc Admin $suffix",
        )
        val adminMembership = membershipRepository.create(
            organizationId = organization.id,
            userId = admin.id,
        )
        membershipRepository.addRole(adminMembership.id, MembershipRole.ORG_ADMIN)

        return ClinicianFixture(
            organization = organization,
            user = clinician,
            adminToken = DevJwtFactory(jwtEncoder).tokenFor(
                user = admin,
                organization = organization,
                scopes = "user/*.read user/*.write",
            ),
        )
    }

    private companion object {
        const val REDIRECT_URI = "https://app.example.test/callback"
        // The application.yml dev default; tests run without overrides.
        const val DEV_LOGIN_PASSWORD = "ehr-core-local-dev-login"
    }
}

data class ClinicianFixture(
    val organization: Organization,
    val user: User,
    val adminToken: String,
)
