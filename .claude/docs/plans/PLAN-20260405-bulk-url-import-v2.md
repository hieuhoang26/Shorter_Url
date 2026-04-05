# Plan: Bulk URL Import — Spring Batch + Apache POI (v2)

**Date**: 2026-04-05
**Status**: DONE
**Completed**: 2026-04-05
**Author**: Claude Code

---

## Revision History

| Version | Date       | Changed By  | Summary                                                               |
|---------|------------|-------------|-----------------------------------------------------------------------|
| v1.0    | 2026-04-05 | Claude Code | Initial plan — @Async while-loop + Commons CSV                        |
| v2.0    | 2026-04-05 | Claude Code | Replace with Spring Batch Job (Reader/Processor/Writer) + Apache POI |

> Previous plan marked **SUPERSEDED**: `PLAN-20260405-bulk-url-import.md`

---

## 1. Requirement Summary

Same business goal as v1: accept an XLSX file upload, generate a short code for each URL, persist them to the `urls` table, and track progress via batch tables. The architectural change is to replace the custom `@Async` while-loop processor and Commons CSV with a proper **Spring Batch Job** (Reader → Processor → Writer pipeline) using **Apache POI** for XLSX parsing. The file is downloaded from S3 inside the Reader at step scope, ensuring the Spring Batch restart/retry model is respected.

---

## 2. Scope

### In Scope
- Replace `BatchProcessingService` with Spring Batch `Job` + `Step` (chunk size 100)
- XLSX parsing via Apache POI (`poi-ooxml`) — XLSX now supported (was out of scope in v1)
- `PoiRowMapper<T>` interface + `PoiReader<T>` abstract class — reusable for future Excel imports
- `UrlRowMapper` — maps Excel `Row` → `UrlRowDTO`
- `UrlExcelItemReader` (`@StepScope`) — downloads file from S3, exposes rows to Spring Batch
- `UrlBatchItemProcessor` — validates URL format, builds `UrlFileBatchRecords` entity
- `UrlBatchItemWriter` — saves `Url` entity, generates short code, saves `UrlFileBatchRecords`
- `UrlBatchJobListener` — updates `UrlFileBatch` status/counters after job finishes
- `UrlBatchJobConfig` — `Job` + `Step` bean definitions
- Async job launch via `TaskExecutorJobLauncher` backed by existing `batchExecutor` pool
- `BulkUrlServiceImpl.createBatch()` reworked: no CSV pre-parse; launches Job with `JobParameters`
- Spring Batch schema auto-initialized via `spring.batch.jdbc.initialize-schema=always`

### Out of Scope
- Pre-inserting PENDING records before processing (v1 pattern — abandoned)
- CSV support (XLSX is the canonical format; CSV was a temporary workaround)
- Retry / skip logic at Spring Batch level (no `SkipPolicy` or `RetryPolicy`)
- Authentication / user-scoped batches
- Download results as CSV
- WebSocket / SSE progress streaming

---

## 3. Technical Design

### 3.1 Package Structure

All new Spring Batch components live under:
```
com.hhh.url.shorter_url.batch
├── dto/
│   └── UrlRowDTO
├── reader/
│   ├── PoiRowMapper<T>        (interface)
│   ├── PoiReader<T>           (abstract ItemStreamReader)
│   ├── UrlRowMapper
│   └── UrlExcelItemReader
├── processor/
│   └── UrlBatchItemProcessor
├── writer/
│   └── UrlBatchItemWriter
├── listener/
│   └── UrlBatchJobListener
└── config/
    └── UrlBatchJobConfig
```

### 3.2 Components to Create

