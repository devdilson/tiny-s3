package com.tinys3;

import com.google.common.collect.Multimap;
import io.minio.CreateMultipartUploadResponse;
import io.minio.MinioAsyncClient;
import io.minio.ObjectWriteResponse;
import io.minio.messages.Part;
import java.util.List;
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

  public ObjectWriteResponse completeMultipartUpload(
      String bucket,
      String region,
      String object,
      String uploadId,
      List<Part> partList,
      Multimap<String, String> headers,
      Multimap<String, String> extraQueryParams) {
    try {
      return super.completeMultipartUploadAsync(
              bucket,
              region,
              object,
              uploadId,
              partList.toArray(new Part[0]),
              headers,
              extraQueryParams)
          .get();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
