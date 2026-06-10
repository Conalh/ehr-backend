package dev.ehr.fhir

import ca.uhn.fhir.context.FhirContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class FhirConfiguration {
    // FhirContext is expensive to create and thread-safe; parsers are created per use.
    @Bean
    fun fhirContext(): FhirContext = FhirContext.forR4()
}
