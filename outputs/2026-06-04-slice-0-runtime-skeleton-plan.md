# Slice 0 Runtime Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first runnable backend slice: a Spring Boot Kotlin service with Gradle wrapper, Postgres Docker Compose, Flyway migrations, health endpoint, correlation IDs, and integration tests.

**Architecture:** This slice creates only runtime infrastructure. It deliberately avoids clinical resources, FHIR endpoints, auth, terminology, and tenancy tables so the service can boot and verify its foundation before clinical behavior is added. It uses current-state relational storage as the committed architectural path.

**Tech Stack:** Kotlin, Spring Boot 3.5.14, Spring MVC, Gradle wrapper, PostgreSQL 16, Flyway, Testcontainers, JUnit 5, Docker Compose.

---

## File Structure

Create or modify these files:

- `settings.gradle.kts`: Gradle project name.
- `build.gradle.kts`: Spring Boot Kotlin build, dependencies, Java 17 toolchain.
- `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`: generated Gradle wrapper from Spring Initializr.
- `.gitignore`: ignores build output, IDE files, local env files, and generated runtime data.
- `docker-compose.yml`: local Postgres for app development.
- `src/main/kotlin/dev/ehr/EhrCoreApplication.kt`: Spring Boot entry point.
- `src/main/kotlin/dev/ehr/runtime/HealthController.kt`: internal service health endpoint.
- `src/main/kotlin/dev/ehr/runtime/CorrelationIdFilter.kt`: request correlation ID filter.
- `src/main/resources/application.yml`: app name, server port, actuator exposure, datasource defaults, Flyway.
- `src/main/resources/db/migration/V1__runtime_skeleton.sql`: initial schema marker migration.
- `src/test/kotlin/dev/ehr/runtime/HealthEndpointTest.kt`: MVC health endpoint test.
- `src/test/kotlin/dev/ehr/runtime/CorrelationIdFilterTest.kt`: correlation ID response header test.
- `src/test/kotlin/dev/ehr/runtime/DatabaseMigrationTest.kt`: Testcontainers Postgres + Flyway migration test.
- `README.md`: local run commands and Slice 0 scope statement.

## Task 1: Generate Spring Boot Kotlin Skeleton

**Files:**

- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `src/main/kotlin/dev/ehr/EhrCoreApplication.kt`
- Create: `src/test/kotlin/dev/ehr/EhrCoreApplicationTests.kt`

- [ ] **Step 1: Generate the project with Spring Initializr**

Run:

```powershell
$zip = 'work\ehr-core-initializr.zip'
$out = 'work\ehr-core-initializr'
Remove-Item -Recurse -Force -LiteralPath $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path 'work' | Out-Null
$uri = 'https://start.spring.io/starter.zip?type=gradle-project-kotlin&language=kotlin&bootVersion=3.5.14.RELEASE&packaging=jar&javaVersion=17&groupId=dev.ehr&artifactId=ehr-core&name=ehr-core&description=EHR%20clinical%20core%20backend&packageName=dev.ehr&dependencies=web,actuator,validation,jdbc,jooq,flyway,postgresql,testcontainers'
curl.exe -L $uri -o $zip
tar.exe -xf $zip -C work
```

Expected:

```text
work\ehr-core contains a generated Spring Boot Gradle Kotlin project.
```

- [ ] **Step 2: Copy generated project files into the repository root**

Run:

```powershell
Copy-Item -Recurse -Force -Path 'work\ehr-core\*' -Destination '.'
```

Expected:

```text
The repository root contains gradlew, build.gradle.kts, settings.gradle.kts, and src/.
```

- [ ] **Step 3: Replace the generated main application with the package-stable entry point**

Set `src/main/kotlin/dev/ehr/EhrCoreApplication.kt` to:

```kotlin
package dev.ehr

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EhrCoreApplication

fun main(args: Array<String>) {
    runApplication<EhrCoreApplication>(*args)
}
```

- [ ] **Step 4: Keep the generated context-load test**

Set `src/test/kotlin/dev/ehr/EhrCoreApplicationTests.kt` to:

```kotlin
package dev.ehr

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class EhrCoreApplicationTests {
    @Test
    fun contextLoads() {
    }
}
```

- [ ] **Step 5: Run the generated test suite**

Run:

```powershell
.\gradlew.bat test
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 6: Commit the generated runtime skeleton**

Run:

```powershell
git add settings.gradle.kts build.gradle.kts gradlew gradlew.bat gradle src/main/kotlin/dev/ehr/EhrCoreApplication.kt src/test/kotlin/dev/ehr/EhrCoreApplicationTests.kt
git commit -m "feat: initialize Spring Boot runtime skeleton"
```

## Task 2: Add Repository Hygiene And Local Runtime Configuration

**Files:**

- Create: `.gitignore`
- Create: `docker-compose.yml`
- Create: `src/main/resources/application.yml`

- [ ] **Step 1: Add `.gitignore`**

Set `.gitignore` to:

```gitignore
.gradle/
build/
out/
*.class

