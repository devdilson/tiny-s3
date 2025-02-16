package com.tinys3;

import com.tinys3.http.S3HttpExchange;
import com.tinys3.response.BucketListResult;
import com.tinys3.response.CompleteMultipartUploadResult;
import com.tinys3.response.InitiateMultipartUploadResult;
import com.tinys3.response.ListAllBucketsResult;
import java.io.*;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class InMemoryS3FileOperations implements S3FileOperations {
  private static class S3Object {
    byte[] data;
    FileTime creationTime;
    FileTime lastModifiedTime;
    FileTime lastAccessTime;

    S3Object(byte[] data) {
      this.data = data;
      long now = System.currentTimeMillis();
      this.creationTime = FileTime.fromMillis(now);
      this.lastModifiedTime = FileTime.fromMillis(now);
      this.lastAccessTime = FileTime.fromMillis(now);
    }
  }

  private final Map<String, Set<String>> buckets =
      new ConcurrentHashMap<>(); // bucket -> set of keys
  private final Map<String, S3Object> objects =
      new ConcurrentHashMap<>(); // bucketName/key -> object
  private final Map<String, List<PartInfo>> multipartUploads = new ConcurrentHashMap<>();
  private final Map<String, byte[]> multipartParts = new ConcurrentHashMap<>();

  private String getFullKey(String bucketName, String key) {
    return bucketName + "/" + key;
  }

  @Override
  public InitiateMultipartUploadResult getInitiateMultipartUploadResult(
      String bucketName, String key) {
    String uploadId = UUID.randomUUID().toString();
    multipartUploads.put(uploadId, new ArrayList<>());
    return new InitiateMultipartUploadResult(bucketName, key, uploadId);
  }

  @Override
  public ListAllBucketsResult getListAllBucketsResult(String accessKey) {
    List<Path> bucketPaths =
        buckets.keySet().stream().map(bucket -> Paths.get(bucket)).collect(Collectors.toList());
    return ListAllBucketsResult.fromPaths(bucketPaths, accessKey);
  }

  @Override
  public boolean containsKey(String key) {
    return multipartUploads.containsKey(key);
  }

  @Override
  public String handleUploadPart(String uploadId, Map<String, String> queryParams, byte[] payload) {
    int partNumber = Integer.parseInt(queryParams.get("partNumber"));
    String partKey = uploadId + "-" + partNumber;
    multipartParts.put(partKey, payload);

    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      String eTag = "\"" + Base64.getEncoder().encodeToString(md.digest(payload)) + "\"";
      multipartUploads.get(uploadId).add(new PartInfo(partNumber, eTag, null));
      return eTag;
    } catch (Exception e) {
      return "\"dummy-etag\"";
    }
  }

  @Override
  public CompleteMultipartUploadResult getCompleteMultipartUploadResult(
      String bucketName, String key, String uploadId) throws IOException {
    List<PartInfo> parts = multipartUploads.get(uploadId);
    List<String> eTags = parts.stream().map(e -> e.eTag).collect(Collectors.toList());
    parts.sort((a, b) -> Integer.compare(a.partNumber, b.partNumber));

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    for (PartInfo part : parts) {
      byte[] partData = multipartParts.get(uploadId + "-" + part.partNumber);
      outputStream.write(partData);
    }

    byte[] finalData = outputStream.toByteArray();
    String fullKey = getFullKey(bucketName, key);
    objects.put(fullKey, new S3Object(finalData));
    buckets.computeIfAbsent(bucketName, k -> new HashSet<>()).add(key);

    multipartUploads.remove(uploadId);
    parts.forEach(part -> multipartParts.remove(uploadId + "-" + part.partNumber));

    return new CompleteMultipartUploadResult(
        bucketName,
        key,
        finalData.length,
        calculateETag(bucketName, key, true, eTags),
        Paths.get(fullKey),
        eTags);
  }

  @Override
  public void handleAbortMultipartUpload(String uploadId) {
    List<PartInfo> parts = multipartUploads.remove(uploadId);
    if (parts != null) {
      parts.forEach(part -> multipartParts.remove(uploadId + "-" + part.partNumber));
    }
  }

  @Override
  public boolean bucketExists(String bucketName) {
    return buckets.containsKey(bucketName);
  }

  @Override
  public void createDirectory(String bucketPath) {
    buckets.putIfAbsent(bucketPath, new HashSet<>());
  }

  @Override
  public BucketListResult getBucketListResult(S3HttpExchange exchange, String bucketName) {
    Map<String, String> queryParams = S3Utils.parseQueryString(exchange.getRequestURI().getQuery());
    boolean isV2 = "2".equals(queryParams.get("list-type"));
    String prefix = queryParams.getOrDefault("prefix", "");
    String delimiter = queryParams.getOrDefault("delimiter", "");
    int maxKeys = Integer.parseInt(queryParams.getOrDefault("max-keys", "1000"));
    String continuationToken = queryParams.get(isV2 ? "continuation-token" : "marker");

    Set<String> keys = buckets.getOrDefault(bucketName, new HashSet<>());
    List<String> filteredKeys =
        keys.stream().filter(key -> key.startsWith(prefix)).sorted().collect(Collectors.toList());

    int startIndex = 0;
    if (continuationToken != null) {
      for (int i = 0; i < filteredKeys.size(); i++) {
        if (filteredKeys.get(i).compareTo(continuationToken) > 0) {
          startIndex = i;
          break;
        }
      }
    }

    Set<String> commonPrefixes = new HashSet<>();
    List<Path> objects = new ArrayList<>();
    int count = 0;
    String nextContinuationToken = null;

    for (int i = startIndex; i < filteredKeys.size() && count < maxKeys; i++) {
      String key = filteredKeys.get(i);

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

      objects.add(Paths.get(bucketName, key));
      count++;

      if (count == maxKeys && i + 1 < filteredKeys.size()) {
        nextContinuationToken = filteredKeys.get(i + 1);
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
        .bucketPath(getObjectPath(bucketName, ""))
        .isV2(isV2)
        .build();
  }

  @Override
  public boolean objectExists(String bucketName, String key) {
    return objects.containsKey(getFullKey(bucketName, key));
  }

  @Override
  public String handlePutObject(String bucketName, String key, byte[] payload) {
    String fullKey = getFullKey(bucketName, key);
    objects.put(fullKey, new S3Object(payload));
    buckets.computeIfAbsent(bucketName, k -> new HashSet<>()).add(key);
    return calculateETag(bucketName, key, false, List.of());
  }

  @Override
  public void handleDeleteObject(String bucketName, String key) {
    String fullKey = getFullKey(bucketName, key);
    objects.remove(fullKey);
    buckets.getOrDefault(bucketName, new HashSet<>()).remove(key);
  }

  @Override
  public boolean bucketHasFiles(String bucketPath) {
    return !buckets.getOrDefault(bucketPath, new HashSet<>()).isEmpty();
  }

  @Override
  public void getObject(S3HttpExchange exchange, String bucketName, String key) throws IOException {
    String fullKey = getFullKey(bucketName, key);
    S3Object object = objects.get(fullKey);
    if (object == null) {
      throw new NoSuchFileException(fullKey);
    }

    exchange.getResponseHeaders().addHeader("Content-Type", "application/octet-stream");
    exchange.sendResponseHeaders(200, object.data.length);

    try (OutputStream os = exchange.getResponseBody()) {
      os.write(object.data);
    }
  }

  @Override
  public long getSize(String bucketName, String key) throws IOException {
    String fullKey = getFullKey(bucketName, key);
    S3Object object = objects.get(fullKey);
    if (object == null) {
      throw new NoSuchFileException(fullKey);
    }
    return object.data.length;
  }

  @Override
  public FileTime getLastModifiedTime(String bucketName, String key) throws IOException {
    String fullKey = getFullKey(bucketName, key);
    S3Object object = objects.get(fullKey);
    if (object == null) {
      throw new NoSuchFileException(fullKey);
    }
    return object.lastModifiedTime;
  }

  @Override
  public String calculateETag(
      String bucketName, String key, boolean isMultipart, List<String> partETags) {
    try {
      if (!isMultipart) {
        String fullKey = getFullKey(bucketName, key);
        S3Object object = objects.get(fullKey);
        if (object == null) {
          return "\"dummy-etag\"";
        }
        MessageDigest md = MessageDigest.getInstance("MD5");
        return "\"" + Base64.getEncoder().encodeToString(md.digest(object.data)) + "\"";
      } else {
        MessageDigest md = MessageDigest.getInstance("MD5");
        for (String partETag : partETags) {
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

  @Override
  public Path getObjectPath(String bucketName, String key) {
    return Paths.get(bucketName, key);
  }

  @Override
  public void copyObject(
      String sourceBucketName, String sourceKey, String destBucketName, String destKey) {
    String sourceFullKey = getFullKey(sourceBucketName, sourceKey);
    String destFullKey = getFullKey(destBucketName, destKey);

    S3Object sourceObject = objects.get(sourceFullKey);
    if (sourceObject == null) {
      throw new RuntimeException("Source object not found: " + sourceFullKey);
    }

    // Create a new S3Object with copied data and metadata
    S3Object destObject = new S3Object(Arrays.copyOf(sourceObject.data, sourceObject.data.length));
    destObject.creationTime = sourceObject.creationTime;
    destObject.lastModifiedTime = FileTime.fromMillis(System.currentTimeMillis());
    destObject.lastAccessTime = FileTime.fromMillis(System.currentTimeMillis());

    objects.put(destFullKey, destObject);
    buckets.computeIfAbsent(destBucketName, k -> new HashSet<>()).add(destKey);
  }

  @Override
  public Object relativize(Path path) {
    return null;
  }
}
