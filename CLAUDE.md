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

Spring Boot 3.5 / Java 17 URL shortening service with AWS S3 integration and Spring Batch bulk import.

**Main package:** `com.hhh.url.shorter_url`

### Layers

- **controller/** — REST endpoints. `UrlController` handles URL CRUD and redirect. `ObjectStorageController` handles S3 presigned URL generation and object verification. `BulkController` handles Excel bulk import.
- **service/impl/** — Business logic. `UrlServiceImpl` manages URL creation/redirect; `ObjectStorageServiceImpl` wraps AWS SDK v3 S3 operations; `BulkUrlServiceImpl` creates batch records and launches Spring Batch jobs.
- **batch/** — Spring Batch pipeline (`UrlExcelItemReader` → `UrlBatchItemProcessor` → `UrlBatchItemWriter`) configured in `batch/config/UrlBatchJobConfig`. Jobs run asynchronously via `AsyncConfig`'s `batchExecutor` thread pool (core=2, max=5).
- **model/** — JPA entities. `Url` is the core entity (fields: `shortCode`, `domain`, `originalUrl`, `expiredAt`, `customAlias`, `description`, `tags`). `UrlFileBatches` / `UrlFileBatchRecords` track batch job state.
- **dto/** — Request/response objects. `UrlRequest` validates input; `UrlResponse` is the standard output; `BulkUploadResponse` returns the `batchId`; `BatchStatusResponse` returns job progress.
- **mapper/** — MapStruct mappers (`UrlMapper`) for entity↔DTO conversion.
- **exception/** — `GlobalExceptionHandler` (@RestControllerAdvice) catches `ResourceNotFoundException` (404), `BadRequestException` (400), and `UrlExpiredException` (410 Gone). `UrlExpiredException` is thrown when a URL exists but its `expiredAt` is in the past.
- **common/** — `ApiResponse<T>` wraps all API responses uniformly; `AuditConfig` enables JPA auditing.

### Key flows

**URL shortening:** `POST /api/v1/urls` → `UrlServiceImpl.create()` → if `customAlias` provided, sets it as `shortCode` (skips Base62); otherwise generates Base62 short code from the DB-assigned `id` via `Base62Code` util → stores with 5-day expiry → returns `UrlResponse`. Duplicate `customAlias` raises `BadRequestException` (400) via `DataIntegrityViolationException` catch.

**Redirect:** `GET /api/v1/urls/redirect?shortCode=xxx` → lookup by short code → check `expiredAt` (throws `UrlExpiredException` 410 if past) → return original URL.

**Bulk import:** `POST /api/v1/bulk` (body: `ImportFileRequest` with `objectUrl` + `fileName`) → `BulkUrlServiceImpl.createBatch()` → verifies the S3 object exists → saves a `UrlFileBatches` record (PENDING) → launches `urlImportJob` asynchronously with `objectStoragePath` and `batchId` job parameters. Poll status via `GET /api/v1/bulk/{batchId}`. Batch step is fault-tolerant: bad rows are skipped up to `app.batch.skip-limit` (default 1000); chunk size defaults to 100.

**S3 / template:** On `ApplicationReadyEvent`, `UrlServiceImpl` checks if the template file (`classpath:/static/import_sample.xlsx`) exists in S3 and uploads or updates it if the SHA-256 hash differs. The template download endpoint (`GET /api/v1/urls/template`) returns a presigned GET URL valid for 15 minutes.

### Configuration

- **Database:** `spring.datasource.*` in `application.yaml` (PostgreSQL, Liquibase migrations in `src/main/resources/db/changelog/`).
- **AWS S3:** `aws.s3.*` — bucket, region, access/secret keys. Keys default to hardcoded values in `application.yaml`; override with `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` env vars.
- **Batch tuning:** `app.batch.chunk-size` (default 100) and `app.batch.skip-limit` (default 1000) control the Spring Batch step behavior.
- **Health:** `/actuator/health` includes a custom `S3HealthIndicator` that probes bucket connectivity.

### Schema management

Liquibase is used (`ddl-auto: validate`). Migration files live in `src/main/resources/db/changelog/`. Migrations: `V1` creates the `urls` table and seeds ~40 example records; `V2` creates the batch tables (`url_file_batches`, `url_file_batch_records`); `V3` adds `custom_alias` (unique), `description`, and `tags` columns to `urls`.

### Rule entity
- **Short Code**: Generated via Base62 encoding of the id. When `customAlias` is provided on create/update, it is stored directly as `shortCode` and Base62 generation is skipped.
- **Custom Alias**: Optional user-defined string (`^[a-zA-Z0-9-]{1,100}$`), unique constraint on DB. Stored in both `customAlias` and `shortCode` columns.
- **Priority**: Redirect resolves purely by `shortCode`; no secondary alias lookup needed.
- **Expiry**: `redirect()` checks `expiredAt < now()` and throws `UrlExpiredException` (410 Gone) for expired links.