package dev.ehr.order

import dev.ehr.encounter.EncounterId
import dev.ehr.patient.PatientId
import dev.ehr.security.SecurityPrincipal
import dev.ehr.terminology.CodeableConceptId
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class OrderController(
    private val orderService: OrderService,
) {
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    fun place(
        authentication: Authentication,
        @Valid @RequestBody request: PlaceOrderRequest,
    ): OrderResponse {
        val principal = securityPrincipal(authentication)
        return orderService.place(
            principal = principal,
            command = OrderCreateCommand(
                organizationId = principal.organization.organizationId,
                patientId = PatientId(request.patientId!!),
                codeConceptId = CodeableConceptId(request.codeConceptId!!),
                encounterId = request.encounterId?.let(::EncounterId),
                priority = request.priority,
                createdBy = principal.subject.userId,
            ),
        ).toResponse()
    }

    @GetMapping("/orders/{orderId}")
    fun get(
        authentication: Authentication,
        @PathVariable orderId: UUID,
    ): OrderResponse =
        orderService.get(
            principal = securityPrincipal(authentication),
            orderId = OrderId(orderId),
        ).toResponse()

    @GetMapping("/patients/{patientId}/orders")
    fun listForPatient(
        authentication: Authentication,
        @PathVariable patientId: UUID,
    ): OrderListResponse =
        OrderListResponse(
            orders = orderService.listForPatient(
                principal = securityPrincipal(authentication),
                patientId = PatientId(patientId),
            ).map { it.toResponse() },
        )

    @PostMapping("/orders/{orderId}/status")
    fun transition(
        authentication: Authentication,
        @PathVariable orderId: UUID,
        @Valid @RequestBody request: TransitionOrderRequest,
    ): OrderResponse {
        val principal = securityPrincipal(authentication)
        return orderService.transition(
            principal = principal,
            orderId = OrderId(orderId),
            command = OrderTransitionCommand(
                targetStatus = request.targetStatus!!,
                expectedVersion = request.expectedVersion!!,
                updatedBy = principal.subject.userId,
            ),
        ).toResponse()
    }

    private fun securityPrincipal(authentication: Authentication): SecurityPrincipal =
        authentication.principal as? SecurityPrincipal
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")
}

data class PlaceOrderRequest(
    @field:NotNull
    val patientId: UUID?,
    @field:NotNull
    val codeConceptId: UUID?,
    val encounterId: UUID? = null,
    val priority: OrderPriority? = null,
)

data class TransitionOrderRequest(
    @field:NotNull
    val targetStatus: OrderStatus?,
    @field:NotNull
    val expectedVersion: Int?,
)

data class OrderResponse(
    val id: String,
    val organizationId: String,
    val patientId: String,
    val encounterId: String?,
    val status: String,
    val codeConceptId: String,
    val priority: String?,
    val placedAt: Instant,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class OrderListResponse(
    val orders: List<OrderResponse>,
)

fun Order.toResponse(): OrderResponse =
    OrderResponse(
        id = id.value.toString(),
        organizationId = organizationId.value.toString(),
        patientId = patientId.value.toString(),
        encounterId = encounterId?.value?.toString(),
        status = status.dbValue,
        codeConceptId = codeConceptId.value.toString(),
        priority = priority?.dbValue,
        placedAt = placedAt,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
