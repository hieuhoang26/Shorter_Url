

## **Batch Module Code Analysis: Excel File Reading & Processing**

### **1. Data Flow (Execution Flow)**

The batch job follows the **Spring Batch ETL (Extract-Transform-Load)** pattern:

```
┌─────────────────────────────────────────────────────────────────┐
│                    BATCH JOB EXECUTION                           │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
          ┌───────────────────────────────────────┐
          │  PaymentNotificationBatchListener     │
          │  beforeStep()                         │
          │  ✓ Update batch status → STARTED      │
          └───────────────────────────────────────┘
                              │
                              ▼
          ┌───────────────────────────────────────┐
          │  File Reading Phase (Chunk Loop)      │
          │  Each iteration reads & processes N   │
          │  items (configurable chunk size)      │
          └───────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
   Step 1: READ         Step 2: PROCESS       Step 3: WRITE
   (ItemReader)         (ItemProcessor)       (ItemWriter)
        │                     │                     │
        └─────────────────────┼─────────────────────┘
                              │
                              ▼
          ┌───────────────────────────────────────┐
          │  PaymentNotificationBatchListener     │
          │  afterChunk()                         │
          │  ✓ Update write count in DB           │
          │  ✓ Track chunk commits                │
          └───────────────────────────────────────┘
                              │
                        [Repeat until EOF]
                              │
                              ▼
          ┌───────────────────────────────────────┐
          │  PaymentNotificationBatchListener     │
          │  afterStep()                          │
          │  ✓ Update batch status → COMPLETED    │
          │  ✓ Calculate final metrics            │
          └───────────────────────────────────────┘
```

#### **Detailed Step-by-Step Execution:**

**STEP 1: READ (PaymentNotificationItemReader → PoiReader)**
- Initialize Excel file resource from Object Storage
- Load Excel workbook using Apache POI
- Skip header row (first row of Excel)
- Iterate through remaining rows sequentially

**STEP 2: PROCESS (PaymentAutomatedProcessor)**
For each row read:
1. Map Excel row data to `PaymentNotificationRowDto` using `PaymentNotificationRowMapper`
2. Fetch batch context (batch ID from job parameters)
3. Retrieve IC Organization and Actor User from database
4. Call `TreatmentDisbursementService.processPaymentNotification()` to perform business logic
5. Capture result (success or error list)
6. Create `PaymentNotificationBatchRecords` entity with status + error message

**STEP 3: WRITE (PaymentNotificationItemWriter)**
- Accumulate processed records in a chunk (typically 100-1000 items)
- When chunk reaches threshold, save all records to database in one batch
- Associate records with batch and organization
- Execute single `save()` operation (chunk-based optimization)

---

### **2. Inheritance and Interface Hierarchy**

#### **Reader Class Hierarchy**

```
AbstractItemCountingItemStreamItemReader<T>
    ↑
    │ (extends)
    │
ObjectStorageReader<T>
    implements ResourceAwareItemReaderItemStream<T>
    implements InitializingBean
    │
    │ (extends)
    │
PoiReader<T> extends ObjectStorageReader<PaymentNotificationRowDto>
    │
    │ (instantiated by)
    │
PaymentNotificationItemReader extends PoiReader<PaymentNotificationRowDto>
```

#### **Mapper Interface Hierarchy**

```
BathModelMapper<T> (interface)
    │
    │ (implemented by)
    │
PaymentNotificationRowMapper
    └─implements BathModelMapper<PaymentNotificationRowDto>
```

#### **Processor Interface Hierarchy**

```
ItemProcessor<I, O> (Spring Batch interface)
    │
    │ (implemented by)
    │
PaymentAutomatedProcessor
    └─implements ItemProcessor<PaymentNotificationRowDto, PaymentNotificationBatchRecords>
```

#### **Writer Interface Hierarchy**

```
ItemWriter<T> (Spring Batch interface)
    │
    │ (implemented by)
    │
PaymentNotificationItemWriter
    └─implements ItemWriter<PaymentNotificationBatchRecords>
```

#### **Listener Interface Hierarchy**

