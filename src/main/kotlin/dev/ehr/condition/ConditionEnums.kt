package dev.ehr.condition

enum class ConditionClinicalStatus(val dbValue: String) {
    ACTIVE("active"),
    INACTIVE("inactive"),
    REMISSION("remission"),
    RESOLVED("resolved"),
    ;

    companion object {
        fun fromDb(dbValue: String): ConditionClinicalStatus =
            entries.first { it.dbValue == dbValue }
    }
}

enum class ConditionVerificationStatus(val dbValue: String) {
    PROVISIONAL("provisional"),
    CONFIRMED("confirmed"),
    REFUTED("refuted"),
    ENTERED_IN_ERROR("entered-in-error"),
    ;

    companion object {
        fun fromDb(dbValue: String): ConditionVerificationStatus =
            entries.first { it.dbValue == dbValue }
    }
}
