package com.tinys3;

import com.google.common.collect.Multimap;
import io.minio.CreateMultipartUploadResponse;
import io.minio.MinioAsyncClient;
import io.minio.messages.Part;
import java.util.concurrent.CompletableFuture;

public class CustomMinioClient extends MinioAsyncClient {

  public CustomMinioClient(MinioAsyncClient client) {
    super(client);
  }

  public String initMultiPartUpload(
      String bucket,
      String region,
      String object,
      Multimap<String, String> headers,
      Multimap<String, String> extraQueryParams)
      throws Exception {
    CompletableFuture<CreateMultipartUploadResponse> response =
        this.createMultipartUploadAsync(bucket, region, object, headers, extraQueryParams);

    return response.get().result().uploadId();
  }

  public String completeMultipartUpload(
      String bucket,
      String region,
      String object,
      Multimap<String, String> headers,
      Multimap<String, String> extraQueryParams)
      throws Exception {
    CompletableFuture<CreateMultipartUploadResponse> response =
        this.createMultipartUploadAsync(bucket, region, object, headers, extraQueryParams);
    return response.get().result().uploadId();
  }

  private Part[] uploadPartCopy(
      String bucketName,
      String region,
      String objectName,
      String uploadId,
      int partNumber,
      Multimap<String, String> headers,
      Part[] parts)
      throws Exception {
    return (Part[])
        this.uploadPartCopyAsync(
                bucketName, region, objectName, uploadId, partNumber, headers, (Multimap) null)
            .get();
  }
}
