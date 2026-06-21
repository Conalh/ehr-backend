package dev.ehr.security

import dev.ehr.identity.OAuthClientType

enum class SmartContext {
    PATIENT,
    USER,
    SYSTEM,
}

/**
 * A parsed SMART clinical scope: `patient|user|system / Resource|* . permissions`.
 * Accepts SMART v1 permissions (`read`, `write`, `*`) and v2 subsets of `cruds`.
 * Non-clinical scopes (openid, launch, fhirUser, ...) parse to null and never authorize.
 */
data class SmartScope(
    val raw: String,
    val context: SmartContext,
    val resourceType: String,
    val canRead: Boolean,
    val canWrite: Boolean,
) {
    fun coversResource(fhirResourceName: String): Boolean =
        resourceType == "*" || resourceType == fhirResourceName

    companion object {
        private val V1_PERMISSIONS = setOf("read", "write", "*")
        private val SCOPE_PATTERN = Regex("^(patient|user|system)/([A-Za-z]+|\\*)\\.([a-z*]+)$")

        fun parse(raw: String): SmartScope? {
            val match = SCOPE_PATTERN.matchEntire(raw) ?: return null
            val (contextPart, resourcePart, permissionPart) = match.destructured

            val context = when (contextPart) {
                "patient" -> SmartContext.PATIENT
                "user" -> SmartContext.USER
                else -> SmartContext.SYSTEM
            }

            val canRead: Boolean
            val canWrite: Boolean
            if (permissionPart in V1_PERMISSIONS) {
                canRead = permissionPart == "read" || permissionPart == "*"
                canWrite = permissionPart == "write" || permissionPart == "*"
            } else {
                // SMART v2: an ordered, non-repeating subset of c r u d s.
                if (permissionPart.isEmpty() ||
                    permissionPart.any { it !in "cruds" } ||
                    permissionPart.toSet().size != permissionPart.length
                ) {
                    return null
                }
                canRead = permissionPart.any { it == 'r' || it == 's' }
                canWrite = permissionPart.any { it == 'c' || it == 'u' || it == 'd' }
            }

            return SmartScope(
                raw = raw,
                context = context,
                resourceType = resourcePart,
                canRead = canRead,
                canWrite = canWrite,
            )
        }
    }
}

object SmartScopeCompatibility {
    fun isAllowedForClientType(
        scope: SecurityScope,
        clientType: OAuthClientType,
    ): Boolean {
        val smartScope = SmartScope.parse(scope.rawValue) ?: return true
        return when (clientType) {
            OAuthClientType.SYSTEM -> smartScope.context == SmartContext.SYSTEM
            OAuthClientType.PUBLIC,
            OAuthClientType.CONFIDENTIAL,
            -> smartScope.context != SmartContext.SYSTEM
        }
    }

    fun areAllowedForClientType(
        scopes: List<SecurityScope>,
        clientType: OAuthClientType,
    ): Boolean =
        scopes.all { isAllowedForClientType(it, clientType) }

    fun isCompatibleWithPrincipal(
        smartScope: SmartScope,
        principal: SecurityPrincipal,
    ): Boolean =
        when (smartScope.context) {
            SmartContext.SYSTEM -> principal.subject.userId == null
            SmartContext.USER -> principal.subject.userId != null
            SmartContext.PATIENT -> principal.subject.userId != null && principal.subject.launchPatientId != null
        }
}
