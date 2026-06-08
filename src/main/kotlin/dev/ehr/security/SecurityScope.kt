package dev.ehr.security

@JvmInline
value class SecurityScope private constructor(val rawValue: String) {
    companion object {
        fun parse(rawScopes: String?): List<SecurityScope> =
            rawScopes
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.split(Regex("\\s+"))
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?.map { SecurityScope(it) }
                ?: emptyList()
    }
}
