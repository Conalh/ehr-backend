package dev.ehr.security

import dev.ehr.identity.OrganizationId

/**
 * Holds the authenticated request's organization so connections borrowed
 * during the request can carry it as the ehr.organization_id GUC (the
 * predicate tenant RLS filters on). Bound and cleared by TenantContextFilter.
 */
object TenantContextHolder {
    private val current = ThreadLocal<OrganizationId?>()

    fun set(organizationId: OrganizationId) {
        current.set(organizationId)
    }

    fun currentOrganizationId(): OrganizationId? = current.get()

    fun clear() {
        current.remove()
    }
}
