package com.tinys3;

import com.tinys3.http.S3HttpExchange;
import com.tinys3.io.StorageException;
import com.tinys3.response.BucketListResult;
import com.tinys3.response.CompleteMultipartUploadResult;
import com.tinys3.response.InitiateMultipartUploadResult;
import com.tinys3.response.ListAllBucketsResult;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

public interface S3FileOperations {
  InitiateMultipartUploadResult getInitiateMultipartUploadResult(String bucketName, String key);

  ListAllBucketsResult getListAllBucketsResult(String accessKey) throws StorageException;

  boolean containsKey(String key);

  String handleUploadPart(String uploadId, Map<String, String> queryParams, byte[] payload)
      throws StorageException;

  CompleteMultipartUploadResult getCompleteMultipartUploadResult(
      String bucketName, String key, String uploadId) throws StorageException;

  void handleAbortMultipartUpload(String uploadId) throws StorageException;

  boolean bucketExists(String bucketName);

  void createDirectory(String bucketName) throws StorageException;

  BucketListResult getBucketListResult(S3HttpExchange exchange, String bucketName)
      throws StorageException;

  boolean objectNotExists(String bucketName, String key);

  String handlePutObject(String bucketName, String key, byte[] payload) throws StorageException;

  void handleDeleteObject(String bucketName, String key) throws StorageException;

  boolean bucketHasFiles(String bucketName) throws StorageException;

  void getObject(S3HttpExchange exchange, String bucketName, String key) throws StorageException;

  long getSize(String bucketName, String key) throws StorageException;

  FileTime getLastModifiedTime(String bucketName, String key) throws StorageException;

  String calculateETag(String bucketName, String key, boolean isMultipart, List<String> partETags);

  void copyObject(String sourceBucketName, String sourceKey, String destBucketName, String destKey)
      throws StorageException;
}
