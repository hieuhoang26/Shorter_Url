# Plan: Batch Module Refactor — Performance & Maintainability

**Date**: 2026-04-06  
**Status**: DONE  
**Completed**: 2026-04-06  
**Author**: Claude Code  

---

## 1. Requirement Summary

Refactor the Spring Batch bulk-URL import module (`com.hhh.url.shorter_url.batch`) to improve
performance and maintainability. The current implementation has three core problems: (1) the writer
issues `3 × N` individual DB calls per chunk instead of batch inserts, (2) carrier data for the
Processor → Writer handoff is stored as `@Transient` fields on the JPA entity rather than a proper
intermediate DTO, and (3) the job listener does not record `startedAt` and offers no real-time
progress visibility. The refactoring aligns the module with the patterns documented in
`.claude/brainstorm/template.md`.

---

## 2. Scope

### In Scope
- Replace individual `save()` calls in `UrlBatchItemWriter` with batch `saveAll()` operations
- Introduce `ProcessedUrlRow` intermediate DTO to decouple processor output from the JPA entity
- Generalize `PoiReader` and `UrlExcelItemReader` to download the file once (not per `open()`)
- Expand `UrlBatchJobListener` with `beforeJob` (sets `startedAt`) and optional chunk-level progress
- Add Spring Batch `faultTolerant` + `skip` policy on the step to replace the writer's per-record try-catch
- Externalize `CHUNK_SIZE` to `application.yaml`
- Add a `StepExecutionListener` to track `startedAt` on the batch record

### Out of Scope
- Streaming POI (SXSSFWorkbook) for reading — the current `WorkbookFactory` is fine unless file sizes exceed ~50 k rows
- Custom `SkipPolicy` logic beyond simple limit — keep it straightforward
- Changing the Excel column layout or `UrlRowDTO`
- Adding new API endpoints
- Migrating from JPA to JDBC batch template

---

## 3. Technical Design

### Components to Create

| Component | Type | Location | Responsibility |
|-----------|------|----------|----------------|
| `ProcessedUrlRow` | DTO (record) | `batch/dto/` | Carries the validated + mapped data from Processor to Writer; replaces `@Transient` fields on the entity |

### Components to Modify

| Component | Location | Change Description |
|-----------|----------|--------------------|
| `UrlBatchItemProcessor` | `batch/processor/` | Return `ProcessedUrlRow` instead of `UrlFileBatchRecords`; keep validation logic intact |
| `UrlBatchItemWriter` | `batch/writer/` | Accept `Chunk<ProcessedUrlRow>`; use `saveAll()` for batch inserts; separate valid vs failed paths |
| `UrlFileBatchRecords` | `model/batch/` | Remove `@Transient` carrier fields (`customAlias`, `expiredAt`, `description`, `tags`) |
| `UrlBatchJobListener` | `batch/listener/` | Add `beforeJob` to set `startedAt`; add `StepExecutionListener` for chunk-count progress |
| `UrlBatchJobConfig` | `batch/config/` | Wire `ProcessedUrlRow` generic type; add `faultTolerant().skip(Exception.class).skipLimit(N)`; read chunk size from `@Value` |
| `UrlExcelItemReader` | `batch/reader/` | Cache downloaded bytes in a field so `getResource()` is idempotent across restart calls |
| `application.yaml` | resources/ | Add `app.batch.chunk-size` and `app.batch.skip-limit` properties |

### Data Model Changes

No schema changes. The `@Transient` fields being removed from `UrlFileBatchRecords` are never
persisted; removing them is a pure Java change with no Liquibase migration required.

### New DTO: `ProcessedUrlRow`

```java
// batch/dto/ProcessedUrlRow.java
public record ProcessedUrlRow(
    int rowNumber,
    String originalUrl,
    String customAlias,          // null → generate Base62
    LocalDateTime expiredAt,     // null → now + 5 days
    String description,
    String tags,
    RecordStatus status,         // PENDING or FAILED
    String errorMessage,         // non-null only when FAILED
    UrlFileBatches batchRef      // JPA proxy reference
) {}
```

