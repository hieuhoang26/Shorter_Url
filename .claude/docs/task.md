

# 0. Scope của feature

Input:

```
CSV/ XLSS file chứa danh sách original_url
```

Output:

```
tạo short_code cho từng URL
lưu vào bảng url
update trạng thái từng record
update progress của batch
```

---

# 1. Overall flow

```text
Upload file
    ↓
create UrlFileBatch (PENDING)
    ↓
parse file → insert UrlFileBatchRecord
    ↓
trigger async job
    ↓
worker xử lý records theo chunk
    ↓
generate short_code
    ↓
insert table url
    ↓
update record status
    ↓
update batch progress
```

---

# 2. Task breakdown for AI

## TASK 1 — create entities

AI implement JPA entities

### UrlFileBatch

fields:

```java
UUID id

String fileName
String filePath

BatchStatus status

int totalRecords
int processedRecords
int successRecords
int failedRecords

OffsetDateTime createdAt
OffsetDateTime startedAt
OffsetDateTime completedAt
```

---

### UrlFileBatchRecord

```java
UUID id

UUID batchId

int rowNumber

String originalUrl

String shortCode

RecordStatus status

String errorMessage

OffsetDateTime processedAt
```

---

## TASK 2 — create enums

### BatchStatus

```java
PENDING
PROCESSING
COMPLETED
FAILED
PARTIAL_SUCCESS
```

---

### RecordStatus

```java
PENDING
SUCCESS
FAILED
```

---

## TASK 3 — repository layer

AI create repository interfaces:

```java
UrlFileBatchRepository
UrlFileBatchRecordRepository
UrlRepository
```

important queries:

### find pending records

```java
List<UrlFileBatchRecord> findTop100ByBatchIdAndStatusOrderByRowNumber(...)
```

---

### count records by status

```java
countByBatchIdAndStatus(...)
```

---

## TASK 4 — upload file service

### method

```java
UUID createBatch(MultipartFile file)
```

logic:

### step 1

upload file → S3 / local storage

return:

```java
filePath
```

---

### step 2

create batch

```java
UrlFileBatch

status = PENDING
```

---

### step 3

parse CSV

for each row:

create record:

```java
UrlFileBatchRecord

status = PENDING
rowNumber = index
```

---

### step 4

update totalRecords

---

## TASK 5 — batch processing service

### method

```java
void processBatch(UUID batchId)
```

logic:

### mark batch PROCESSING

```java
startedAt = now
status = PROCESSING
```

---

### loop until no records left

```java
while(true)
```

fetch chunk:

```java
limit 100
status = PENDING
```

---

### process each record

for record:

```java
validate url
generate short code
insert url table
update record SUCCESS
```

error:

```java
update record FAILED
save error_message
```

---

### update batch counters

after each chunk:

```java
processedRecords += chunk size
successRecords count SUCCESS
failedRecords count FAILED
```

---

### finish batch

if failedRecords > 0:

```java
PARTIAL_SUCCESS
```

else:

```java
COMPLETED
```

---

## TASK 6 — short code generation integration

reuse existing logic:

```java
id generator
→ base62 encode
```

AI chỉ cần gọi:

```java
shortCodeService.generate()
```

---

## TASK 7 — url insert logic

table:

```java
url
```

fields:

```java
id
short_code
original_url
created_at
```

unique constraint:

```sql
unique(short_code)
```


## TASK 9 — API endpoints

### upload file

```
POST /api/v1/bulk
```

response:

```json
{
  "batchId": "uuid"
}
```

---

### check status

```
GET /api/v1/bulk/{batchId}
```

response:

```json
{
  "status": "PROCESSING",
  "progress": 45,
  "success": 450,
  "failed": 12
}
```

---

# 3. Execution order for AI

recommend gửi AI theo thứ tự:

### phase 1

entities

repositories

enums

---

### phase 2

upload file service

parse CSV

insert records

---

### phase 3

batch processor service

chunk processing

update counters

---

### phase 4

integrate short code service

insert url table

---

### phase 5

async execution

API controller

---

