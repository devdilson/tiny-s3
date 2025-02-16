package com.tinys3;

import static com.tinys3.CanonicalRequest.createCanonicalRequest;
import static com.tinys3.S3Utils.*;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.tinys3.auth.Credentials;
import com.tinys3.auth.S3Authenticator;
import com.tinys3.response.CopyObjectResult;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class S3Handler implements HttpHandler {
  private final Map<String, Credentials> credentials;
  private final String storagePath;
  private final S3Authenticator authenticator;
  private final DefaultS3FileOperations fileSystem;

  public S3Handler(
      Map<String, Credentials> credentials, String storagePath, S3Authenticator authenticator) {
    this.credentials = credentials;
    this.storagePath = storagePath;
    this.authenticator = authenticator;
    this.fileSystem = new DefaultS3FileOperations(storagePath);
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      byte[] payload = null;
      if (exchange.getRequestBody() != null) {
        payload = copyPayload(exchange);
      }
      if (!authenticator.authenticateRequest(exchange, payload)) {
        sendError(exchange, 403, "InvalidAccessKeyId");
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
        handleListBuckets(exchange);
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
      HttpExchange exchange, String bucketName, String key, String method) throws IOException {
    if (method.equals("POST")) {
      var response = fileSystem.getInitiateMultipartUploadResult(bucketName, key);
      sendResponse(exchange, 200, response.toXML(), "application/xml");
    }
  }

  private void handleMultipartOperation(
      HttpExchange exchange,
      String bucketName,
      String key,
      String method,
      String query,
      byte[] payload)
      throws IOException {
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
      HttpExchange exchange, String uploadId, Map<String, String> queryParams, byte[] payload)
      throws IOException {
    String eTag = fileSystem.handleUploadPart(uploadId, queryParams, payload);

    exchange.getResponseHeaders().set("ETag", "\"" + eTag + "\"");
    exchange.sendResponseHeaders(200, -1);
    exchange.getResponseBody().close();
  }

  private void handleCompleteMultipartUpload(
      HttpExchange exchange, String bucketName, String key, String uploadId) throws IOException {

    try {
      var result = fileSystem.getCompleteMultipartUploadResult(bucketName, key, uploadId);
      sendResponse(exchange, 200, result.toXML(), "application/xml");
    } catch (Exception e) {
      sendError(exchange, 500, "InternalError");
    }
  }

  private void handleAbortMultipartUpload(HttpExchange exchange, String uploadId)
      throws IOException {
    fileSystem.handleAbortMultipartUpload(uploadId);
    exchange.sendResponseHeaders(204, -1);
    exchange.getResponseBody().close();
  }

  private void handleListBuckets(HttpExchange exchange) throws IOException {
    var result = fileSystem.getListAllBucketsResult();
    sendResponse(exchange, 200, result.toXML(), "application/xml");
  }

  private void handleBucketOperation(HttpExchange exchange, String bucketName, String method)
      throws IOException {
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
      HttpExchange exchange, String bucketName, String objectKey, byte[] payload)
      throws IOException {
    if (!fileSystem.bucketExists(bucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    if (fileSystem.objectExists(bucketName, objectKey)) {
      sendError(exchange, 404, "NoSuchKey");
      return;
    }

    Headers responseHeaders = exchange.getResponseHeaders();
    responseHeaders.set("Content-Type", "application/octet-stream");
    responseHeaders.set(
        "Content-Length", String.valueOf(fileSystem.getSize(bucketName, objectKey)));
    var lastModified = fileSystem.getLastModifiedTime(bucketName, objectKey);
    String lastModifiedStr =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'")
            .withZone(ZoneOffset.UTC)
            .withLocale(Locale.US)
            .format(lastModified.toInstant());
    responseHeaders.set("Last-Modified", lastModifiedStr);
    responseHeaders.set(
        "ETag", "\"" + fileSystem.calculateETag(bucketName, objectKey, false, List.of()) + "\"");

    exchange.sendResponseHeaders(200, -1);
  }

  private void handleListObjects(HttpExchange exchange, String bucketName) throws IOException {
    Path bucketPath = Paths.get(storagePath, bucketName);
    if (!fileSystem.bucketExists(bucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    var result = fileSystem.getBucketListResult(exchange, bucketName, bucketPath);

    sendResponse(exchange, 200, result.toXML(), "application/xml");
  }

  private void handleCreateBucket(HttpExchange exchange, String bucketName) throws IOException {
    Path bucketPath = Paths.get(storagePath, bucketName);

    if (fileSystem.bucketExists(bucketName)) {
      sendError(exchange, 409, "BucketAlreadyExists");
      return;
    }

    fileSystem.createDirectory(bucketPath);
    sendResponse(exchange, 200, "", "application/xml");
  }

  private void handleDeleteBucket(HttpExchange exchange, String bucketName) throws IOException {
    Path bucketPath = Paths.get(storagePath, bucketName);

    if (!fileSystem.bucketExists(bucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    if (fileSystem.bucketHasFiles(bucketPath)) {
      sendError(exchange, 409, "BucketNotEmpty");
      return;
    }

    fileSystem.handleDeleteObject(bucketName, "");
    sendResponse(exchange, 204, "", "application/xml");
  }

  private void handleObjectOperation(
      HttpExchange exchange, String bucketName, String key, String method, byte[] payload)
      throws IOException {

    switch (method) {
      case "GET":
        handleGetObject(exchange, bucketName, key);
        break;
      case "HEAD":
        handleHeadObject(exchange, bucketName, key, payload);
        break;
      case "PUT":
        if (exchange.getRequestHeaders().containsKey("x-amz-copy-source")) {
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

  private void handleCopyObject(HttpExchange exchange, String destBucketName, String destBucketKey)
      throws IOException {
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

    String sourceBucket = sourceParts[0];
    String sourceKey = sourceParts[1];

    if (fileSystem.objectExists(sourceBucket, sourceKey)) {
      sendError(exchange, 404, "NoSuchKey");
      return;
    }

    if (fileSystem.objectExists(destBucketName, destBucketKey)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    var result = new CopyObjectResult(destBucketName, destBucketKey, fileSystem);
    sendResponse(exchange, 200, result.toXML(), "application/xml");
  }

  private void handleGetObject(HttpExchange exchange, String bucketName, String key)
      throws IOException {
    if (fileSystem.objectExists(bucketName, key)) {
      sendError(exchange, 404, "NoSuchKey");
      return;
    }

    fileSystem.getObject(exchange, bucketName, key);
  }

  private void handlePutObject(HttpExchange exchange, String bucketName, String key, byte[] payload)
      throws IOException {
    if (!fileSystem.bucketExists(bucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    String eTag = fileSystem.handlePutObject(bucketName, key, payload);
    exchange.getResponseHeaders().set("ETag", eTag);
    sendResponse(exchange, 200, "", "application/xml");
  }

  private void handleDeleteObject(HttpExchange exchange, String bucketName, String key)
      throws IOException {
    if (fileSystem.objectExists(bucketName, key)) {
      sendError(exchange, 404, "NoSuchKey");
      return;
    }

    fileSystem.handleDeleteObject(bucketName, key);
    sendResponse(exchange, 204, "", "application/xml");
  }

  private void handlePreSignedUrlGeneration(HttpExchange exchange) throws IOException {
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
      String method, String path, String accessKey, long expiration, Headers requestHeaders)
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
