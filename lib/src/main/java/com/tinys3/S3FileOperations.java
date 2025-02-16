package com.tinys3;

import com.sun.net.httpserver.HttpExchange;
import com.tinys3.response.BucketListResult;
import com.tinys3.response.CompleteMultipartUploadResult;
import com.tinys3.response.InitiateMultipartUploadResult;
import com.tinys3.response.ListAllBucketsResult;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

public interface S3FileOperations {
  InitiateMultipartUploadResult getInitiateMultipartUploadResult(String bucketName, String key);

  ListAllBucketsResult getListAllBucketsResult() throws IOException;

  boolean containsKey(String key);

  String handleUploadPart(String uploadId, Map<String, String> queryParams, byte[] payload)
      throws IOException;

  CompleteMultipartUploadResult getCompleteMultipartUploadResult(
      String bucketName, String key, String uploadId) throws IOException;

  void handleAbortMultipartUpload(String uploadId) throws IOException;

  boolean bucketExists(String bucketName);

  void createDirectory(Path bucketPath) throws IOException;

  BucketListResult getBucketListResult(HttpExchange exchange, String bucketName, Path bucketPath)
      throws IOException;

  boolean objectExists(Path objectPath);

  String handlePutObject(Path objectPath, byte[] payload) throws IOException;

  void handleDeleteObject(Path objectPath) throws IOException;

  boolean bucketHasFiles(Path bucketPath) throws IOException;

  void getObject(HttpExchange exchange, String bucketName, String key) throws IOException;

  long getSize(String objectName, String key) throws IOException;

  FileTime getLastModifiedTime(String objectName, String key) throws IOException;
}