| Component | Type | Location | Responsibility |
|-----------|------|----------|----------------|
| `UrlRowDTO` | DTO | `batch/dto` | Represents one parsed Excel row (`rowNumber`, `originalUrl`) |
| `PoiRowMapper<T>` | Interface | `batch/reader` | Generic contract: `T mapRow(Row row, int rowNumber)` |
| `PoiReader<T>` | Abstract class | `batch/reader` | `ItemStreamReader<T>` — opens workbook, iterates rows, delegates to mapper |
| `UrlRowMapper` | Class | `batch/reader` | `PoiRowMapper<UrlRowDTO>` — col 0 → originalUrl |
| `UrlExcelItemReader` | `@StepScope` bean | `batch/reader` | Extends `PoiReader<UrlRowDTO>`, downloads file from S3 using job params |
| `UrlBatchItemProcessor` | `@StepScope` bean | `batch/processor` | `ItemProcessor<UrlRowDTO, UrlFileBatchRecords>` — validates URL, sets FAILED with error or returns PENDING record |
| `UrlBatchItemWriter` | `@StepScope` bean | `batch/writer` | `ItemWriter<UrlFileBatchRecords>` — saves `Url`, generates short code, saves record |
| `UrlBatchJobListener` | `@Component` | `batch/listener` | `JobExecutionListener` — after job: count SUCCESS/FAILED, update `UrlFileBatch` |
| `UrlBatchJobConfig` | `@Configuration` | `batch/config` | Declares `Job urlImportJob`, `Step urlImportStep` (chunk=100), wires components |

### 3.3 Components to Modify

| Component | Location | Change |
|-----------|----------|--------|
| `pom.xml` | root | REMOVE `commons-csv`; ADD `poi-ooxml` |
| `application.yaml` | `src/main/resources` | ADD `spring.batch.jdbc.initialize-schema: always` |
| `BulkUrlServiceImpl` | `service/impl` | REWORK `createBatch()` — no CSV parse, no `@Async` trigger; inject `JobLauncher` + `Job`, launch with `JobParameters` |
| `UrlFileBatchRecordRepository` | `repository` | REMOVE `findTop100ByBatch_IdAndStatusOrderByRowNumber` (Spring Batch handles chunking); KEEP `countByBatch_IdAndStatus` |
| `AsyncConfig` | `config` | ADD `TaskExecutorJobLauncher` bean configured with `batchExecutor` |

### 3.4 Components to Delete

| Component | Reason |
|-----------|--------|
| `BatchProcessingService` | Fully replaced by Spring Batch Job pipeline |

### 3.5 Data Flow

```
POST /api/v1/bulk
  │
  ▼
BulkUrlServiceImpl.createBatch(file)
  │  upload file → S3
  │  save UrlFileBatch (PENDING)
  │  jobLauncher.run(urlImportJob, params{objectStoragePath, batchId, timestamp})
  │
  ▼  [async — batchExecutor thread pool]
Spring Batch: urlImportStep  chunk=100
  │
  ├─ READER: UrlExcelItemReader
  │    @StepScope — receives objectStoragePath from jobParameters
  │    downloads byte[] from ObjectStorageService
  │    creates ByteArrayResource → WorkbookFactory.create()
  │    sheet 0, skip row 0 (header)
  │    for each row → UrlRowMapper → UrlRowDTO(rowNumber, originalUrl)
  │
  ├─ PROCESSOR: UrlBatchItemProcessor
  │    @StepScope — receives batchId from jobParameters
  │    validates originalUrl (URI.create check)
  │    if INVALID → returns UrlFileBatchRecords(FAILED, errorMessage)
  │    if VALID   → returns UrlFileBatchRecords(PENDING, originalUrl)
  │    note: shortCode and Url insert happen in Writer (requires DB-assigned id)
  │
  ├─ WRITER: UrlBatchItemWriter
  │    for each item:
  │      if FAILED → save UrlFileBatchRecords as-is
  │      if PENDING →
  │        new Url(originalUrl, domain, expiredAt) → urlRepository.save()
  │        shortCode = base62Service.generateShortCode(url.getId())
  │        url.setShortCode(shortCode) → urlRepository.save()
  │        record.setShortCode(shortCode), record.setStatus(SUCCESS)
  │        record.setProcessedAt(now) → recordRepository.save()
  │
  └─ LISTENER: UrlBatchJobListener (afterJob)
       load UrlFileBatch by batchId (from jobParameters)
       successCount = countByBatch_IdAndStatus(batchId, SUCCESS)
       failedCount  = countByBatch_IdAndStatus(batchId, FAILED)
       batch.totalRecords     = stepExecution.readCount
       batch.processedRecords = successCount + failedCount
       batch.successRecords   = successCount
       batch.failedRecords    = failedCount
       batch.completedAt      = now
       batch.status           = failedCount > 0 ? PARTIAL_SUCCESS : COMPLETED
       batchRepository.save(batch)
```

