package com.tinys3;

import com.sun.net.httpserver.HttpExchange;
import com.tinys3.response.BucketListResult;
import com.tinys3.response.CompleteMultipartUploadResult;
import com.tinys3.response.InitiateMultipartUploadResult;
import com.tinys3.response.ListAllBucketsResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.tinys3.S3Utils.calculateETag;
import static com.tinys3.S3Utils.parseQueryString;

public class DefaultS3FileOperations implements S3FileOperations {

  private final String storagePath;

  private final Map<String, List<PartInfo>> multipartUploads = new ConcurrentHashMap<>();

  public DefaultS3FileOperations(String storagePath) {
    this.storagePath = storagePath;
  }

  @Override
  public InitiateMultipartUploadResult getInitiateMultipartUploadResult(
      String bucketName, String key) {
    String uploadId = UUID.randomUUID().toString();
    multipartUploads.put(uploadId, new ArrayList<>());
    return new InitiateMultipartUploadResult(bucketName, key, uploadId);
  }

  @Override
  public ListAllBucketsResult getListAllBucketsResult() throws IOException {
    Path currentPath = Paths.get(storagePath);
    List<Path> buckets = Files.list(currentPath).toList();
    return ListAllBucketsResult.fromPaths(buckets);
  }

  @Override
  public boolean containsKey(String key) {
    return multipartUploads.containsKey(key);
  }

