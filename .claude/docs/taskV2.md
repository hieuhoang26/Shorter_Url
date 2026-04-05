Dưới đây là **plain plan** để implement bulk import URL theo **cùng pattern với `InsuredInventoryExcelItemReader`** (Spring Batch + Object Storage + POI reader).

Giữ đúng triết lý:

> Reader chỉ đọc file → mapping DTO
> Processor xử lý business → generate short code
> Writer lưu DB → update batch tables

---

# 1. Tổng quan kiến trúc

Sẽ có 3 layer giống flow bạn mô tả:

### Layer 1 — Storage access

download file từ object storage

```text
ObjectStorageService
        ↓
ObjectStorageReader
```

---

### Layer 2 — Excel parsing generic

đọc Excel bằng Apache POI

```text
PoiReader<T>
```

generic reusable reader

---

### Layer 3 — URL-specific implementation

map row → DTO

```text
UrlFileRowMapper
UrlExcelItemReader
```

---

# 2. End-to-end flow

```text
Spring Batch Job start
        ↓
StepScope Reader init
        ↓
download file từ object storage
        ↓
load Excel bằng Apache POI
        ↓
skip header row
        ↓
đọc từng dòng Excel
        ↓
map row → UrlRowDTO
        ↓
Processor:
    validate url
    generate short code
        ↓
Writer:
    insert url
    update UrlFileBatchRecord
        ↓
Step finish
        ↓
update UrlFileBatch status
```

---

# 3. Component design

## 3.1 DTO

đại diện 1 row trong file

```java
UrlRowDTO

rowNumber
originalUrl
expiredAt (optional)
customAlias (optional)
```

---

## 3.2 Row Mapper

map từ Excel row → DTO

```text
UrlRowMapper implements PoiRowMapper<UrlRowDTO>
```

logic:

read cell:

column 0 → original_url
column 1 → expired_at (optional)

---

## 3.3 Excel Reader

```text
UrlExcelItemReader
    extends PoiReader<UrlRowDTO>
```

responsibility:

* nhận file path từ job parameter
* download file từ object storage
* load workbook
* iterate rows

job parameter:

```text
objectStoragePath
batchId
```

---

## 3.4 Processor

```text
UrlBatchItemProcessor
```

input:

```text
UrlRowDTO
```

process:

1. validate url format
2. generate short code
3. attach batchId
4. return entity

output:

```text
UrlFileBatchRecord
```

status:

```text
SUCCESS
FAILED
```

---

## 3.5 Writer

```text
UrlBatchItemWriter
```

responsibility:

save:

### table url

store mapping:

```text
short_code
original_url
```

---

### table url_file_batch_record

update:

```text
status
short_code
error_message
processed_at
```

---

# 4. Step configuration

Spring Batch step:

```text
chunk size = 100
```

flow:

```text
Reader
   ↓
Processor
   ↓
Writer
```

---

# 5. Initialization logic (StepScope)

Reader khởi tạo:

```text
@StepScope
```

parameters:

```text
jobParameters["objectStoragePath"]
jobParameters["batchId"]
```

Reader constructor:

1. nhận file path
2. gọi ObjectStorageService
3. download file bytes
4. wrap ByteArrayResource

---

# 6. Detailed flow giống InsuredInventoryExcelItemReader

## Step 1 — Job start

API tạo batch:

```text
UrlFileBatch
status = PENDING
```

upload file → object storage

save:

```text
filePath
```

start job:

```text
JobParameters:

objectStoragePath
batchId
```

---

## Step 2 — Reader init

UrlExcelItemReader:

```text
download file từ object storage
```

file load vào memory:

```text
byte[]
```

wrap:

```text
ByteArrayResource
```

---

## Step 3 — open workbook

PoiReader.doOpen():

```text
WorkbookFactory.create()
```

select:

```text
sheet 0
```

skip:

```text
header row
```

---

## Step 4 — read rows

PoiReader.doRead():

loop từng row:

```text
Row → UrlRowMapper → UrlRowDTO
```

---

## Step 5 — processing

Processor xử lý:

```text
validate url
generate short code
set status
```

---

## Step 6 — writing

Writer lưu:

### insert url

```text
url table
```

---

### insert/update batch record

```text
UrlFileBatchRecord
```

---

## Step 7 — finish job

update batch:

```text
UrlFileBatch

status = COMPLETED
successRecords
failedRecords
completedAt
```

---

# 7. Package structure

```text
batch
 ├── reader
 │     ├── UrlExcelItemReader
 │     ├── UrlRowMapper
 │
 ├── processor
 │     ├── UrlBatchItemProcessor
 │
 ├── writer
 │     ├── UrlBatchItemWriter
 │
 ├── dto
 │     ├── UrlRowDTO
 │
 ├── config
 │     ├── UrlBatchJobConfig
```

---

# 8. Job parameters

required:

```text
objectStoragePath
batchId
```

optional:

```text
createdBy
```

---

# 9. Processing example

Excel:

```text
original_url
https://a.com
https://b.com
https://c.com
```

result:

```text
a.com → AbC1
b.com → AbC2
c.com → AbC3
```

---

# 10. Implementation order

step 1

DTO + RowMapper

---

step 2

UrlExcelItemReader

---

step 3

Processor logic

---

step 4

Writer logic

---

step 5

Job config

---

step 6

API trigger job

---
