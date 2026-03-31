# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./mvnw spring-boot:run       # Run the application
./mvnw clean package         # Build
./mvnw test                  # Run all tests
./mvnw test -Dtest=ClassName # Run a single test class
```

Requires a running PostgreSQL instance (`localhost:5432/shorter_url`) and AWS credentials (see `application.yaml`).

## Architecture

This is a Spring Boot 3.5 URL shortening service with AWS S3 integration.

**Main package:** `com.hhh.url.shorter_url`

### Layers

- **controller/** — REST endpoints. `UrlController` handles URL CRUD and redirect. `ObjectStorageController` handles S3 presigned URL generation and object verification.
- **service/impl/** — Business logic. `UrlServiceImpl` manages URL creation/redirect; `ObjectStorageServiceImpl` wraps AWS SDK v3 S3 operations.
- **model/** — JPA entities. `Url` is the core entity; `UrlFileBatches` / `UrlFileBatchRecords` support bulk import (Spring Batch infrastructure in place, not fully implemented).
- **dto/** — Request/response objects. `UrlRequest` validates input; `UrlResponse` is the standard output; `BulkUrlResponse` covers batch results.
- **mapper/** — MapStruct mappers (`UrlMapper`) for entity↔DTO conversion.
- **exception/** — `GlobalExceptionHandler` (@RestControllerAdvice) catches `ResourceNotFoundException` (404) and `BadRequestException` (400).
- **common/** — `ApiResponse<T>` wraps all API responses uniformly; `AuditConfig` enables JPA auditing.

### Key flows

**URL shortening:** `POST /api/v1/urls` → `UrlServiceImpl.create()` → generates Base62 short code from the DB-assigned `id` via `Base62Code` util → stores with 5-day expiry → returns `UrlResponse`.

**Redirect:** `GET /api/v1/urls/redirect?shortCode=xxx` → lookup by short code → return original URL.

**S3 / template:** On `ApplicationReadyEvent`, `UrlServiceImpl` checks if the template file (`classpath:/static/import_sample.xlsx`) exists in S3 and uploads or updates it if the SHA-256 hash differs. The template download endpoint (`GET /api/v1/urls/template`) returns a presigned GET URL valid for 15 minutes.

### Configuration

- **Database:** `spring.datasource.*` in `application.yaml` (PostgreSQL, Liquibase migrations in `src/main/resources/db/changelog/`).
- **AWS S3:** `aws.s3.*` — bucket, region, access/secret keys. Keys default to hardcoded values in `application.yaml`; override with `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` env vars.
- **Health:** `/actuator/health` includes a custom `S3HealthIndicator` that probes bucket connectivity.

### Schema management

Liquibase is used (`ddl-auto: validate`). Migration files live in `src/main/resources/db/changelog/`. The baseline script seeds `~40` example URL records.
