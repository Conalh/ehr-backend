# Patient FHIR Search AND Semantics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make FHIR `Patient` search correctly AND `_id` and `identifier` when both parameters are present, returning an empty searchset when either parameter does not match the same patient.

**Architecture:** Keep `PatientFhirController` as the thin FHIR boundary and reuse the existing `PatientService` methods. `_id` remains an honest search parameter with empty-bundle non-matches, while `identifier` remains a strict `system|value` token. The controller will resolve both optional filters and intersect them in memory because each branch can return at most one patient in this model.

**Tech Stack:** Kotlin, Spring MVC, HAPI FHIR R4 model types, MockMvc integration tests, Gradle/Testcontainers.

---

## File Structure

- Modify: `src/test/kotlin/dev/ehr/fhir/PatientFhirApiIntegrationTest.kt`
  - Add regression coverage for combined `_id` + `identifier` search where both match the same patient.
  - Add regression coverage where `_id` points at patient A and `identifier` points at patient B, expecting an empty Bundle.
- Modify: `src/main/kotlin/dev/ehr/fhir/PatientFhirController.kt`
  - Replace the current `_id` early-return branch with search-parameter intersection logic.
  - Preserve existing semantics for `_id`-only, `identifier`-only, invalid `_id`, invalid/missing `identifier`, cross-tenant `_id`, and cross-tenant identifier searches.

No new production abstractions are needed.

---

### Task 1: Add Regression Tests For Combined Patient Search

**Files:**
- Modify: `src/test/kotlin/dev/ehr/fhir/PatientFhirApiIntegrationTest.kt`

- [ ] **Step 1: Write the failing test**

Add this test below `fhir patient search by _id returns a singleton or an honest empty bundle`:

```kotlin
@Test
fun `fhir patient search combines id and identifier with and semantics`() {
    val member = createMember(MembershipRole.CLINICIAN, "user/Patient.read")
    val patientA = createPatient(member.organization)
    val patientB = createPatient(member.organization)
    val identifierSystem = "urn:ehr:mrn:${UUID.randomUUID()}"
    patientRepository.addIdentifier(
        TenantScope(member.organization.id),
        patientA.id,
        PatientIdentifierCreateCommand(system = identifierSystem, value = "MRN-A"),
    )
    patientRepository.addIdentifier(
        TenantScope(member.organization.id),
        patientB.id,
        PatientIdentifierCreateCommand(system = identifierSystem, value = "MRN-B"),
    )

    mockMvc.get("/fhir/r4/Patient") {
        param("_id", patientA.id.value.toString())
        param("identifier", "$identifierSystem|MRN-A")
        header("Authorization", "Bearer ${member.token}")
    }.andExpect {
        status { isOk() }
        jsonPath("$.resourceType") { value("Bundle") }
        jsonPath("$.type") { value("searchset") }
        jsonPath("$.total") { value(1) }
        jsonPath("$.entry[0].resource.id") { value(patientA.id.value.toString()) }
    }

    mockMvc.get("/fhir/r4/Patient") {
        param("_id", patientA.id.value.toString())
        param("identifier", "$identifierSystem|MRN-B")
        header("Authorization", "Bearer ${member.token}")
    }.andExpect {
        status { isOk() }
        jsonPath("$.resourceType") { value("Bundle") }
        jsonPath("$.type") { value("searchset") }
        jsonPath("$.total") { value(0) }
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails for the known bug**

Run:

```powershell
$env:PATH = (($env:PATH -split ';') | Where-Object { $_ -notlike '\\?\*' }) -join ';'
.\gradlew.bat test --tests dev.ehr.fhir.PatientFhirApiIntegrationTest
```

Expected:

```text
PatientFhirApiIntegrationTest > fhir patient search combines id and identifier with and semantics() FAILED
```

The failure should be on the second request because the current controller returns patient A whenever `_id` is present and ignores `identifier`.

---

### Task 2: Implement Minimal AND Semantics In `PatientFhirController`

**Files:**
- Modify: `src/main/kotlin/dev/ehr/fhir/PatientFhirController.kt`

- [ ] **Step 1: Replace the `_id` early return with combined filter logic**

In `search(...)`, replace the current `if (id != null) { return ... }` block and the later identifier-only branch with this logic:

```kotlin
val idResults = if (id != null) {
    parsePatientId(id)
        ?.let { patientId ->
            try {
                listOf(patientService.get(principal, patientId))
            } catch (notFound: ResponseStatusException) {
                if (notFound.statusCode == HttpStatus.NOT_FOUND) emptyList() else throw notFound
            }
        }
        ?: emptyList()
} else {
    null
}