.idea/
.vscode/
*.iml

.env
.env.*
!.env.example

work/
*.log

docker-data/
```

- [ ] **Step 2: Add Docker Compose for local Postgres**

Set `docker-compose.yml` to:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: ehr-core-postgres
    environment:
      POSTGRES_DB: ehr_core
      POSTGRES_USER: ehr_core
      POSTGRES_PASSWORD: ehr_core_dev
    ports:
      - "54328:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ehr_core -d ehr_core"]
      interval: 5s
      timeout: 3s
      retries: 20
    volumes:
      - ehr-core-postgres-data:/var/lib/postgresql/data

volumes:
  ehr-core-postgres-data:
```

- [ ] **Step 3: Add application configuration**

Set `src/main/resources/application.yml` to:

```yaml
spring:
  application:
    name: ehr-core
  datasource:
    url: ${EHR_DB_URL:jdbc:postgresql://localhost:54328/ehr_core}
    username: ${EHR_DB_USERNAME:ehr_core}
    password: ${EHR_DB_PASSWORD:ehr_core_dev}
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: ${PORT:8080}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true

logging:
  pattern:
    level: "%5p [correlationId:%X{correlationId:-none}]"
```

- [ ] **Step 4: Verify configuration files are parseable**

Run:

```powershell
.\gradlew.bat test
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit runtime configuration**

Run:

```powershell
git add .gitignore docker-compose.yml src/main/resources/application.yml
git commit -m "chore: add local runtime configuration"
```

## Task 3: Add Internal Health Endpoint With TDD

**Files:**

- Create: `src/test/kotlin/dev/ehr/runtime/HealthEndpointTest.kt`
- Create: `src/main/kotlin/dev/ehr/runtime/HealthController.kt`

- [ ] **Step 1: Write the failing health endpoint test**

Create `src/test/kotlin/dev/ehr/runtime/HealthEndpointTest.kt`:

```kotlin
package dev.ehr.runtime

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(controllers = [HealthController::class])
class HealthEndpointTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `internal health returns service status`() {
        mockMvc.get("/internal/health")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
                jsonPath("$.service") { value("ehr-core") }
            }
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests dev.ehr.runtime.HealthEndpointTest
```

Expected:

```text
Compilation fails because HealthController is not defined.
```

- [ ] **Step 3: Implement the minimal health controller**

Create `src/main/kotlin/dev/ehr/runtime/HealthController.kt`:

```kotlin
package dev.ehr.runtime

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/health")
class HealthController {
    @GetMapping
    fun getHealth(): HealthResponse =
        HealthResponse(
            status = "UP",
            service = "ehr-core",
        )
}

data class HealthResponse(
    val status: String,
    val service: String,
)
```

- [ ] **Step 4: Run the endpoint test and verify it passes**

Run:

```powershell
.\gradlew.bat test --tests dev.ehr.runtime.HealthEndpointTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit the health endpoint**

Run:

```powershell
git add src/test/kotlin/dev/ehr/runtime/HealthEndpointTest.kt src/main/kotlin/dev/ehr/runtime/HealthController.kt
git commit -m "feat: add internal health endpoint"
```

## Task 4: Add Flyway Migration With Testcontainers

**Files:**

- Create: `src/main/resources/db/migration/V1__runtime_skeleton.sql`
- Create: `src/test/kotlin/dev/ehr/runtime/DatabaseMigrationTest.kt`

- [ ] **Step 1: Write the failing migration test**

Create `src/test/kotlin/dev/ehr/runtime/DatabaseMigrationTest.kt`:

```kotlin
package dev.ehr.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Testcontainers
class DatabaseMigrationTest {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `flyway applies runtime skeleton migration`() {
        val marker = jdbcTemplate.queryForObject(
            "select name from runtime_markers where name = ?",
            String::class.java,
            "runtime-skeleton",
        )

        assertEquals("runtime-skeleton", marker)
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ehr_core_test")
            .withUsername("ehr_core")
            .withPassword("ehr_core_dev")
    }
}
```

- [ ] **Step 2: Run the migration test and verify it fails**

Run:

```powershell
.\gradlew.bat test --tests dev.ehr.runtime.DatabaseMigrationTest
```

Expected:

```text
The test fails because relation "runtime_markers" does not exist.
```

- [ ] **Step 3: Add the initial Flyway migration**

Create `src/main/resources/db/migration/V1__runtime_skeleton.sql`:

```sql
create table runtime_markers (
    name text primary key,
    created_at timestamptz not null default now()
);

insert into runtime_markers (name)
values ('runtime-skeleton');
```

- [ ] **Step 4: Run the migration test and verify it passes**

Run:

