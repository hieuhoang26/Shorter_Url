# Plan: Bulk URL Import via CSV/XLSX File Upload

**Date**: 2026-04-05  
**Status**: SUPERSEDED → see PLAN-20260405-bulk-url-import-v2.md  
**Completed**: 2026-04-05  
**Author**: Claude Code  

---

## 1. Requirement Summary

Build a bulk URL import feature that accepts a CSV/XLSX file containing a list of `original_url` values, generates a short code for each URL, persists them to the `urls` table, and tracks progress asynchronously via a batch record system. Users upload a file, receive a `batchId`, and can poll for processing status.

---

## 2. Scope

### In Scope
- `POST /api/v1/bulk` — accept `MultipartFile`, upload to S3, parse CSV, create batch + records, trigger async processing
- `GET /api/v1/bulk/{batchId}` — return current batch status and progress counters
- Custom `BatchStatus` enum: `PENDING, PROCESSING, COMPLETED, FAILED, PARTIAL_SUCCESS`
- Updated `RecordStatus` enum: `PENDING, SUCCESS, FAILED`
- Update `UrlFileBatches` entity (add missing fields, change id to UUID)
- Update `UrlFileBatchRecords` entity (add `processedAt`, align with new enum)
- Liquibase V2 migration for batch tables
- Repositories: `UrlFileBatchRepository`, `UrlFileBatchRecordRepository`
- `BulkUrlService` for file upload + CSV parsing
- `BatchProcessingService` for async chunk processing
- `@EnableAsync` configuration

### Out of Scope
- XLSX parsing (CSV only for now; XLSX requires Apache POI)
- Authentication / user-scoped batches
- Retry logic for failed records
- Download results as CSV
- WebSocket / SSE progress streaming

---

## 3. Technical Design

### 3.1 Components to Create

| Component | Type | Location | Responsibility |
|-----------|------|----------|----------------|
| `BatchStatus` | Enum | `com.hhh.url.shorter_url.util` | Custom batch-level status (replaces Spring Batch enum) |
| `BulkUrlService` | Interface | `com.hhh.url.shorter_url.service` | Contract for upload + status query |
| `BulkUrlServiceImpl` | Service | `com.hhh.url.shorter_url.service.impl` | File upload, CSV parse, batch/record creation |
| `BatchProcessingService` | Service | `com.hhh.url.shorter_url.service.impl` | Async chunk processing loop |
| `UrlFileBatchRepository` | Repository | `com.hhh.url.shorter_url.repository` | CRUD for `UrlFileBatches` |
| `UrlFileBatchRecordRepository` | Repository | `com.hhh.url.shorter_url.repository` | CRUD + custom queries for `UrlFileBatchRecords` |
| `BulkController` | Controller | `com.hhh.url.shorter_url.controller` | Endpoints `POST /api/v1/bulk`, `GET /api/v1/bulk/{batchId}` |
| `BulkUploadResponse` | DTO | `com.hhh.url.shorter_url.dto.response` | `{ batchId: UUID }` |
| `BatchStatusResponse` | DTO | `com.hhh.url.shorter_url.dto.response` | `{ status, progress, success, failed }` |
| `AsyncConfig` | Config | `com.hhh.url.shorter_url.config` | `@EnableAsync` + thread pool executor |
| `V2__create_batch_tables.sql` | Migration | `src/main/resources/db/changelog` | DDL for `url_file_batches` + `url_file_batch_records` |

### 3.2 Components to Modify

| Component | Location | Change Description |
|-----------|----------|--------------------|
| `RecordStatus` | `com.hhh.url.shorter_url.util` | Replace `ERROR, SUCCESS, RESOLVED` → `PENDING, SUCCESS, FAILED` |
| `UrlFileBatches` | `com.hhh.url.shorter_url.model.batch` | Change `id` to UUID, swap `BatchStatus` type to custom enum, add `processedRecords`, `startedAt`, `completedAt` |
| `UrlFileBatchRecords` | `com.hhh.url.shorter_url.model.batch` | Add `processedAt` field, update `RecordStatus` references |
| `db-changelog.xml` | `src/main/resources/db/changelog` | Include new `V2__create_batch_tables.sql` changeSet |
| `pom.xml` | root | Add `commons-csv` dependency for CSV parsing |

### 3.3 Data Model Changes

#### `UrlFileBatches` (updated)
```
UUID        id              PK, generated
VARCHAR     file_name       NOT NULL
VARCHAR     file_path       NOT NULL
VARCHAR(20) status          NOT NULL  (BatchStatus enum)
INT         total_records   NOT NULL DEFAULT 0
INT         processed_records NOT NULL DEFAULT 0
INT         success_records NOT NULL DEFAULT 0
INT         failed_records  NOT NULL DEFAULT 0
TIMESTAMP   started_at
TIMESTAMP   completed_at
-- inherited from BaseEntity:
TIMESTAMP   created_at
VARCHAR     created_by
TIMESTAMP   updated_at
VARCHAR     updated_by
```