val identifierResults = if (identifier != null) {
    val token = parseIdentifierToken(identifier)
        ?: return responses.operationOutcome(
            HttpStatus.BAD_REQUEST,
            OperationOutcome.IssueType.INVALID,
            "The identifier search parameter must be in system|value form",
        )
    patientService.searchByIdentifier(principal, token.first, token.second)
} else {
    null
}

if (idResults == null && identifierResults == null) {
    return responses.operationOutcome(
        HttpStatus.BAD_REQUEST,
        OperationOutcome.IssueType.INVALID,
        "The identifier or _id search parameter is required",
    )
}

val results = when {
    idResults != null && identifierResults != null -> {
        val identifierIds = identifierResults.map { it.patient.id }.toSet()
        idResults.filter { it.patient.id in identifierIds }
    }
    idResults != null -> idResults
    else -> identifierResults.orEmpty()
}

return responses.resource(HttpStatus.OK, searchBundle(results))
```

Keep this wrapped in the existing `try/catch (ResponseStatusException)` pattern so existing OperationOutcome conversion remains intact.

- [ ] **Step 2: Confirm existing invalid-request behavior is intentionally preserved**

After the change, these cases must still behave as follows:

```text
GET /fhir/r4/Patient
=> 400 OperationOutcome invalid

GET /fhir/r4/Patient?identifier=missing-separator
=> 400 OperationOutcome invalid

GET /fhir/r4/Patient?_id=not-a-uuid
=> 200 Bundle total=0

GET /fhir/r4/Patient?_id=not-a-uuid&identifier=missing-separator
=> 400 OperationOutcome invalid
```

That last combined invalid case is acceptable because a supplied malformed token should be refused rather than silently ignored.

---

### Task 3: Verify Focused And Full Behavior

**Files:**
- Test: `src/test/kotlin/dev/ehr/fhir/PatientFhirApiIntegrationTest.kt`

- [ ] **Step 1: Run the focused Patient FHIR integration suite**

Run:

```powershell
$env:PATH = (($env:PATH -split ';') | Where-Object { $_ -notlike '\\?\*' }) -join ';'
.\gradlew.bat test --tests dev.ehr.fhir.PatientFhirApiIntegrationTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 2: Run the full suite**

Run:

```powershell
$env:PATH = (($env:PATH -split ';') | Where-Object { $_ -notlike '\\?\*' }) -join ';'
.\gradlew.bat test
```

Expected:

```text
BUILD SUCCESSFUL
```

Confirm XML totals after the run:

```powershell
$xmlFiles = Get-ChildItem build\test-results\test -Filter 'TEST-*.xml'
$totals = [ordered]@{tests=0; failures=0; errors=0; skipped=0}
foreach ($file in $xmlFiles) {
  [xml]$xml = Get-Content $file.FullName
  $suite = $xml.testsuite
  $totals.tests += [int]$suite.tests
  $totals.failures += [int]$suite.failures
  $totals.errors += [int]$suite.errors
  $totals.skipped += [int]$suite.skipped
}
[pscustomobject]$totals | Format-List
```

Expected after adding the new test:

```text
tests    : 380
failures : 0
errors   : 0
skipped  : 0
```

- [ ] **Step 3: Run diff hygiene**

Run:

```powershell
git diff --check
```

Expected:

```text
exit 0
```

CRLF conversion warnings are acceptable in this Windows working tree if there are no whitespace-error lines.

---

## Self-Review

- Spec coverage: The plan fixes the only remaining inspection finding by making `_id` and `identifier` combine with AND semantics while preserving all single-parameter behavior.
- Placeholder scan: No placeholders remain; all test and implementation snippets are concrete.
- Type consistency: `PatientWithIdentifiers.patient.id` is used consistently with existing tests and mapper usage.

## Execution Note

Do not stage or commit these changes unless the user explicitly asks for a commit. The current working tree already contains P1/P2/P3 fixes and an untracked CI workflow, so keep this fix scoped to `PatientFhirController.kt` and `PatientFhirApiIntegrationTest.kt` during execution.
