package dev.ehr.export

import dev.ehr.fhir.FhirCapabilityRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExportResourceCatalogTest {
    @Test
    fun `bulk export resource catalog covers every served FHIR resource`() {
        assertEquals(
            FhirCapabilityRegistry.supportedResources.map { it.type },
            ExportJobProcessor.exportedResourceTypes,
        )
    }
}