```
StepExecutionListener (Spring Batch interface)
    │
    ├─ beforeStep(StepExecution)
    └─ afterStep(StepExecution)

ChunkListener (Spring Batch interface)
    │
    ├─ beforeChunk(ChunkContext)
    ├─ afterChunk(ChunkContext)
    └─ afterChunkError(ChunkContext)

PaymentNotificationBatchListener
    implements StepExecutionListener
    implements ChunkListener
```

---

### **3. Method Responsibilities**

#### **PaymentNotificationItemReader**
| Method | Responsibility |
|--------|-----------------|
| Constructor | Accepts job parameters (object storage path, batchId) and delegates to parent `PoiReader` |
| (Inherits from PoiReader) | - |

#### **PoiReader<T>** (Core Excel Reader)
| Method | Responsibility |
|--------|-----------------|
| `doOpen()` | **Initialize**: Download file from Object Storage, create POI Workbook, get first sheet, skip header row, create row iterator |
| `doRead()` | **Read single row**: Check if more rows exist, map current row to DTO using mapper, return DTO or null (EOF) |
| `doClose()` | **Cleanup**: Close Workbook and release rowIterator to free memory |
| `afterPropertiesSet()` | Validation hook (empty in this case) |

#### **ObjectStorageReader<T>** (Storage Integration)
| Method | Responsibility |
|--------|-----------------|
| Constructor | Download file bytes from Object Storage Service, wrap in ByteArrayResource for streaming |
| `setResource(Resource)` | Store Resource reference for later access by PoiReader |

#### **PaymentNotificationRowMapper** (Row-to-DTO Mapping)
| Method | Responsibility |
|--------|-----------------|
| `toDto(Row)` | **Convert Excel row to DTO**: Use ExcelUtils to deserialize Excel row cells to `PaymentNotificationRowDto` fields (uses `@TableColumn` annotations), set 1-based row index |
| `toRow(dto)` | Not implemented (reverse mapping not needed) |
| `toDto(ResultSet)` | Not implemented (JDBC path not used) |

#### **PaymentAutomatedProcessor** (Business Logic)
| Method | Responsibility |
|--------|-----------------|
| `process(PaymentNotificationRowDto)` | **Core processing**: (1) Fetch batch entity by batchId, (2) Fetch IC Organization and User from repos, (3) Call disbursement service, (4) Create/populate PaymentNotificationBatchRecords entity, (5) Set status (SUCCESS/ERROR), (6) Populate error messages if failures occur, (7) Return enriched batch record entity |

#### **PaymentNotificationItemWriter** (Persistence)
| Method | Responsibility |
|--------|-----------------|
| `write(Chunk<PaymentNotificationBatchRecords>)` | **Batch save**: For each processed record in chunk, associate batch & organization references, add to batch entity's records collection, persist entire batch with all records in single DB transaction |

#### **PaymentNotificationBatchListener** (Observability & State Management)

| Method | Responsibility |
|--------|-----------------|
| `beforeStep(StepExecution)` | **Pre-execution**: Extract batchId from job parameters, update batch status to STARTED in DB |
| `afterStep(StepExecution)` | **Post-execution**: Query error count from DB, update batch status to COMPLETED, record total read/write counts, log execution metrics |
| `afterChunk(ChunkContext)` | **Progress tracking**: After every chunk commit, update cumulative write count in batch record (enables real-time progress monitoring) |
| `getBatchId()` | **Utility**: Extract batchId string from StepExecution job parameters |

---

### **Key Design Patterns**

1. **Template Method Pattern**: `ObjectStorageReader` (abstract) + `PoiReader` (concrete) define skeleton, subclass fills specifics
2. **Strategy Pattern**: `BathModelMapper` interface allows swappable mapping implementations
3. **Dependency Injection**: All dependencies autowired; Job parameters injected via `@Value("#{jobParameters[...]}")`
4. **Listener Pattern**: Batch listeners inserted at lifecycle hooks (before/after step, after chunk)
5. **Chunk-Oriented Processing**: Records accumulated and persisted in batches for performance
6. **Error Handling**: Per-record success/failure captured; batch continues on individual record errors

This architecture ensures **fault tolerance**, **monitoring**, and **scalability** for large Excel file imports.