### 3.6 API Contract (unchanged from v1)

```
POST /api/v1/bulk
Content-Type: multipart/form-data
Body: file (MultipartFile — XLSX)

Response 202:
{ "data": { "batchId": "uuid" }, "message": "Batch created successfully" }

---

GET /api/v1/bulk/{batchId}

Response 200:
{ "data": { "status": "PROCESSING", "progress": 45, "success": 450, "failed": 12 } }
```

### 3.7 Key Decisions

- **Decision**: Processor outputs `UrlFileBatchRecords` with status PENDING (valid URL) or FAILED (invalid).  
  **Reason**: Short code generation requires `Url.id` from DB — impossible before a DB save. Writer owns the DB interaction, so short code generation belongs there.  
  **Alternatives considered**: Processor saves `Url` entity — violates SRP and Spring Batch phase separation.

- **Decision**: `UrlFileBatchRecords` are NOT pre-inserted as PENDING before the Job runs.  
  **Reason**: The v1 pre-insert approach added unnecessary write overhead. Spring Batch's `readCount` provides `totalRecords` after the step, set by the listener.  
  **Alternatives considered**: Keep pre-insert — creates a 2-phase write with no benefit when using Spring Batch.

- **Decision**: Use `TaskExecutorJobLauncher` (async) not synchronous `JobLauncher`.  
  **Reason**: API must return `batchId` immediately; job runs in background on `batchExecutor` pool.  
  **Alternatives considered**: `@Async` wrapper around synchronous launcher — more fragile than a proper async launcher.

- **Decision**: Spring Batch job schema initialized with `initialize-schema: always`.  
  **Reason**: Simplest path for non-embedded DB. Alternative is Liquibase migration for batch schema — over-engineered for this stage.  
  **Alternatives considered**: Manual Liquibase migration for batch tables — deferred.

- **Decision**: `PoiReader<T>` is abstract with `getResource()` and `getRowMapper()` as abstract methods.  
  **Reason**: Makes the generic reader reusable for future Excel import types without changing the iteration/lifecycle logic.  
  **Alternatives considered**: Single concrete reader — not reusable.

---

## 4. Implementation Steps

- [x] **Step 1**: REMOVE `commons-csv` from `pom.xml`; ADD `poi-ooxml` dependency
- [x] **Step 2**: ADD `spring.batch.jdbc.initialize-schema: always` to `application.yaml`
- [x] **Step 3**: ADD `TaskExecutorJobLauncher` bean to `AsyncConfig`, configured with `batchExecutor`
- [x] **Step 4**: Create `UrlRowDTO` in `batch/dto/` — fields: `int rowNumber`, `String originalUrl`
- [x] **Step 5**: Create `PoiRowMapper<T>` interface in `batch/reader/` — single method: `T mapRow(Row row, int rowNumber)`
- [x] **Step 6**: Create `PoiReader<T>` abstract class in `batch/reader/` implementing `ItemStreamReader<T>`:
  - `open()` — calls `getResource()`, `WorkbookFactory.create()`, selects sheet 0, skips header row
  - `read()` — returns next mapped row via `getRowMapper().mapRow(row, ++currentRow)`, or `null` at EOF
  - `close()` — closes workbook
  - abstract `Resource getResource()`
  - abstract `PoiRowMapper<T> getRowMapper()`
- [x] **Step 7**: Create `UrlRowMapper` in `batch/reader/` implementing `PoiRowMapper<UrlRowDTO>`:
  - col 0 → `originalUrl` (STRING cell, null-safe)
- [x] **Step 8**: Create `UrlExcelItemReader` in `batch/reader/` extending `PoiReader<UrlRowDTO>`, annotated `@StepScope`:
  - Constructor params: `ObjectStorageService`, `@Value("#{jobParameters['objectStoragePath']}")`, `@Value("#{jobParameters['batchId']}") `
  - `getResource()` → `objectStorageService.downloadObject(path)` → `new ByteArrayResource(bytes)`
  - `getRowMapper()` → `new UrlRowMapper()`
