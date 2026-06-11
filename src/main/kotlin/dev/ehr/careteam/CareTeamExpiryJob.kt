package dev.ehr.careteam

import dev.ehr.identity.OrganizationId
import dev.ehr.runtime.EhrProperties
import dev.ehr.security.AuditEventService
import dev.ehr.security.AuditOperation
import dev.ehr.security.AuditOutcome
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Ends encounter-derived care-team memberships that no longer have a
 * sustaining encounter: one for the same (org, patient) opened by the member
 * that is still open, or finished within the configured expiry window.
 * Explicit memberships never auto-expire (design decision 1C / open Q2).
 */
@Component
class CareTeamExpiryJob(
    private val jdbcTemplate: JdbcTemplate,
    private val auditEventService: AuditEventService,
    private val properties: EhrProperties,
    private val transactionTemplate: TransactionTemplate,
) {
    private val logger = LoggerFactory.getLogger(CareTeamExpiryJob::class.java)

    @Scheduled(initialDelayString = "PT10M", fixedDelayString = "PT1H")
    fun sweep() {
        val ended = expireStale()
        if (ended > 0) {
            logger.info("ended {} expired encounter-derived care-team memberships", ended)
        }
    }

    fun expireStale(): Int =
        transactionTemplate.execute {
            val expired = jdbcTemplate.query(
                """
                update care_team_memberships ctm
                set period_end = now()
                where ctm.origin = 'encounter-derived'
                  and ctm.period_end is null
                  and not exists (
                    select 1
                    from encounters e
                    where e.organization_id = ctm.organization_id
                      and e.patient_id = ctm.patient_id
                      and e.created_by = ctm.user_id
                      and (
                        e.status in ('planned', 'in-progress')
                        or (e.status = 'finished' and e.period_end > now() - make_interval(days => ?))
                      )
                  )
                returning ctm.id, ctm.organization_id
                """.trimIndent(),
                { rs, _ ->
                    ExpiredMembership(
                        id = rs.getObject("id", UUID::class.java),
                        organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                    )
                },
                properties.compartment.encounterDerivedExpiryDays,
            )
            expired.forEach { membership ->
                auditEventService.recordBackgroundEvent(
                    organizationId = membership.organizationId,
                    subjectUserId = null,
                    resourceType = "CARE_TEAM",
                    operation = AuditOperation.SYSTEM,
                    outcome = AuditOutcome.SUCCESS,
                    resourceId = membership.id,
                )
            }
            expired.size
        }!!

    private data class ExpiredMembership(
        val id: UUID,
        val organizationId: OrganizationId,
    )
}