#### `UrlFileBatchRecords` (updated)
```
UUID        id              PK, generated
UUID        batch_id        FK → url_file_batches.id
INT         row_number      NOT NULL
TEXT        original_url    NOT NULL
VARCHAR(50) short_code
VARCHAR(20) status          NOT NULL  (RecordStatus enum: PENDING/SUCCESS/FAILED)
TEXT        error_message
TIMESTAMP   processed_at
-- inherited from BaseEntity:
TIMESTAMP   created_at
VARCHAR     created_by
TIMESTAMP   updated_at
VARCHAR     updated_by
```

### 3.4 API Contract

**Upload file:**
```
POST /api/v1/bulk
Content-Type: multipart/form-data
Body: file (MultipartFile)

Response 202:
{ "data": { "batchId": "uuid" }, "message": "Batch created successfully" }
```

**Check status:**
```
GET /api/v1/bulk/{batchId}

Response 200:
{
  "data": {
    "status": "PROCESSING",
    "progress": 45,
    "success": 450,
    "failed": 12
  }
}
```
Note: `progress` = `processedRecords` (raw count, not percentage) to match the entity field.

### 3.5 Key Decisions

- **Decision**: Keep `UrlFileBatches.id` as UUID (not Long like the current draft entity)  
  **Reason**: Batch IDs are exposed externally in the API; UUID prevents ID enumeration  
  **Alternatives considered**: Keep Long — rejected due to security exposure

- **Decision**: Use Apache Commons CSV for parsing, not Apache POI  
  **Reason**: Task scope is CSV-only; POI adds significant dependency weight  
  **Alternatives considered**: OpenCSV — both are fine, Commons CSV is already Apache ecosystem

- **Decision**: Use Spring `@Async` with a dedicated thread pool, not Spring Batch Job infrastructure  
  **Reason**: The existing Spring Batch dependency was unused; the task describes a simpler while-loop chunk processor, not a full Job/Step model  
  **Alternatives considered**: Spring Batch Job — over-engineered for this requirement

- **Decision**: CSV column is expected to have `original_url` as the first (or only) column; skip header row  
  **Reason**: File format matches the template (`import_sample.xlsx`) already in S3  
  **Alternatives considered**: Named column lookup — will use Commons CSV header mapping for robustness

- **Decision**: `UrlFileBatches` does NOT extend `BaseEntity` for `startedAt`/`completedAt` — these are managed fields, but `createdAt` from `BaseEntity` is kept  
  **Reason**: `startedAt` and `completedAt` are business timestamps, not audit timestamps; map them as plain `@Column` fields  
  **Alternatives considered**: Add to BaseEntity — pollutes the audit abstraction

- **Decision**: Reuse existing `Base62Service.generateShortCode(id)` for short code generation  
  **Reason**: Existing, tested utility; `Url.id` is a BIGINT identity assigned by the DB on insert  
  **Alternatives considered**: Random UUID-based codes — not consistent with existing URL scheme

---

## 4. Implementation Steps

- [x] **Step 1**: Update `RecordStatus` enum — values: `PENDING, SUCCESS, FAILED`
- [x] **Step 2**: Create `BatchStatus` enum in `com.hhh.url.shorter_url.util` — values: `PENDING, PROCESSING, COMPLETED, FAILED, PARTIAL_SUCCESS`
- [x] **Step 3**: Update `UrlFileBatches` entity — change `id` to UUID, use custom `BatchStatus`, add `processedRecords`, `startedAt`, `completedAt`, remove `@OneToMany records` list (not needed for chunk processing)
- [x] **Step 4**: Update `UrlFileBatchRecords` entity — add `processedAt` (`OffsetDateTime`), update `RecordStatus` type, keep `@ManyToOne batch` FK
- [x] **Step 5**: Write `V2__create_batch_tables.sql` — DDL for both batch tables with correct types (UUID pk, UUID fk)
- [x] **Step 6**: Add V2 changeSet to `db-changelog.xml`
- [x] **Step 7**: Add `commons-csv` dependency to `pom.xml`
- [x] **Step 8**: Create `UrlFileBatchRepository extends JpaRepository<UrlFileBatches, UUID>`
- [x] **Step 9**: Create `UrlFileBatchRecordRepository extends JpaRepository<UrlFileBatchRecords, UUID>` with methods:
  - `findTop100ByBatchIdAndStatusOrderByRowNumber(UUID batchId, RecordStatus status)`
  - `countByBatchIdAndStatus(UUID batchId, RecordStatus status)`
