
### 1. Refined AI Prompt
> "Act as a Senior Backend Developer. Implement a class named `ObjectStorageService` using **Java** and **AWS SDK v3**.
>
> The service must include the following methods:
> 1. **uploadObject**: Upload a file (Buffer/Stream) to a specific S3 bucket.
> 2. **downloadObject**: Retrieve an object from S3.
> 3. **generatePresignedUrl**: Create a temporary URL for a client to upload/download directly (specify `PutObject` or `GetObject`).
> 4. **verifyObject**: Check if an object exists in the bucket using `HeadObject`.