### Revised Writer Logic (batch inserts)

```
write(Chunk<ProcessedUrlRow>):
  partition chunk into failedRows + pendingRows

  // 1 batch op — persist all failed records immediately
  List<UrlFileBatchRecords> failedEntities = failedRows.map(toRecord(FAILED))
  recordRepository.saveAll(failedEntities)

  // 2 batch ops — insert URLs, then update short codes
  List<Url> urls = pendingRows.map(toUrl)
  urlRepository.saveAll(urls)            // flush assigns IDs
  urls.forEach(url → url.setShortCode(base62 or customAlias))
  urlRepository.saveAll(urls)            // batch UPDATE

  // 1 batch op — persist success records
  List<UrlFileBatchRecords> successEntities = zip(pendingRows, urls).map(toRecord(SUCCESS))
  recordRepository.saveAll(successEntities)
```

DB calls per chunk: **4 batch ops** (constant) vs previous **4 × N individual ops** (linear).

### Fault Tolerance via Spring Batch Step Config

```java
.<ProcessedUrlRow, ProcessedUrlRow>chunk(chunkSize, transactionManager)
  .reader(...)
  .processor(...)
  .writer(...)
  .faultTolerant()
  .skip(Exception.class)
  .skipLimit(skipLimit)
  .build();
```

The writer's per-record `try-catch` is removed; Spring Batch will retry the chunk in single-item
mode on exception and call `afterSkip` callbacks if the skip limit is respected.

### Key Decisions

- **Decision**: Introduce `ProcessedUrlRow` record DTO instead of keeping `@Transient` fields  
  **Reason**: `@Transient` fields on a JPA entity used as cross-layer carrier data is an anti-pattern — it blurs entity vs DTO semantics and makes the entity state confusing  
  **Alternatives considered**: Keep `@Transient` fields (simpler but more coupling); use a `Map<String,Object>` extras bag (too untyped)

- **Decision**: Use `saveAll()` at the writer level rather than enabling JDBC batch via Hibernate properties  
  **Reason**: `saveAll()` is explicit, testable, and works with the existing JPA setup. Hibernate JDBC batch (`hibernate.jdbc.batch_size`) is complementary and can be added as a `application.yaml` tweak later without code changes  
  **Alternatives considered**: Switch to `JdbcTemplate` bulk insert (more perf, but breaks JPA audit and requires SQL maintenance)

- **Decision**: Cache downloaded bytes in `UrlExcelItemReader` field  
  **Reason**: On a job restart, `open()` is called again. Without caching, the file is re-downloaded from S3 every time, wasting bandwidth and adding latency  
  **Alternatives considered**: Cache at `ObjectStorageService` level (too broad a concern)

- **Decision**: Use Spring Batch `faultTolerant().skip()` instead of per-record try-catch in writer  
  **Reason**: The framework already supports skip semantics; duplicating it in the writer bypasses Spring Batch's skip count tracking, metrics, and `SkipListener` callbacks  
  **Alternatives considered**: Keep existing try-catch (simpler, but defeats batch framework intent)

---

## 4. Implementation Steps