  @Override
  public String handleUploadPart(String uploadId, Map<String, String> queryParams, byte[] payload)
      throws IOException {
    int partNumber = Integer.parseInt(queryParams.get("partNumber"));
    Path tempDir = Files.createTempDirectory("multipart-");
    Path tempFile = tempDir.resolve("part-" + partNumber);

    try (InputStream is = new ByteArrayInputStream(payload);
        OutputStream os = Files.newOutputStream(tempFile)) {
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = is.read(buffer)) != -1) {
        os.write(buffer, 0, bytesRead);
      }
      os.flush();
    }

    String eTag = calculateETag(tempFile, false, List.of());
    multipartUploads.get(uploadId).add(new PartInfo(partNumber, eTag, tempFile));
    return eTag;
  }

  @Override
  public CompleteMultipartUploadResult getCompleteMultipartUploadResult(
      String bucketName, String key, String uploadId) throws IOException {
    Path bucketPath = Paths.get(storagePath, bucketName);
    Path finalPath = bucketPath.resolve(key);
    Files.createDirectories(finalPath.getParent());

    List<PartInfo> parts = multipartUploads.get(uploadId);
    List<String> eTags = parts.stream().map(e -> e.eTag).toList();
    parts.sort((a, b) -> Integer.compare(a.partNumber, b.partNumber));

    try (OutputStream os = Files.newOutputStream(finalPath)) {
      for (PartInfo part : parts) {
        Files.copy(part.tempPath, os);
        Files.delete(part.tempPath);
      }
      os.flush();
    }

    multipartUploads.remove(uploadId);
    return new CompleteMultipartUploadResult(
        bucketName,
        key,
        Files.size(finalPath),
        calculateETag(finalPath, true, eTags),
        finalPath,
        eTags);
  }

  @Override
  public void handleAbortMultipartUpload(String uploadId) throws IOException {
    List<PartInfo> parts = multipartUploads.remove(uploadId);
    if (parts != null) {
      for (PartInfo part : parts) {
        Files.deleteIfExists(part.tempPath);
      }
    }
  }

  @Override
  public boolean bucketExists(String bucketName) {
    Path bucketPath = Paths.get(storagePath, bucketName);
    return Files.exists(bucketPath);
  }

  @Override
  public boolean bucketExists(Path bucketName) {
    return Files.exists(bucketName);
  }

  @Override
  public void createDirectory(Path bucketPath) throws IOException {
    Files.createDirectory(bucketPath);
  }

  @Override
  public BucketListResult getBucketListResult(
      HttpExchange exchange, String bucketName, Path bucketPath) throws IOException {
    Map<String, String> queryParams = parseQueryString(exchange.getRequestURI().getQuery());
    boolean isV2 = "2".equals(queryParams.get("list-type"));
    String prefix = queryParams.getOrDefault("prefix", "");
    String delimiter = queryParams.getOrDefault("delimiter", "");
    int maxKeys = Integer.parseInt(queryParams.getOrDefault("max-keys", "1000"));
    String continuationToken = queryParams.get(isV2 ? "continuation-token" : "marker");

    List<Path> allObjects =
        Files.walk(bucketPath)
            .filter(Files::isRegularFile)
            .filter(
                p -> {
                  String key = bucketPath.relativize(p).toString();
                  return key.startsWith(prefix);
                })
            .sorted()
            .toList();

    int startIndex = 0;
    if (continuationToken != null) {
      for (int i = 0; i < allObjects.size(); i++) {
        if (bucketPath.relativize(allObjects.get(i)).toString().compareTo(continuationToken) > 0) {
          startIndex = i;
          break;
        }
      }
    }

    Set<String> commonPrefixes = new HashSet<>();
    List<Path> objects = new ArrayList<>();
    int count = 0;
    String nextContinuationToken = null;

    for (int i = startIndex; i < allObjects.size() && count < maxKeys; i++) {
      Path object = allObjects.get(i);
      String key = bucketPath.relativize(object).toString();

      if (!delimiter.isEmpty()) {
        int delimiterIndex = key.indexOf(delimiter, prefix.length());
        if (delimiterIndex >= 0) {
          String commonPrefix = key.substring(0, delimiterIndex + delimiter.length());
          if (commonPrefixes.add(commonPrefix)) {
            count++;
          }
          continue;
        }
      }

      objects.add(object);
      count++;

      if (count == maxKeys && i + 1 < allObjects.size()) {
        nextContinuationToken = bucketPath.relativize(allObjects.get(i + 1)).toString();
      }
    }

    return new BucketListResult.Builder()
        .bucketName(bucketName)
        .prefix(prefix)
        .delimiter(delimiter)
        .maxKeys(maxKeys)
        .continuationToken(continuationToken)
        .nextContinuationToken(nextContinuationToken)
        .commonPrefixes(commonPrefixes)
        .objects(objects)
        .bucketPath(bucketPath)
        .isV2(isV2)
        .build();
  }

  @Override
  public boolean objectExists(Path objectPath) {
    return !Files.exists(objectPath);
  }

  @Override
  public String handlePutObject(Path objectPath, byte[] payload) throws IOException {
    Files.createDirectories(objectPath.getParent());

    try (InputStream is = new ByteArrayInputStream(payload);
        OutputStream os = Files.newOutputStream(objectPath)) {
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = is.read(buffer)) != -1) {
        os.write(buffer, 0, bytesRead);
      }
    }
    return calculateETag(objectPath, false, List.of());
  }

  @Override
  public void handleDeleteObject(Path objectPath) throws IOException {
    Files.delete(objectPath);
  }

  @Override
  public boolean bucketHasFiles(Path bucketPath) throws IOException {
    return Files.list(bucketPath).findFirst().isPresent();
  }

  @Override
  public void getObject(HttpExchange exchange, Path objectPath) throws IOException {
    try (InputStream is = Files.newInputStream(objectPath)) {
      exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
      exchange.sendResponseHeaders(200, Files.size(objectPath));

      try (OutputStream os = exchange.getResponseBody()) {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
          os.write(buffer, 0, bytesRead);
        }
      }
    }
  }

  @Override
  public long getSize(Path objectPath) throws IOException {
    return Files.size(objectPath);
  }

  @Override
  public FileTime getLastModifiedTime(Path objectPath) throws IOException {
    return Files.getLastModifiedTime(objectPath);
  }
}
