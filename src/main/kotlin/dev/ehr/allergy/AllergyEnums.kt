package dev.ehr.allergy

enum class AllergyClinicalStatus(val dbValue: String) {
    ACTIVE("active"),
    INACTIVE("inactive"),
    RESOLVED("resolved"),
    ;

    companion object {
        fun fromDb(dbValue: String): AllergyClinicalStatus =
            entries.first { it.dbValue == dbValue }
    }
}

enum class AllergyVerificationStatus(val dbValue: String) {
    UNCONFIRMED("unconfirmed"),
    CONFIRMED("confirmed"),
    REFUTED("refuted"),
    ENTERED_IN_ERROR("entered-in-error"),
    ;

    companion object {
        fun fromDb(dbValue: String): AllergyVerificationStatus =
            entries.first { it.dbValue == dbValue }
    }
}

enum class AllergyCategory(val dbValue: String) {
    FOOD("food"),
    MEDICATION("medication"),
    ENVIRONMENT("environment"),
    BIOLOGIC("biologic"),
    ;

    companion object {
        fun fromDb(dbValue: String): AllergyCategory =
            entries.first { it.dbValue == dbValue }
    }
}

enum class AllergyCriticality(val dbValue: String) {
    LOW("low"),
    HIGH("high"),
    UNABLE_TO_ASSESS("unable-to-assess"),
    ;

    companion object {
        fun fromDb(dbValue: String): AllergyCriticality =
            entries.first { it.dbValue == dbValue }
    }
}
