package dev.ehr.order

enum class OrderStatus(val dbValue: String) {
    ACTIVE("active"),
    ON_HOLD("on-hold"),
    COMPLETED("completed"),
    REVOKED("revoked"),
    ENTERED_IN_ERROR("entered-in-error"),
    ;

    fun canTransitionTo(target: OrderStatus): Boolean =
        target in allowedTransitions.getValue(this)

    companion object {
        fun fromDb(dbValue: String): OrderStatus =
            entries.first { it.dbValue == dbValue }

        private val allowedTransitions: Map<OrderStatus, Set<OrderStatus>> = mapOf(
            ACTIVE to setOf(ON_HOLD, COMPLETED, REVOKED, ENTERED_IN_ERROR),
            ON_HOLD to setOf(ACTIVE, REVOKED, ENTERED_IN_ERROR),
            COMPLETED to setOf(ENTERED_IN_ERROR),
            REVOKED to setOf(ENTERED_IN_ERROR),
            ENTERED_IN_ERROR to emptySet(),
        )
    }
}

enum class OrderPriority(val dbValue: String) {
    ROUTINE("routine"),
    URGENT("urgent"),
    STAT("stat"),
    ;

    companion object {
        fun fromDb(dbValue: String): OrderPriority =
            entries.first { it.dbValue == dbValue }
    }
}