- [x] **Step 10**: Create `AsyncConfig` with `@EnableAsync` and a `ThreadPoolTaskExecutor` bean named `batchExecutor`
- [x] **Step 11**: Create `BulkUrlService` interface with:
  - `UUID createBatch(MultipartFile file)`
  - `BatchStatusResponse getBatchStatus(UUID batchId)`
- [x] **Step 12**: Implement `BulkUrlServiceImpl.createBatch()`:
  - Upload file to S3 via `objectStorageService.uploadObject()`
  - Persist `UrlFileBatches` with `status=PENDING`
  - Parse CSV rows → bulk-insert `UrlFileBatchRecords` with `status=PENDING`
  - Update `totalRecords`
  - Trigger `batchProcessingService.processBatch(batchId)` via `@Async`
  - Return `batchId`
- [x] **Step 13**: Implement `BulkUrlServiceImpl.getBatchStatus()` — load batch by ID, map to `BatchStatusResponse`
- [x] **Step 14**: Implement `BatchProcessingService.processBatch(UUID batchId)` annotated `@Async("batchExecutor")`:
  - Mark batch `PROCESSING`, set `startedAt`
  - Loop: fetch 100 PENDING records; break if empty
  - For each record: create `Url`, call `base62Service.generateShortCode(url.getId())`, update record `SUCCESS` / `FAILED`
  - After each chunk: update `processedRecords`, `successRecords`, `failedRecords`
  - After loop: set final status (`COMPLETED` or `PARTIAL_SUCCESS`), set `completedAt`
- [x] **Step 15**: Create `BulkUploadResponse` record: `UUID batchId`
- [x] **Step 16**: Create `BatchStatusResponse` record: `String status, int progress, int success, int failed`
- [x] **Step 17**: Create `BulkController` with:
  - `POST /api/v1/bulk` — calls `bulkUrlService.createBatch()`, returns `202 Accepted`
  - `GET /api/v1/bulk/{batchId}` — calls `bulkUrlService.getBatchStatus()`, returns `200 OK`

---

[//]: # (## 5. Testing Strategy)

[//]: # ()
[//]: # (- **Unit — `BulkUrlServiceImpl`**: mock `ObjectStorageService`, `UrlFileBatchRepository`, `UrlFileBatchRecordRepository`, `BatchProcessingService`; test CSV parsing with valid/empty/malformed input)

[//]: # (- **Unit — `BatchProcessingService`**: mock `UrlFileBatchRecordRepository`, `UrlRepository`, `Base62Service`; verify status transitions, counter updates, PARTIAL_SUCCESS vs COMPLETED logic)

[//]: # (- **Integration — `BulkController`**: use `MockMvc`, upload a test CSV file, assert `202` + batchId in response; GET status endpoint returns shape)

[//]: # ()
[//]: # (---)

## 6. Risks & Open Questions

- **Risk**: `RecordStatus` enum change (`ERROR → FAILED`, `RESOLVED → PENDING`) is a breaking change if any existing data uses the old values → **Mitigation**: No Liquibase migration for batch tables exists yet, so no live data is affected; safe to rename
- **Risk**: `UrlFileBatches.id` type change from `Long` to `UUID` in existing entity → **Mitigation**: same as above — no V1 DDL for this table, no data loss
- **Open question**: Should the CSV file key in S3 be namespaced per batch (e.g., `batches/{batchId}/file.csv`) or flat? **Assumption**: use `batches/{batchId}/{originalFileName}` for isolation
- **Open question**: Does the `Url` entity need a `domain` field set during bulk import? **Assumption**: use `Constant.URL_LOCAL` as in `UrlServiceImpl.create()`

---

## 7. Estimated Complexity

[x] Medium (2–8h)

---

## 8. Summary of Changes

**Files created:**
- `src/main/java/.../util/BatchStatus.java`
- `src/main/java/.../config/AsyncConfig.java`
- `src/main/java/.../repository/UrlFileBatchRepository.java`
- `src/main/java/.../repository/UrlFileBatchRecordRepository.java`
- `src/main/java/.../service/BulkUrlService.java`
- `src/main/java/.../service/impl/BulkUrlServiceImpl.java`
- `src/main/java/.../service/impl/BatchProcessingService.java`
- `src/main/java/.../controller/BulkController.java`
- `src/main/java/.../dto/response/BulkUploadResponse.java`
- `src/main/java/.../dto/response/BatchStatusResponse.java`
- `src/main/resources/db/changelog/V2__create_batch_tables.sql`

**Files modified:**
- `src/main/java/.../util/RecordStatus.java` — values updated to `PENDING, SUCCESS, FAILED`
- `src/main/java/.../model/batch/UrlFileBatches.java` — UUID id, custom BatchStatus, new fields
- `src/main/java/.../model/batch/UrlFileBatchRecords.java` — `processedAt` added, UUID generation fixed
- `src/main/resources/db/changelog/db-changelog.xml` — V2 changeSet added
- `pom.xml` — `commons-csv` dependency added
