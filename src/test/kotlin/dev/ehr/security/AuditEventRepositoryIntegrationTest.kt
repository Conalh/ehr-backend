package dev.ehr.security

import dev.ehr.identity.OrganizationRepository
import dev.ehr.identity.UserRepository
import dev.ehr.testsupport.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertFailsWith

class AuditEventRepositoryIntegrationTest : PostgresIntegrationTest() {
    @Autowired
    lateinit var auditEventRepository: AuditEventRepository

    @Autowired
    lateinit var organizationRepository: OrganizationRepository

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `repository appends audit row with expected safe fields`() {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "audit-repo-org-$suffix",
            displayName = "Audit Repo Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "audit-repo-user-$suffix",
            email = "audit-repo-user-$suffix@example.test",
            displayName = "Audit Repo User $suffix",
        )
        val correlationId = "audit-repo-$suffix"

        val record = auditEventRepository.append(
            AuditEventCommand(
                organizationId = organization.id,
                subjectUserId = user.id,
                resourceType = "ORGANIZATION",
                operation = AuditOperation.READ,
                outcome = AuditOutcome.SUCCESS,
                policyVersion = "policy-spine-v1",
                policyReasonCode = "ALLOWED",
                correlationId = correlationId,
            ),
        )

        assertNotNull(record.id)
        assertEquals(organization.id, record.organizationId)
        assertEquals(user.id, record.subjectUserId)
        assertEquals(null, record.clientId)
        assertEquals("ORGANIZATION", record.resourceType)
        assertEquals(AuditOperation.READ, record.operation)
        assertEquals(AuditOutcome.SUCCESS, record.outcome)
        assertEquals("policy-spine-v1", record.policyVersion)
        assertEquals("ALLOWED", record.policyReasonCode)
        assertEquals(correlationId, record.correlationId)
        assertEquals("{}", record.metadata)
    }

    @Test
    fun `repository appends patient access audit row with patient and resource ids`() {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "audit-patient-org-$suffix",
            displayName = "Audit Patient Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "audit-patient-user-$suffix",
            email = "audit-patient-user-$suffix@example.test",
            displayName = "Audit Patient User $suffix",
        )
        val patientId = UUID.randomUUID()

        val created = auditEventRepository.append(
            AuditEventCommand(
                organizationId = organization.id,
                subjectUserId = user.id,
                patientId = patientId,
                resourceType = "PATIENT",
                resourceId = patientId,
                operation = AuditOperation.CREATE,
                outcome = AuditOutcome.SUCCESS,
                policyVersion = "policy-spine-v2",
                policyReasonCode = "ALLOWED",
                correlationId = "audit-patient-create-$suffix",
            ),
        )
        assertEquals(patientId, created.patientId)
        assertEquals(patientId, created.resourceId)
        assertEquals(AuditOperation.CREATE, created.operation)

        val notFound = auditEventRepository.append(
            AuditEventCommand(
                organizationId = organization.id,
                subjectUserId = user.id,
                patientId = null,
                resourceType = "PATIENT",
                resourceId = patientId,
                operation = AuditOperation.READ,
                outcome = AuditOutcome.FAILURE,
                policyVersion = "policy-spine-v2",
                policyReasonCode = "ALLOWED",
                correlationId = "audit-patient-miss-$suffix",
            ),
        )
        assertEquals(null, notFound.patientId)
        assertEquals(patientId, notFound.resourceId)
        assertEquals(AuditOutcome.FAILURE, notFound.outcome)

        val search = auditEventRepository.append(
            AuditEventCommand(
                organizationId = organization.id,
                subjectUserId = user.id,
                patientId = patientId,
                resourceType = "PATIENT",
                resourceId = patientId,
                operation = AuditOperation.SEARCH,
                outcome = AuditOutcome.SUCCESS,
                policyVersion = "policy-spine-v2",
                policyReasonCode = "ALLOWED",
                correlationId = "audit-patient-search-$suffix",
            ),
        )
        assertEquals(AuditOperation.SEARCH, search.operation)
    }

    @Test
    fun `repository inserted audit rows remain append only`() {
        val suffix = UUID.randomUUID()
        val organization = organizationRepository.create(
            slug = "audit-append-org-$suffix",
            displayName = "Audit Append Org $suffix",
        )
        val user = userRepository.create(
            externalSubject = "audit-append-user-$suffix",
            email = "audit-append-user-$suffix@example.test",
            displayName = "Audit Append User $suffix",
        )
        val record = auditEventRepository.append(
            AuditEventCommand(
                organizationId = organization.id,
                subjectUserId = user.id,
                resourceType = "ORGANIZATION",
                operation = AuditOperation.AUTHORIZATION_DENIED,
                outcome = AuditOutcome.DENIED,
                policyVersion = "policy-spine-v1",
                policyReasonCode = "INSUFFICIENT_ROLE",
                correlationId = "audit-append-$suffix",
            ),
        )

        assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "update audit_events set outcome = 'FAILURE' where id = ?",
                record.id,
            )
        }
        assertFailsWith<DataAccessException> {
            jdbcTemplate.update("delete from audit_events where id = ?", record.id)
        }
    }
}
