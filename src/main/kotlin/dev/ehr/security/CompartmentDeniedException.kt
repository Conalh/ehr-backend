package dev.ehr.security

import java.util.UUID

/**
 * Thrown when a post-fetch compartment re-evaluation denies inside a
 * transaction. Callers catch it outside the transaction boundary so the
 * denial audit row survives the rollback.
 */
class CompartmentDeniedException(
    val decision: PolicyDecision,
    val patientId: UUID,
    val resourceId: UUID?,
) : RuntimeException("compartment access denied")