- [x] Step 1: Create `ProcessedUrlRow` record DTO in `batch/dto/`
- [x] Step 2: Update `UrlBatchItemProcessor` to return `ProcessedUrlRow` (adjust generic type `ItemProcessor<UrlRowDTO, ProcessedUrlRow>`)
- [x] Step 3: Remove `@Transient` carrier fields from `UrlFileBatchRecords`
- [x] Step 4: Rewrite `UrlBatchItemWriter` to accept `Chunk<ProcessedUrlRow>` and use `saveAll()` with the 4-op batch pattern
- [x] Step 5: Remove per-record try-catch from `UrlBatchItemWriter` (fault tolerance moves to step config)
- [x] Step 6: Update `UrlBatchJobConfig` to wire new generic types, add `faultTolerant().skip()`, and read `chunk-size` / `skip-limit` from `@Value`
- [x] Step 7: Add `app.batch.chunk-size=100` and `app.batch.skip-limit=1000` to `application.yaml`
- [x] Step 8: Cache file bytes in `UrlExcelItemReader` (lazy-load on first `getResource()` call, store in field)
- [x] Step 9: Add `beforeJob` to `UrlBatchJobListener` to set `batch.startedAt = OffsetDateTime.now()` and save
- [x] Step 10: Add `StepExecutionListener` to `UrlBatchJobListener` (or a separate `UrlBatchStepListener`) to track in-progress chunk count in the batch record
- [x] Step 11: Register the step listener in `UrlBatchJobConfig` via `.listener(stepListener)`

---

## 5. Testing Strategy

- **Unit tests** (`UrlBatchItemWriterTest`):
  - Verify `saveAll` is called once for URLs (not N times) per chunk
  - Verify mixed PENDING + FAILED chunk produces correct success/failed split
  - Verify custom alias skips `base62Service` call

- **Unit tests** (`UrlBatchItemProcessorTest`):
  - Valid URL → `ProcessedUrlRow` with status PENDING
  - Invalid URL → `ProcessedUrlRow` with status FAILED and non-null `errorMessage`
  - Null/blank URL → FAILED

- **Unit tests** (`UrlRowMapperTest`):
  - Existing tests remain valid (no change to mapper)

- **Integration / slice test** (`UrlBatchJobListenerTest`):
  - `beforeJob`: `startedAt` is set
  - `afterJob`: correct `BatchStatus` resolution for all three outcomes (COMPLETED, PARTIAL_SUCCESS, FAILED)

- **Mocking strategy**: Mock repositories with Mockito; use `ArgumentCaptor` to verify `saveAll` receives the correct list size and statuses.

---

## 6. Risks & Open Questions

- **Risk**: Removing `@Transient` fields from `UrlFileBatchRecords` while the writer still needs the data → Mitigation: Steps 1–5 are a coordinated change; do not merge partial steps
- **Risk**: `saveAll` + `flush` may load all URLs into the persistence context at once for large chunks → Mitigation: chunk size of 100 is well within safe bounds; document the limit
- **Risk**: `faultTolerant().skip()` changes transaction semantics — on skip, chunk is re-executed item-by-item → Mitigation: This is expected Spring Batch behaviour; ensure the writer is idempotent (it is, since each item gets a new UUID)
- **Open question**: Should `app.batch.skip-limit` be set per-deployment or hard-coded? Recommend `application.yaml` default with env-var override option

---

## 7. Estimated Complexity

[x] Medium (2–8h)

---

## 8. Summary of Changes

**Files created:**
- `batch/dto/ProcessedUrlRow.java` — new record DTO for Processor → Writer handoff
- `src/test/…/batch/UrlBatchItemProcessorTest.java`
- `src/test/…/batch/UrlBatchItemWriterTest.java`
- `src/test/…/batch/UrlBatchJobListenerTest.java`

**Files modified:**
- `batch/processor/UrlBatchItemProcessor.java` — returns `ProcessedUrlRow` instead of entity
- `batch/writer/UrlBatchItemWriter.java` — `saveAll()` batch ops; no per-record try-catch
- `batch/listener/UrlBatchJobListener.java` — added `beforeJob` (startedAt) + `StepExecutionListener`
- `batch/config/UrlBatchJobConfig.java` — new generic types, `faultTolerant().skip()`, step listener, `@Value` chunk/skip config
- `batch/reader/UrlExcelItemReader.java` — cached S3 download
- `model/batch/UrlFileBatchRecords.java` — removed `@Transient` carrier fields
- `src/main/resources/application.yaml` — added `app.batch.chunk-size` and `app.batch.skip-limit`
