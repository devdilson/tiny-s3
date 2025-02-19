# TinyS3

A lightweight no-dependencies S3-compatible server implementation in Java.

## Features

- S3 API compatibility (core operations)
- AWS v4 signature authentication
- Presigned URL support
- Multi-user credentials
- MinIO client compatibility
- In-memory file system

## Supported Operations

### Bucket Operations
- List all buckets (GET /)
- Create bucket (PUT /bucket)
- Delete bucket (DELETE /bucket)
- List objects in bucket (GET /bucket)
- Head bucket (HEAD /bucket)

### Object Operations
- Put object (PUT /bucket/key)
- Get object (GET /bucket/key)
- Delete object (DELETE /bucket/key)
- Copy object (PUT /bucket/key with x-amz-copy-source header)
- Head object (HEAD /bucket/key)

### Multipart Upload
- Initiate multipart upload (POST /bucket/key?uploads)
- Upload part (PUT /bucket/key?uploadId=xxx&partNumber=n)
- Complete multipart upload (POST /bucket/key?uploadId=xxx)
- Abort multipart upload (DELETE /bucket/key?uploadId=xxx)

### Presigned URLs
- Generate presigned URL (POST /?presigned-url)

## Quick Start

### Basic Server Setup

```java
// Create credentials
Credentials cred = new Credentials("access-key", "secret-key", "us-east-1");

// Build and start server
S3Server server = new S3Server.Builder()
        .withPort(8000)
        .withCredentials(cred)
        .withStorageDir("storage")  // Local directory for storage
        .build();

server.start();
```

### Maven Central

```xml
<dependency>
    <groupId>dev.totis</groupId>
    <artifactId>tiny-s3-lib</artifactId>
    <version>1.1.0</version>
</dependency>
```

## Testing

Run integration tests:
```bash
./gradlew test
```

## Publishing

```bash
./gradlew publishToMavenCentral --no-configuration-cache 
```

Building the docker image
```
./gradlew jibDockerBuild --no-configuration-cache
```

## Unimplemented Features

### Bucket Operations
- Bucket versioning
- Bucket lifecycle configuration
- Bucket replication
- Bucket policies and ACLs
- Bucket CORS configuration
- Bucket encryption configuration

### Object Operations
- Object versioning
- Object ACLs and permissions
- Object tagging
- Object retention and legal holds
- Server-side encryption (SSE)
- Object locks
- Batch operations
- Range-based object retrieval

## License

GNU Affero General Public License version 3 (AGPL v3)
TinyS3 is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
You should have received a copy of the GNU Affero General Public License along with this program. If not, see https://www.gnu.org/licenses/agpl-3.0.html.



