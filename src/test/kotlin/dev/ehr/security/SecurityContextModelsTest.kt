package dev.ehr.security

import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class SecurityContextModelsTest {
    @Test
    fun `security scope parser preserves raw scope tokens without SMART semantics`() {
        val scopes = SecurityScope.parse(" user/*.read  patient/Patient.rs system/*.read ")

        assertEquals(
            listOf("user/*.read", "patient/Patient.rs", "system/*.read"),
            scopes.map { it.rawValue },
        )
    }

    @Test
    fun `security scope parser treats blank input as no scopes`() {
        assertTrue(SecurityScope.parse("   ").isEmpty())
        assertTrue(SecurityScope.parse(null).isEmpty())
    }

    @Test
    fun `security principal carries authenticated subject and organization context`() {
        val userId = UserId(UUID.randomUUID())
        val organizationId = OrganizationId(UUID.randomUUID())
        val subject = AuthenticatedSubject(
            externalSubject = "fixture-subject",
            userId = userId,
            scopes = SecurityScope.parse("user/*.read"),
        )
        val context = OrganizationContext(organizationId)

        val principal = SecurityPrincipal(
            subject = subject,
            organization = context,
        )

        assertEquals(userId, principal.subject.userId)
        assertEquals("fixture-subject", principal.subject.externalSubject)
        assertEquals(organizationId, principal.organization.organizationId)
        assertEquals(listOf("user/*.read"), principal.subject.scopes.map { it.rawValue })
    }
}
