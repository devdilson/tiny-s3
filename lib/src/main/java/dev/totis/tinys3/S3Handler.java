package dev.totis.tinys3;

import static dev.totis.tinys3.CanonicalRequest.createCanonicalRequest;
import static dev.totis.tinys3.S3Utils.*;

import dev.totis.tinys3.auth.Credentials;
import dev.totis.tinys3.auth.S3Authenticator;
import dev.totis.tinys3.http.S3HttpExchange;
import dev.totis.tinys3.http.S3HttpHeaders;
import dev.totis.tinys3.io.StorageException;
import dev.totis.tinys3.response.CopyObjectResult;
import dev.totis.tinys3.response.PostUploadResult;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class S3Handler {
  private final Map<String, Credentials> credentials;
  private final S3Authenticator authenticator;
  private final S3FileOperations fileSystem;

  public S3Handler(
      Map<String, Credentials> credentials,
      S3Authenticator authenticator,
      S3FileOperations fileSystem) {
    this.credentials = credentials;
    this.authenticator = authenticator;
    this.fileSystem = fileSystem;
  }

  public void handle(S3HttpExchange exchange) throws IOException {
    try {
      byte[] payload = null;
      if (exchange.getRequestBody() != null) {
        payload = copyPayload(exchange);
      }
      if (!authenticator.authenticateRequest(exchange, payload)) {
        sendError(exchange, 403, "XAmzContentSHA256Mismatch");
        return;
      }

      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();
      String query = Objects.requireNonNullElse(exchange.getRequestURI().getQuery(), "");

      if (method.equals("POST") && query.contains("presigned-url")) {
        handlePreSignedUrlGeneration(exchange);
        return;
      }

      if (path.equals("/") && method.equals("GET")) {
        handleListBuckets(exchange, authenticator.getCredentials(exchange));
        return;
      }

      String[] pathParts = path.split("/", 3);
      String bucketName = pathParts.length > 1 ? pathParts[1] : "";
      String key = pathParts.length > 2 ? pathParts[2] : "";

      if (query.contains("uploads")) {
        handleMultipartUpload(exchange, bucketName, key, method);
        return;
      } else if (query.contains("uploadId")) {
        handleMultipartOperation(exchange, bucketName, key, method, query, payload);
        return;
      } else if (key.isEmpty()) {
        handleBucketOperation(exchange, bucketName, method);
        return;
      }

      handleObjectOperation(exchange, bucketName, key, method, payload);

    } catch (Exception e) {
      e.printStackTrace();
      sendError(exchange, 500, "InternalError");
    }
  }

  private void handleMultipartUpload(
      S3HttpExchange exchange, String bucketName, String key, String method) throws IOException {
    if (method.equals("POST")) {
      var response = fileSystem.getInitiateMultipartUploadResult(bucketName, key);
      sendResponse(exchange, 200, response.toXML(), "application/xml");
    }
  }

  private void handleMultipartOperation(
      S3HttpExchange exchange,
      String bucketName,
      String key,
      String method,
      String query,
      byte[] payload)
      throws IOException, StorageException {
    Map<String, String> queryParams = parseQueryString(query);
    String uploadId = queryParams.get("uploadId");

    if (!fileSystem.containsKey(uploadId)) {
      sendError(exchange, 404, "NoSuchUpload");
      return;
    }

    switch (method) {
      case "PUT" -> handleUploadPart(exchange, uploadId, queryParams, payload);
      case "POST" -> handleCompleteMultipartUpload(exchange, bucketName, key, uploadId);
      case "DELETE" -> handleAbortMultipartUpload(exchange, uploadId);
    }
  }

  private void handleUploadPart(
      S3HttpExchange exchange, String uploadId, Map<String, String> queryParams, byte[] payload)
      throws IOException, StorageException {
    String eTag = fileSystem.handleUploadPart(uploadId, queryParams, payload);

    exchange.getResponseHeaders().addHeader("ETag", "\"" + eTag + "\"");
    exchange.sendResponseHeaders(200, -1);
    exchange.getResponseBody().close();
  }

  private void handleCompleteMultipartUpload(
      S3HttpExchange exchange, String bucketName, String key, String uploadId) throws IOException {

    try {
      var result = fileSystem.getCompleteMultipartUploadResult(bucketName, key, uploadId);
      sendResponse(exchange, 200, result.toXML(), "application/xml");
    } catch (Exception e) {
      sendError(exchange, 500, "InternalError");
    }
  }

  private void handleAbortMultipartUpload(S3HttpExchange exchange, String uploadId)
      throws IOException, StorageException {
    fileSystem.handleAbortMultipartUpload(uploadId);
    exchange.sendResponseHeaders(204, -1);
    exchange.getResponseBody().close();
  }

  private void handleListBuckets(S3HttpExchange exchange, Credentials credentials)
      throws IOException, StorageException {
    var result = fileSystem.getListAllBucketsResult(credentials.accessKey());
    sendResponse(exchange, 200, result.toXML(), "application/xml");
  }

  private void handleBucketOperation(S3HttpExchange exchange, String bucketName, String method)
      throws IOException, StorageException {
    switch (method) {
      case "HEAD":
        handleHeadObject(exchange, bucketName, "", null);
        break;
      case "GET":
        handleListObjects(exchange, bucketName);
        break;
      case "PUT":
        handleCreateBucket(exchange, bucketName);
        break;
      case "DELETE":
        handleDeleteBucket(exchange, bucketName);
        break;
      default:
        sendError(exchange, 405, "MethodNotAllowed");
    }
  }

  private void handleHeadObject(
      S3HttpExchange exchange, String bucketName, String objectKey, byte[] payload)
      throws IOException, StorageException {
    if (!fileSystem.bucketExists(bucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    if (fileSystem.objectNotExists(bucketName, objectKey) && !objectKey.isEmpty()) {
      sendError(exchange, 404, "NoSuchKey");
      return;
    }

    S3HttpHeaders responseHeaders = exchange.getResponseHeaders();
    responseHeaders.addHeader("Content-Type", "application/octet-stream");
    responseHeaders.addHeader(
        "Content-Length", String.valueOf(fileSystem.getSize(bucketName, objectKey)));
    var lastModified = fileSystem.getLastModifiedTime(bucketName, objectKey);
    String lastModifiedStr =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'")
            .withZone(ZoneOffset.UTC)
            .withLocale(Locale.US)
            .format(lastModified.toInstant());
    responseHeaders.addHeader("Last-Modified", lastModifiedStr);
    responseHeaders.addHeader(
        "ETag", "\"" + fileSystem.calculateETag(bucketName, objectKey, false, List.of()) + "\"");

    exchange.sendResponseHeaders(200, -1);
  }

  private void handleListObjects(S3HttpExchange exchange, String bucketName)
      throws IOException, StorageException {
    if (!fileSystem.bucketExists(bucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    var result = fileSystem.getBucketListResult(exchange, bucketName);

    sendResponse(exchange, 200, result.toXML(), "application/xml");
  }

  private void handleCreateBucket(S3HttpExchange exchange, String bucketName)
      throws IOException, StorageException {

    if (fileSystem.bucketExists(bucketName)) {
      sendError(exchange, 409, "BucketAlreadyExists");
      return;
    }

    fileSystem.createDirectory(bucketName);
    sendResponse(exchange, 200, "", "application/xml");
  }

  private void handleDeleteBucket(S3HttpExchange exchange, String bucketName)
      throws IOException, StorageException {

    if (!fileSystem.bucketExists(bucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    if (fileSystem.bucketHasFiles(bucketName)) {
      sendError(exchange, 409, "BucketNotEmpty");
      return;
    }

    fileSystem.handleDeleteObject(bucketName, "");
    sendResponse(exchange, 204, "", "application/xml");
  }

  private void handleObjectOperation(
      S3HttpExchange exchange, String bucketName, String key, String method, byte[] payload)
      throws IOException, StorageException {

    switch (method) {
      case "GET":
        handleGetObject(exchange, bucketName, key);
        break;
      case "HEAD":
        handleHeadObject(exchange, bucketName, key, payload);
        break;
      case "POST":
        handlePostObject(exchange, bucketName, key, payload);
        break;
      case "PUT":
        if (exchange.getRequestHeaders().containsHeader("x-amz-copy-source")) {
          handleCopyObject(exchange, bucketName, key);
        } else {
          handlePutObject(exchange, bucketName, key, payload);
        }
        break;
      case "DELETE":
        handleDeleteObject(exchange, bucketName, key);
        break;
      default:
        sendError(exchange, 405, "MethodNotAllowed");
    }
  }

  private void handleCopyObject(S3HttpExchange exchange, String destBucketName, String destKey)
      throws IOException, StorageException {
    String copySource = exchange.getRequestHeaders().getFirst("x-amz-copy-source");
    if (copySource == null) {
      sendError(exchange, 400, "MissingParameter");
      return;
    }

    if (copySource.startsWith("/")) {
      copySource = copySource.substring(1);
    }
    String[] sourceParts = copySource.split("/", 2);
    if (sourceParts.length != 2) {
      sendError(exchange, 400, "InvalidRequest");
      return;
    }

    String sourceBucketName = sourceParts[0];
    String sourceKey = sourceParts[1];

    if (fileSystem.objectNotExists(sourceBucketName, sourceKey)) {
      sendError(exchange, 404, "NoSuchKey");
      return;
    }

    if (!fileSystem.bucketExists(destBucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    fileSystem.copyObject(sourceBucketName, sourceKey, destBucketName, destKey);

    var result = new CopyObjectResult(destBucketName, destKey, fileSystem);
    sendResponse(exchange, 200, result.toXML(), "application/xml");
  }

  private void handleGetObject(S3HttpExchange exchange, String bucketName, String key)
      throws IOException, StorageException {
    if (fileSystem.objectNotExists(bucketName, key)) {
      sendError(exchange, 404, "NoSuchKey");
      return;
    }

    fileSystem.getObject(exchange, bucketName, key);
  }

  private void handlePostObject(
      S3HttpExchange exchange, String bucketName, String key, byte[] payload)
      throws IOException, StorageException {
    // Validate bucket existence
    if (!fileSystem.bucketExists(bucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    if (payload == null || payload.length == 0) {
      sendError(exchange, 400, "InvalidArgument");
      return;
    }

    try {
      String eTag = fileSystem.handlePutObject(bucketName, key, payload);

      PostUploadResult postUploadResult = new PostUploadResult(bucketName, key, eTag);

      exchange.getResponseHeaders().addHeader("ETag", "\"" + eTag + "\"");
      sendResponse(exchange, 200, postUploadResult.toXML(), "application/xml");

    } catch (Exception e) {
      e.printStackTrace();
      sendError(exchange, 500, "InternalError");
    }
  }

  private void handlePutObject(
      S3HttpExchange exchange, String bucketName, String key, byte[] payload)
      throws IOException, StorageException {
    if (!fileSystem.bucketExists(bucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    String eTag = fileSystem.handlePutObject(bucketName, key, payload);
    exchange.getResponseHeaders().addHeader("ETag", eTag);
    sendResponse(exchange, 200, "", "application/xml");
  }

  private void handleDeleteObject(S3HttpExchange exchange, String bucketName, String key)
      throws IOException, StorageException {
    if (fileSystem.objectNotExists(bucketName, key)) {
      sendError(exchange, 404, "NoSuchKey");
      return;
    }

    fileSystem.handleDeleteObject(bucketName, key);
    sendResponse(exchange, 204, "", "application/xml");
  }

  private void handlePreSignedUrlGeneration(S3HttpExchange exchange) throws IOException {
    try {
      Map<String, String> params = parseRequestParameters(exchange);
      String method = params.get("method");
      String path = params.get("path");
      String accessKey = params.get("accessKey");
      long expiration = Long.parseLong(params.get("expiration"));

      String presignedUrl =
          generatePreSignedUrl(method, path, accessKey, expiration, exchange.getRequestHeaders());
      sendResponse(exchange, 200, presignedUrl, "text/plain");
    } catch (Exception e) {
      sendError(exchange, 400, "InvalidRequest");
    }
  }

  private String generatePreSignedUrl(
      String method, String path, String accessKey, long expiration, S3HttpHeaders requestHeaders)
      throws NoSuchAlgorithmException, InvalidKeyException {
    String timestamp =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now());

    Map<String, String> queryParams = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    queryParams.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
    queryParams.put(
        "X-Amz-Credential",
        accessKey + "/" + S3Utils.getCredentialScope(timestamp, credentials.get(accessKey)));
    queryParams.put("X-Amz-Date", timestamp);
    queryParams.put("X-Amz-Expires", String.valueOf(expiration));
    queryParams.put("X-Amz-SignedHeaders", "host");

    String canonicalRequest = createCanonicalRequest(method, path, queryParams, requestHeaders);
    String stringToSign =
        S3Utils.createStringToSign(canonicalRequest, timestamp, credentials.get(accessKey));
    String signature =
        S3Utils.calculateSignature(stringToSign, timestamp, credentials.get(accessKey));

    StringBuilder url = new StringBuilder(path).append("?");
    for (Map.Entry<String, String> param : queryParams.entrySet()) {
      url.append(param.getKey()).append("=").append(param.getValue()).append("&");
    }
    url.append("X-Amz-Signature=").append(signature);

    return url.toString();
  }
}
