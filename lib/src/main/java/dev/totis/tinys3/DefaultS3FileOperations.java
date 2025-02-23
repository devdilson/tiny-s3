package dev.totis.tinys3;

import static dev.totis.tinys3.S3Context.parseQueryString;

import dev.totis.tinys3.http.S3HttpExchange;
import dev.totis.tinys3.io.FileEntry;
import dev.totis.tinys3.io.FileOperations;
import dev.totis.tinys3.io.PartInfo;
import dev.totis.tinys3.io.StorageException;
import dev.totis.tinys3.response.*;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class DefaultS3FileOperations implements S3FileOperations {
  private final FileOperations fileOps;
  private final Map<String, List<PartInfo>> multipartUploads = new ConcurrentHashMap<>();

  public DefaultS3FileOperations(FileOperations fileOps) {
    this.fileOps = fileOps;
  }

  @Override
  public InitiateMultipartUploadResult getInitiateMultipartUploadResult(
      String bucketName, String key) {
    String uploadId = UUID.randomUUID().toString();
    multipartUploads.put(uploadId, new ArrayList<>());
    return new InitiateMultipartUploadResult(bucketName, key, uploadId);
  }

  @Override
  public ListAllBucketsResult getListAllBucketsResult(String accessKey) throws StorageException {
    FileEntry[] buckets = fileOps.listBuckets();
    return ListAllBucketsResult.fromBuckets(buckets, accessKey);
  }

  @Override
  public boolean containsKey(String key) {
    return multipartUploads.containsKey(key);
  }

  @Override
  public String handleUploadPart(String uploadId, Map<String, String> queryParams, byte[] payload)
      throws StorageException {
    int partNumber = Integer.parseInt(queryParams.get("partNumber"));
    String tempDir = fileOps.createTempDirectory("multipart-");
    String tempFilePath = tempDir + "/part-" + partNumber;

    fileOps.writeTempFile(tempFilePath, payload);

    Path tempPath = Path.of(tempFilePath);
    String eTag = S3Utils.calculateETag(tempPath, false, List.of());
    multipartUploads.get(uploadId).add(new PartInfo(partNumber, eTag, tempPath.toString()));
    return eTag;
  }

  @Override
  public CompleteMultipartUploadResult getCompleteMultipartUploadResult(
      String bucketName, String key, String uploadId) throws StorageException {
    String finalPath = getObjectPath(bucketName, key);
    fileOps.createParentDirectories(finalPath);

    List<PartInfo> parts = multipartUploads.get(uploadId);
    List<String> eTags = parts.stream().map(PartInfo::eTag).toList();
    parts.sort(Comparator.comparingInt(PartInfo::partNumber));

    // For now delete the existing file
    fileOps.delete(finalPath);

    for (PartInfo part : parts) {
      byte[] partData = fileOps.readTempFile(part.tempPath());
      fileOps.appendToFile(finalPath, partData);
      fileOps.deleteTempFile(part.tempPath());
    }

    multipartUploads.remove(uploadId);
    Path objectPath = Path.of(finalPath);
    return new CompleteMultipartUploadResult(
        bucketName,
        key,
        fileOps.getSize(finalPath),
        S3Utils.calculateETag(objectPath, true, eTags),
        objectPath,
        eTags);
  }

  @Override
  public void handleAbortMultipartUpload(String uploadId) throws StorageException {
    List<PartInfo> parts = multipartUploads.remove(uploadId);
    if (parts != null) {
      for (PartInfo part : parts) {
        fileOps.delete(part.tempPath());
      }
    }
  }

  @Override
  public boolean bucketExists(String bucketName) {
    return fileOps.exists(bucketName);
  }

  private String getObjectPath(String bucketName, String key) {
    return fileOps.getObjectPath(bucketName, key);
  }

  @Override
  public void createDirectory(String bucketName) throws StorageException {
    fileOps.createDirectory(bucketName);
  }

  @Override
  public BucketListResult getBucketListResult(S3HttpExchange exchange, String bucketName)
      throws StorageException {
    Map<String, String> queryParams = parseQueryString(exchange.getRequestURI().getQuery());
    boolean isV2 = "2".equals(queryParams.get("list-type"));
    String prefix = queryParams.getOrDefault("prefix", "");
    String delimiter = queryParams.getOrDefault("delimiter", "");
    int maxKeys = Integer.parseInt(queryParams.getOrDefault("max-keys", "1000"));
    String continuationToken = queryParams.get(isV2 ? "continuation-token" : "marker");

    FileEntry[] allEntries = fileOps.list(bucketName);
    List<FileEntry> allObjects =
        Arrays.stream(allEntries)
            .filter(entry -> !entry.isDirectory())
            .filter(entry -> entry.path().startsWith(prefix)) // TODO: FIX PREFIX
            .sorted(Comparator.comparing(FileEntry::path))
            .toList();

    int startIndex = 0;
    if (continuationToken != null) {
      for (int i = 0; i < allObjects.size(); i++) {
        if (allObjects.get(i).path().compareTo(continuationToken) > 0) {
          startIndex = i;
          break;
        }
      }
    }

    Set<String> commonPrefixes = new HashSet<>();
    List<FileEntry> objects = new ArrayList<>();
    int count = 0;
    String nextContinuationToken = null;

    for (int i = startIndex; i < allObjects.size() && count < maxKeys; i++) {
      FileEntry object = allObjects.get(i);
      String key = object.path();

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
        nextContinuationToken = allObjects.get(i + 1).path();
      }
    }

    List<BucketObject> bucketObjects =
        objects.stream()
            .map(
                entry ->
                    new BucketObject(
                        entry.path(), entry.size(), FileTime.fromMillis(entry.lastModified())))
            .collect(Collectors.toList());

    return new BucketListResult.Builder()
        .bucketName(bucketName)
        .prefix(prefix)
        .delimiter(delimiter)
        .maxKeys(maxKeys)
        .continuationToken(continuationToken)
        .nextContinuationToken(nextContinuationToken)
        .commonPrefixes(commonPrefixes)
        .objects(bucketObjects)
        .bucketPath(bucketName)
        .isV2(isV2)
        .build();
  }

  @Override
  public boolean objectNotExists(String bucketName, String key) {
    return !fileOps.exists(getObjectPath(bucketName, key));
  }

  @Override
  public String handlePutObject(String bucketName, String key, byte[] payload)
      throws StorageException {
    String objectPath = getObjectPath(bucketName, key);
    fileOps.createParentDirectories(objectPath);
    fileOps.writeFile(objectPath, payload);
    return calculateETag(bucketName, key, false, List.of());
  }

  @Override
  public void handleDeleteObject(String bucketName, String key) throws StorageException {
    fileOps.delete(getObjectPath(bucketName, key));
  }

  @Override
  public boolean bucketHasFiles(String bucketName) throws StorageException {
    return fileOps.isDirectoryNotEmpty(getObjectPath(bucketName, ""));
  }

  @Override
  public void getObject(S3HttpExchange exchange, String bucketName, String key)
      throws StorageException {

    String objectPath = getObjectPath(bucketName, key);
    long contentLength = fileOps.getSize(objectPath);

    try (InputStream is = fileOps.readFileStream(objectPath);
        OutputStream os = exchange.getResponseBody()) {
      exchange.getResponseHeaders().addHeader("Content-Type", "application/octet-stream");
      exchange.sendResponseHeaders(200, contentLength);

      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = is.read(buffer)) != -1) {
        os.write(buffer, 0, bytesRead);
      }
    } catch (IOException e) {
      throw new StorageException("Failed to write response", e);
    }
  }

  @Override
  public long getSize(String bucketName, String key) throws StorageException {
    return fileOps.getSize(getObjectPath(bucketName, key));
  }

  @Override
  public FileTime getLastModifiedTime(String bucketName, String key) throws StorageException {
    return fileOps.getLastModifiedTime(getObjectPath(bucketName, key));
  }

  @Override
  public String calculateETag(
      String bucketName, String key, boolean isMultipart, List<String> partETags) {
    try {
      if (!isMultipart) {
        MessageDigest md = MessageDigest.getInstance("MD5");
        String objectPath = getObjectPath(bucketName, key);

        try (InputStream is = fileOps.readFileStream(objectPath)) {
          byte[] buffer = new byte[8192]; // 8KB chunks
          int bytesRead;

          while ((bytesRead = is.read(buffer)) != -1) {
            md.update(buffer, 0, bytesRead);
          }

          byte[] hash = md.digest();
          return "\"" + Base64.getEncoder().encodeToString(hash) + "\"";
        }
      } else {
        // Multipart ETag calculation remains the same since part ETags are already computed
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
  public void copyObject(
      String sourceBucketName, String sourceKey, String destBucketName, String destKey)
      throws StorageException {
    String sourcePath = getObjectPath(sourceBucketName, sourceKey);
    String destPath = getObjectPath(destBucketName, destKey);

    fileOps.createParentDirectories(destPath);
    fileOps.copy(sourcePath, destPath);
  }
}
