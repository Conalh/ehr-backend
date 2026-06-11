package dev.ehr.identity

enum class OrganizationStatus(val dbValue: String) {
    ACTIVE("active"),
    SUSPENDED("suspended"),
    INACTIVE("inactive"),
    ;

    companion object {
        fun fromDb(dbValue: String): OrganizationStatus =
            entries.first { it.dbValue == dbValue }
    }
}

enum class UserStatus(val dbValue: String) {
    ACTIVE("active"),
    INACTIVE("inactive"),
    LOCKED("locked"),
    ;

    companion object {
        fun fromDb(dbValue: String): UserStatus =
            entries.first { it.dbValue == dbValue }
    }
}

enum class PractitionerStatus(val dbValue: String) {
    ACTIVE("active"),
    INACTIVE("inactive"),
    ;

    companion object {
        fun fromDb(dbValue: String): PractitionerStatus =
            entries.first { it.dbValue == dbValue }
    }
}

enum class MembershipStatus(val dbValue: String) {
    ACTIVE("active"),
    INACTIVE("inactive"),
    SUSPENDED("suspended"),
    ;

    companion object {
        fun fromDb(dbValue: String): MembershipStatus =
            entries.first { it.dbValue == dbValue }
    }
}

enum class OAuthClientStatus(val dbValue: String) {
    ACTIVE("active"),
    INACTIVE("inactive"),
    REVOKED("revoked"),
    ;

    companion object {
        fun fromDb(dbValue: String): OAuthClientStatus =
            entries.first { it.dbValue == dbValue }
    }
}

enum class OAuthClientType(val dbValue: String) {
    PUBLIC("public"),
    CONFIDENTIAL("confidential"),
    SYSTEM("system"),
    ;

    companion object {
        fun fromDb(dbValue: String): OAuthClientType =
            entries.first { it.dbValue == dbValue }
    }
}

enum class MembershipRole(val dbValue: String) {
    SYSTEM_ADMIN("SYSTEM_ADMIN"),
    ORG_ADMIN("ORG_ADMIN"),
    CLINICIAN("CLINICIAN"),
    STAFF("STAFF"),
    PATIENT("PATIENT"),
    SYSTEM_APP("SYSTEM_APP"),
    ;

    companion object {
        fun fromDb(dbValue: String): MembershipRole =
            entries.first { it.dbValue == dbValue }
    }
}