```powershell
.\gradlew.bat test --tests dev.ehr.runtime.DatabaseMigrationTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit Flyway migration support**

Run:

```powershell
git add src/main/resources/db/migration/V1__runtime_skeleton.sql src/test/kotlin/dev/ehr/runtime/DatabaseMigrationTest.kt
git commit -m "feat: add Flyway migration smoke test"
```

## Task 5: Add Correlation ID Filter With TDD

**Files:**

- Create: `src/test/kotlin/dev/ehr/runtime/CorrelationIdFilterTest.kt`
- Create: `src/main/kotlin/dev/ehr/runtime/CorrelationIdFilter.kt`

- [ ] **Step 1: Write the failing correlation ID tests**

Create `src/test/kotlin/dev/ehr/runtime/CorrelationIdFilterTest.kt`:

```kotlin
package dev.ehr.runtime

import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(controllers = [HealthController::class])
@Import(CorrelationIdFilter::class)
class CorrelationIdFilterTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `generates a correlation id when request has none`() {
        mockMvc.get("/internal/health")
            .andExpect {
                status { isOk() }
                header {
                    string(
                        "X-Correlation-Id",
                        matchesPattern("[0-9a-fA-F-]{36}"),
                    )
                }
            }
    }

    @Test
    fun `echoes an accepted inbound correlation id`() {
        mockMvc.get("/internal/health") {
            header("X-Correlation-Id", "req-12345")
        }.andExpect {
            status { isOk() }
            header { string("X-Correlation-Id", "req-12345") }
        }
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run:

```powershell
.\gradlew.bat test --tests dev.ehr.runtime.CorrelationIdFilterTest
```

Expected:

```text
Compilation fails because CorrelationIdFilter is not defined.
```

- [ ] **Step 3: Implement the correlation ID filter**

Create `src/main/kotlin/dev/ehr/runtime/CorrelationIdFilter.kt`:

```kotlin
package dev.ehr.runtime

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = request.getHeader(CORRELATION_ID_HEADER)
            ?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: UUID.randomUUID().toString()

        response.setHeader(CORRELATION_ID_HEADER, correlationId)
        MDC.put(MDC_KEY, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val CORRELATION_ID_HEADER = "X-Correlation-Id"
        const val MDC_KEY = "correlationId"
    }
}
```

- [ ] **Step 4: Run the correlation ID tests and verify they pass**

Run:

```powershell
.\gradlew.bat test --tests dev.ehr.runtime.CorrelationIdFilterTest
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Commit correlation ID support**

Run:

```powershell
git add src/test/kotlin/dev/ehr/runtime/CorrelationIdFilterTest.kt src/main/kotlin/dev/ehr/runtime/CorrelationIdFilter.kt
git commit -m "feat: add request correlation id filter"
```

## Task 6: Add README And Full Slice Verification

**Files:**

- Create: `README.md`

- [ ] **Step 1: Add README**

Create `README.md`:

```markdown
# EHR Core

Synthetic-only EHR backend foundation.

This repository is building a production-shaped clinical record backend. It is not approved for real PHI, care delivery, prescribing, billing, clinical decision support, HIPAA compliance claims, or ONC certification claims.

## Slice 0 Scope

Slice 0 provides the runtime skeleton:

- Kotlin Spring Boot service
- Gradle wrapper
- Postgres Docker Compose
- Flyway migration
- internal health endpoint
- correlation ID response header and MDC value
- Testcontainers migration test

## Local Requirements

- Java 17
- Docker

Gradle is provided by the checked-in wrapper.

## Run Tests

```powershell
.\gradlew.bat test
```

## Start Local Database

```powershell
docker compose up -d postgres
```

## Run The App

```powershell
.\gradlew.bat bootRun
```

## Health Check

```powershell
curl.exe -i http://localhost:8080/internal/health
```

Expected response body:

```json
{"status":"UP","service":"ehr-core"}
```
```

- [ ] **Step 2: Run the full test suite**

Run:

```powershell
.\gradlew.bat test
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: Start local Postgres**

Run:

```powershell
docker compose up -d postgres
```

Expected:

```text
Container ehr-core-postgres is healthy.
```

- [ ] **Step 4: Start the application**

Run:

```powershell
.\gradlew.bat bootRun
```

Expected:

```text
Started EhrCoreApplication
Tomcat started on port 8080
```

- [ ] **Step 5: Verify the health endpoint in another shell**

Run:

```powershell
curl.exe -i http://localhost:8080/internal/health
```

Expected:

```text
HTTP/1.1 200
X-Correlation-Id: <non-empty value>

{"status":"UP","service":"ehr-core"}
```

- [ ] **Step 6: Stop the application and database**

Stop the `bootRun` process with `Ctrl+C`, then run:

```powershell
docker compose down
```

Expected:

```text
Container ehr-core-postgres is removed.
```

- [ ] **Step 7: Commit README and verified Slice 0**

Run:

```powershell
git add README.md
git commit -m "docs: document Slice 0 runtime workflow"
```

## Self-Review Checklist

- Slice 0 creates runtime infrastructure only.
- The generated project uses Spring Boot 3.5.14 and Java 17.
- No real PHI paths are introduced.
- No clinical resource tables are introduced.
- Flyway is verified against Postgres through Testcontainers.
- Correlation IDs are returned to clients and placed in MDC.
- The app can run locally against Docker Compose Postgres.
- Every implementation task has a test or command-level verification.

