package com.tinys3;

import com.tinys3.http.S3HttpExchange;
import com.tinys3.response.BucketListResult;
import com.tinys3.response.CompleteMultipartUploadResult;
import com.tinys3.response.InitiateMultipartUploadResult;
import com.tinys3.response.ListAllBucketsResult;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

public interface S3FileOperations {
  InitiateMultipartUploadResult getInitiateMultipartUploadResult(String bucketName, String key);

  ListAllBucketsResult getListAllBucketsResult(String accessKey) throws IOException;

  boolean containsKey(String key);

  String handleUploadPart(String uploadId, Map<String, String> queryParams, byte[] payload)
      throws IOException;

  CompleteMultipartUploadResult getCompleteMultipartUploadResult(
      String bucketName, String key, String uploadId) throws IOException;

  void handleAbortMultipartUpload(String uploadId) throws IOException;

  boolean bucketExists(String bucketName);

  void createDirectory(String bucketName) throws IOException;

  BucketListResult getBucketListResult(S3HttpExchange exchange, String bucketName)
      throws IOException;

  boolean objectExists(String bucketName, String key);

  String handlePutObject(String bucketName, String key, byte[] payload) throws IOException;

  void handleDeleteObject(String bucketName, String key) throws IOException;

  boolean bucketHasFiles(String bucketName) throws IOException;

  void getObject(S3HttpExchange exchange, String bucketName, String key) throws IOException;

  long getSize(String bucketName, String key) throws IOException;

  FileTime getLastModifiedTime(String bucketName, String key) throws IOException;

  String calculateETag(String bucketName, String key, boolean isMultipart, List<String> partETags);

  Path getObjectPath(String bucketName, String key);

  void copyObject(String sourceBucketName, String sourceKey, String destBucketName, String destKey);

  Object relativize(Path path);
}