- [x] **Step 9**: Create `UrlBatchItemProcessor` in `batch/processor/` implementing `ItemProcessor<UrlRowDTO, UrlFileBatchRecords>`, annotated `@StepScope`:
  - Constructor param: `UrlFileBatchRepository`, `@Value("#{jobParameters['batchId']}")`
  - Lazy-load `UrlFileBatches` batchRef on first call and cache
  - `process()`: validate URL via `URI.create()` + scheme check (`http`/`https`)
  - INVALID → return record with `FAILED`, `errorMessage`, `processedAt`
  - VALID → return record with `PENDING`, `originalUrl`, `rowNumber`, `batch`
- [x] **Step 10**: Create `UrlBatchItemWriter` in `batch/writer/` implementing `ItemWriter<UrlFileBatchRecords>`, annotated `@StepScope`:
  - Inject `UrlRepository`, `UrlFileBatchRecordRepository`, `Base62Service`
  - `write(chunk)`: for each item, if FAILED → `recordRepository.save(item)`; if PENDING → save `Url`, generate short code, set shortCode + SUCCESS + processedAt on record, save both
- [x] **Step 11**: Create `UrlBatchJobListener` in `batch/listener/` implementing `JobExecutionListener`:
  - Inject `UrlFileBatchRepository`, `UrlFileBatchRecordRepository`
  - `afterJob()`: read `batchId` from `JobParameters`; count SUCCESS + FAILED; update `UrlFileBatch` status/counters/completedAt; set COMPLETED or PARTIAL_SUCCESS
- [x] **Step 12**: Create `UrlBatchJobConfig` in `batch/config/`:
  - Declare `Step urlImportStep` — chunk size 100, reader=`UrlExcelItemReader`, processor=`UrlBatchItemProcessor`, writer=`UrlBatchItemWriter`
  - Declare `Job urlImportJob` — single step, listener=`UrlBatchJobListener`
- [x] **Step 13**: REWORK `BulkUrlServiceImpl.createBatch()`:
  - Remove CSV parsing, remove `batchProcessingService` injection
  - Inject `TaskExecutorJobLauncher`, `Job urlImportJob`
  - After saving `UrlFileBatch`, build `JobParameters{objectStoragePath, batchId, timestamp}` and call `jobLauncher.run(urlImportJob, params)`
- [x] **Step 14**: REMOVE `findTop100ByBatch_IdAndStatusOrderByRowNumber` from `UrlFileBatchRecordRepository`
- [x] **Step 15**: DELETE `BatchProcessingService`

---

## 5. Risks & Open Questions

- **Risk**: `spring.batch.jdbc.initialize-schema: always` re-runs on every restart → **Mitigation**: Spring Batch checks if tables exist before creating; safe to leave as `always` in dev; switch to `never` + Liquibase migration for production.
- **Risk**: `TaskExecutorJobLauncher` + `JobRepository` requires `@EnableBatchProcessing` or Spring Boot auto-config to be active → **Mitigation**: Spring Boot 3.x auto-configures `JobRepository` when `spring-boot-starter-batch` is on classpath; `@EnableBatchProcessing` is NOT needed (and conflicts with auto-config in Boot 3.x).
- **Risk**: POI loads entire XLSX into memory → **Mitigation**: acceptable for expected file sizes; SXSSF streaming can be added later if memory becomes a concern.
- **Open question**: `JobParameters` must be unique per run (Spring Batch enforces this). Adding a `timestamp` parameter ensures uniqueness.
- **Open question**: What happens if the S3 download fails in the Reader? → Spring Batch will mark the step FAILED; `UrlBatchJobListener.afterJob()` should handle `FAILED` exit status and set `UrlFileBatch.status = FAILED`.

---

## 6. Estimated Complexity

[ ] Medium (2–8h)

---

## ⚠️ Already-Implemented Steps That Need Rework

These steps from `PLAN-20260405-bulk-url-import.md` (v1) were completed but must change:

| v1 Step | Component | Action Required |
|---------|-----------|-----------------|
| Step 7  | `pom.xml` — commons-csv | REMOVE commons-csv; ADD poi-ooxml |
| Step 9  | `UrlFileBatchRecordRepository` | REMOVE `findTop100ByBatch_IdAndStatusOrderByRowNumber` |
| Step 12 | `BulkUrlServiceImpl.createBatch()` | REWORK — replace CSV parse + @Async call with JobLauncher |
| Step 14 | `BatchProcessingService` | DELETE file entirely |
