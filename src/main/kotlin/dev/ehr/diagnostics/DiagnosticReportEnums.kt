package dev.ehr.diagnostics

enum class DiagnosticReportStatus(val dbValue: String) {
    PARTIAL("partial"),
    FINAL("final"),
    AMENDED("amended"),
    ENTERED_IN_ERROR("entered-in-error"),
    ;

    companion object {
        fun fromDb(dbValue: String): DiagnosticReportStatus =
            entries.first { it.dbValue == dbValue }
    }
}
