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
