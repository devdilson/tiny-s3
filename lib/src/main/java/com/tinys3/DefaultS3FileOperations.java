package com.tinys3;

import static com.tinys3.S3Utils.parseQueryString;

import com.sun.net.httpserver.HttpExchange;
import com.tinys3.response.BucketListResult;
import com.tinys3.response.CompleteMultipartUploadResult;
import com.tinys3.response.InitiateMultipartUploadResult;
import com.tinys3.response.ListAllBucketsResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultS3FileOperations {

  private final String storagePath;

  private final Map<String, List<PartInfo>> multipartUploads = new ConcurrentHashMap<>();

  public DefaultS3FileOperations(String storagePath) {
    this.storagePath = storagePath;
  }

  public InitiateMultipartUploadResult getInitiateMultipartUploadResult(
      String bucketName, String key) {
    String uploadId = UUID.randomUUID().toString();
    multipartUploads.put(uploadId, new ArrayList<>());
    return new InitiateMultipartUploadResult(bucketName, key, uploadId);
  }

  public ListAllBucketsResult getListAllBucketsResult(String accessKey) throws IOException {
    Path currentPath = Paths.get(storagePath);
    List<Path> buckets = Files.list(currentPath).toList();
    return ListAllBucketsResult.fromPaths(buckets, accessKey);
  }

  public boolean containsKey(String key) {
    return multipartUploads.containsKey(key);
  }

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

    String eTag = S3Utils.calculateETag(tempFile, false, List.of());
    multipartUploads.get(uploadId).add(new PartInfo(partNumber, eTag, tempFile));
    return eTag;
  }

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
        S3Utils.calculateETag(finalPath, true, eTags),
        finalPath,
        eTags);
  }

  public void handleAbortMultipartUpload(String uploadId) throws IOException {
    List<PartInfo> parts = multipartUploads.remove(uploadId);
    if (parts != null) {
      for (PartInfo part : parts) {
        Files.deleteIfExists(part.tempPath);
      }
    }
  }

  public boolean bucketExists(String bucketName) {
    Path bucketPath = Paths.get(storagePath, bucketName);
    return Files.exists(bucketPath);
  }

  public void createDirectory(Path bucketPath) throws IOException {
    Files.createDirectory(bucketPath);
  }

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

  public boolean objectExists(String bucketName, String key) {
    var objectPath = Paths.get(storagePath, bucketName, key);
    return !Files.exists(objectPath);
  }

  public String handlePutObject(String bucketName, String key, byte[] payload) throws IOException {
    Path objectPath = Paths.get(storagePath, bucketName, key);
    Files.createDirectories(objectPath.getParent());

    try (InputStream is = new ByteArrayInputStream(payload);
        OutputStream os = Files.newOutputStream(objectPath)) {
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = is.read(buffer)) != -1) {
        os.write(buffer, 0, bytesRead);
      }
    }
    return calculateETag(bucketName, key, false, List.of());
  }

  public void handleDeleteObject(String bucketName, String key) throws IOException {
    var objectPath = Paths.get(storagePath, bucketName, key);
    Files.delete(objectPath);
  }

  public boolean bucketHasFiles(Path bucketPath) throws IOException {
    return Files.list(bucketPath).findFirst().isPresent();
  }

  public void getObject(HttpExchange exchange, String bucketName, String key) throws IOException {
    var objectPath = getObjectPath(bucketName, key);
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

  public long getSize(String bucketName, String key) throws IOException {
    Path bucketPath = getObjectPath(bucketName, key);
    return Files.size(bucketPath);
  }

  public FileTime getLastModifiedTime(String bucketName, String key) throws IOException {
    var objectPath = getObjectPath(bucketName, key);
    return Files.getLastModifiedTime(objectPath);
  }

  public String calculateETag(
      String bucketName, String key, boolean isMultipart, List<String> partETags) {
    try {
      Path objectPath = getObjectPath(bucketName, key);
      MessageDigest md = MessageDigest.getInstance("MD5");
      if (!isMultipart) {
        // Single upload ETag
        byte[] hash = md.digest(Files.readAllBytes(objectPath));
        return "\"" + Base64.getEncoder().encodeToString(hash) + "\"";
      } else {
        // Multipart upload ETag
        for (String partETag : partETags) {
          // Remove quotes from part ETags
          String cleanPartETag = partETag.replaceAll("\"", "");
          md.update(Base64.getDecoder().decode(cleanPartETag));
        }
        String finalHash = Base64.getEncoder().encodeToString(md.digest());
        return "\"" + finalHash + "-" + partETags.size() + "\"";
      }
    } catch (Exception e) {
      return "\"dummy-etag\"";
    }
  }

  private Path getObjectPath(String bucketName, String key) {
    return Paths.get(storagePath, bucketName, key);
  }

  public void copyObject(
      String sourceBucketName, String sourceKey, String destBucketName, String destKey) {
    try {
      Path sourcePath = getObjectPath(sourceBucketName, sourceKey);
      Path destPath = getObjectPath(destBucketName, destKey);

      Files.createDirectories(destPath.getParent());

      Files.copy(sourcePath, destPath, StandardCopyOption.REPLACE_EXISTING);

      BasicFileAttributes sourceAttrs = Files.readAttributes(sourcePath, BasicFileAttributes.class);
      FileTime creationTime = sourceAttrs.creationTime();
      FileTime lastModifiedTime = sourceAttrs.lastModifiedTime();
      FileTime lastAccessTime = sourceAttrs.lastAccessTime();

      Files.setAttribute(destPath, "creationTime", creationTime);
      Files.setAttribute(destPath, "lastModifiedTime", lastModifiedTime);
      Files.setAttribute(destPath, "lastAccessTime", lastAccessTime);

    } catch (NoSuchFileException e) {
      throw new RuntimeException(
          "Source object not found: " + sourceBucketName + "/" + sourceKey, e);
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to copy object from "
              + sourceBucketName
              + "/"
              + sourceKey
              + " to "
              + destBucketName
              + "/"
              + destKey,
          e);
    }
  }
}
