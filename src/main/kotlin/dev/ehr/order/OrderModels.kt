package dev.ehr.order

import dev.ehr.encounter.EncounterId
import dev.ehr.identity.OrganizationId
import dev.ehr.identity.UserId
import dev.ehr.patient.PatientId
import dev.ehr.terminology.CodeableConceptId
import java.time.Instant

data class Order(
    val id: OrderId,
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val encounterId: EncounterId?,
    val status: OrderStatus,
    val codeConceptId: CodeableConceptId,
    val priority: OrderPriority?,
    val placedAt: Instant,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UserId?,
    val updatedBy: UserId?,
)

data class OrderCreateCommand(
    val organizationId: OrganizationId,
    val patientId: PatientId,
    val codeConceptId: CodeableConceptId,
    val encounterId: EncounterId? = null,
    val priority: OrderPriority? = null,
    val createdBy: UserId? = null,
)

data class OrderTransitionCommand(
    val targetStatus: OrderStatus,
    val expectedVersion: Int,
    val updatedBy: UserId? = null,
)
