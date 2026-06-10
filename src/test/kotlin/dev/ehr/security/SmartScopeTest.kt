package dev.ehr.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartScopeTest {
    @Test
    fun `parses smart v1 scopes`() {
        val read = SmartScope.parse("user/Patient.read")!!
        assertEquals(SmartContext.USER, read.context)
        assertEquals("Patient", read.resourceType)
        assertTrue(read.canRead)
        assertFalse(read.canWrite)

        val write = SmartScope.parse("system/*.write")!!
        assertEquals(SmartContext.SYSTEM, write.context)
        assertEquals("*", write.resourceType)
        assertFalse(write.canRead)
        assertTrue(write.canWrite)

        val both = SmartScope.parse("user/*.*")!!
        assertTrue(both.canRead)
        assertTrue(both.canWrite)

        val patientContext = SmartScope.parse("patient/Observation.read")!!
        assertEquals(SmartContext.PATIENT, patientContext.context)
    }

    @Test
    fun `parses smart v2 scopes`() {
        val rs = SmartScope.parse("user/Patient.rs")!!
        assertTrue(rs.canRead)
        assertFalse(rs.canWrite)

        val cud = SmartScope.parse("user/Condition.cud")!!
        assertFalse(cud.canRead)
        assertTrue(cud.canWrite)

        val cruds = SmartScope.parse("system/Observation.cruds")!!
        assertTrue(cruds.canRead)
        assertTrue(cruds.canWrite)

        val createOnly = SmartScope.parse("user/*.c")!!
        assertFalse(createOnly.canRead)
        assertTrue(createOnly.canWrite)
    }

    @Test
    fun `rejects non smart and malformed scopes`() {
        listOf(
            "openid",
            "fhirUser",
            "launch/patient",
            "offline_access",
            "user/Patient",
            "user/Patient.",
            "user/Patient.rx",
            "user/Patient.rr",
            "user/Patient.readwrite",
            "admin/Patient.read",
            "user//read",
            "user/Patient.READ",
        ).forEach { raw ->
            assertNull(SmartScope.parse(raw), "expected '$raw' to be rejected")
        }
    }

    @Test
    fun `resource coverage matches exact names and wildcards`() {
        val wildcard = SmartScope.parse("user/*.read")!!
        assertTrue(wildcard.coversResource("Patient"))
        assertTrue(wildcard.coversResource("DiagnosticReport"))

        val exact = SmartScope.parse("user/Patient.read")!!
        assertTrue(exact.coversResource("Patient"))
        assertFalse(exact.coversResource("Observation"))
    }
}
