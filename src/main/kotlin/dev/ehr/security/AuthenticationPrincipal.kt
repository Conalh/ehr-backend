package dev.ehr.security

import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.server.ResponseStatusException

fun Authentication.securityPrincipal(): SecurityPrincipal =
    principal as? SecurityPrincipal
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Security principal is not available")
