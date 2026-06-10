package dev.ehr.order

import dev.ehr.encounter.EncounterId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.TenantScope
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConceptId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

class StaleOrderTransitionException(message: String) : RuntimeException(message)

@Repository
class OrderRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun create(command: OrderCreateCommand): Order =
        jdbcTemplate.query(
            """
            insert into orders (
              organization_id,
              patient_id,
              encounter_id,
              code_concept_id,
              priority,
              created_by,
              updated_by
            )
            select
              p.organization_id,
              p.id,
              ?,
              ?,
              ?,
              ?,
              ?
            from patients p
            where p.organization_id = ?
              and p.id = ?
            returning $COLUMNS
            """.trimIndent(),
            rowMapper,
            command.encounterId?.value,
            command.codeConceptId.value,
            command.priority?.dbValue,
            command.createdBy?.value,
            command.createdBy?.value,
            command.organizationId.value,
            command.patientId.value,
        ).singleOrNull()
            ?: throw IllegalArgumentException("patient does not exist in the requested organization")

    fun findById(
        tenantScope: TenantScope,
        orderId: OrderId,
    ): Order? =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from orders
            where organization_id = ?
              and id = ?
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            orderId.value,
        ).singleOrNull()

    fun findByPatient(
        tenantScope: TenantScope,
        patientId: PatientId,
    ): List<Order> =
        jdbcTemplate.query(
            """
            select $COLUMNS
            from orders
            where organization_id = ?
              and patient_id = ?
            order by placed_at desc, id
            """.trimIndent(),
            rowMapper,
            tenantScope.organizationId.value,
            patientId.value,
        )

    fun transition(
        tenantScope: TenantScope,
        orderId: OrderId,
        command: OrderTransitionCommand,
    ): Order? {
        val current = findById(tenantScope, orderId) ?: return null
        require(current.status.canTransitionTo(command.targetStatus)) {
            "order status ${current.status.dbValue} cannot transition to ${command.targetStatus.dbValue}"
        }

        return jdbcTemplate.query(
            """
            update orders
            set status = ?,
                version = version + 1,
                updated_at = now(),
                updated_by = coalesce(?, updated_by)
            where organization_id = ?
              and id = ?
              and status = ?
              and version = ?
            returning $COLUMNS
            """.trimIndent(),
            rowMapper,
            command.targetStatus.dbValue,
            command.updatedBy?.value,
            tenantScope.organizationId.value,
            orderId.value,
            current.status.dbValue,
            command.expectedVersion,
        ).singleOrNull()
            ?: throw StaleOrderTransitionException("order was modified concurrently; transition not applied")
    }

    private companion object {
        const val COLUMNS = """
              id,
              organization_id,
              patient_id,
              encounter_id,
              status,
              code_concept_id,
              priority,
              placed_at,
              version,
              created_at,
              updated_at,
              created_by,
              updated_by
        """

        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            Order(
                id = OrderId(rs.getObject("id", UUID::class.java)),
                organizationId = OrganizationId(rs.getObject("organization_id", UUID::class.java)),
                patientId = PatientId(rs.getObject("patient_id", UUID::class.java)),
                encounterId = rs.getObject("encounter_id", UUID::class.java)?.let(::EncounterId),
                status = OrderStatus.fromDb(rs.getString("status")),
                codeConceptId = CodeableConceptId(rs.getObject("code_concept_id", UUID::class.java)),
                priority = rs.getString("priority")?.let(OrderPriority::fromDb),
                placedAt = rs.getTimestamp("placed_at").toInstant(),
                version = rs.getInt("version"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                createdBy = rs.getObject("created_by", UUID::class.java)?.let(::UserId),
                updatedBy = rs.getObject("updated_by", UUID::class.java)?.let(::UserId),
            )
        }
    }
}
