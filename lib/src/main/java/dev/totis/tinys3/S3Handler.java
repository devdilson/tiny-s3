package dev.totis.tinys3;

import static dev.totis.tinys3.S3Utils.*;

import dev.totis.tinys3.auth.Credentials;
import dev.totis.tinys3.auth.S3Authenticator;
import dev.totis.tinys3.frontend.BadFrontend;
import dev.totis.tinys3.http.S3HttpExchange;
import dev.totis.tinys3.http.S3HttpHeaders;
import dev.totis.tinys3.io.StorageException;
import dev.totis.tinys3.logging.S3Logger;
import dev.totis.tinys3.response.CopyObjectResult;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class S3Handler {
  private final Map<String, Credentials> credentials;
  private final S3Authenticator authenticator;
  private final S3FileOperations fileOperations;
  private final String host;

  public S3Handler(
      String host,
      Map<String, Credentials> credentials,
      S3Authenticator authenticator,
      S3FileOperations fileOperations) {
    this.host = host;
    this.credentials = credentials;
    this.authenticator = authenticator;
    this.fileOperations = fileOperations;
  }

  public void handle(S3Context s3Context, S3HttpExchange exchange) throws IOException {
    try {

      if (s3Context.isFrontendTesting()) {
        sendResponse(
            exchange,
            200,
            BadFrontend.FRONTEND.replace("http://localhost:8000", host),
            "text/html");
        return;
      }
      if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
        sendResponse(exchange, 200, "", "");
        return;
      }

      if (s3Context.isPostBucketUpload()) {
        BucketUploadPostHandler policy = new BucketUploadPostHandler(authenticator, fileOperations);
        policy.handle(s3Context);
        return;
      }

      if (!authenticator.authenticateRequest(exchange, s3Context.getPayload())) {
        s3Context.sendError(403, "XAmzContentSHA256Mismatch");
        return;
      }

      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();
      String query = Objects.requireNonNullElse(exchange.getRequestURI().getQuery(), "");

      if (s3Context.isPresignedUrlGeneration()) {
        handlePreSignedUrlGeneration(exchange);
        return;
      }

      if (s3Context.isListBucketsRequest()) {
        handleListBuckets(exchange, authenticator.getCredentials(exchange));
        return;
      }

      String[] pathParts = path.split("/", 3);
      String bucketName = pathParts.length > 1 ? pathParts[1] : "";
      String objectKey = pathParts.length > 2 ? pathParts[2] : "";

      s3Context.setBucketName(bucketName);
      s3Context.setObjectKey(objectKey);

      if (query.contains("uploads")) {
        handleMultipartUpload(s3Context);
        return;
      } else if (query.contains("uploadId")) {
        handleMultipartOperation(s3Context);
        return;
      } else if (objectKey.isEmpty()) {
        handleBucketOperation(exchange, bucketName, method);
        return;
      }

      handleObjectOperation(exchange, bucketName, objectKey, method, s3Context.getPayload());

    } catch (Exception e) {
      S3Logger.getInstance().log("Could not process request" + e);
      s3Context.sendError(500, "InternalError");
    }
  }

  private void handleMultipartUpload(S3Context s3Context) throws IOException {
    var response =
        fileOperations.getInitiateMultipartUploadResult(
            s3Context.getBucketName(), s3Context.getObjectKey());
    s3Context.sendResponse(200, response.toXML(), "application/xml");
  }

  private void handleMultipartOperation(S3Context s3Context) throws IOException, StorageException {
    Map<String, String> queryParams = s3Context.getQueriesParams();
    String uploadId = queryParams.get("uploadId");

    if (!fileOperations.containsKey(uploadId)) {
      s3Context.sendError(404, "NoSuchUpload");
      return;
    }

    switch (s3Context.getMethod()) {
      case "PUT" -> handleUploadPart(s3Context, uploadId);
      case "POST" -> handleCompleteMultipartUpload(s3Context, uploadId);
      case "DELETE" -> handleAbortMultipartUpload(s3Context, uploadId);
    }
  }

  private void handleUploadPart(S3Context s3Context, String uploadId)
      throws IOException, StorageException {
    String eTag =
        fileOperations.handleUploadPart(
            uploadId, s3Context.getQueriesParams(), s3Context.getPayload());
    s3Context.getHttpExchange().getResponseHeaders().addHeader("ETag", "\"" + eTag + "\"");
    s3Context.sendResponse(200, "", "");
  }

  private void handleCompleteMultipartUpload(S3Context s3Context, String uploadId)
      throws IOException {
    try {
      var result =
          fileOperations.getCompleteMultipartUploadResult(
              s3Context.getBucketName(), s3Context.getObjectKey(), uploadId);
      s3Context.sendResponse(200, result.toXML(), "application/xml");
    } catch (Exception e) {
      s3Context.sendError(500, "InternalError");
    }
  }

  private void handleAbortMultipartUpload(S3Context s3Context, String uploadId)
      throws IOException, StorageException {
    fileOperations.handleAbortMultipartUpload(uploadId);
    s3Context.sendResponse(204, "", "application/xml");
  }

  private void handleListBuckets(S3HttpExchange exchange, Credentials credentials)
      throws IOException, StorageException {
    var result = fileOperations.getListAllBucketsResult(credentials.accessKey());
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
    if (!fileOperations.bucketExists(bucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    if (fileOperations.objectNotExists(bucketName, objectKey) && !objectKey.isEmpty()) {
      sendError(exchange, 404, "NoSuchKey");
      return;
    }

    S3HttpHeaders responseHeaders = exchange.getResponseHeaders();
    responseHeaders.addHeader("Content-Type", "application/octet-stream");
    responseHeaders.addHeader(
        "Content-Length", String.valueOf(fileOperations.getSize(bucketName, objectKey)));
    var lastModified = fileOperations.getLastModifiedTime(bucketName, objectKey);
    String lastModifiedStr =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'")
            .withZone(ZoneOffset.UTC)
            .withLocale(Locale.US)
            .format(lastModified.toInstant());
    responseHeaders.addHeader("Last-Modified", lastModifiedStr);
    responseHeaders.addHeader(
        "ETag",
        "\"" + fileOperations.calculateETag(bucketName, objectKey, false, List.of()) + "\"");

    exchange.sendResponseHeaders(200, -1);
  }

  private void handleListObjects(S3HttpExchange exchange, String bucketName)
      throws IOException, StorageException {
    if (!fileOperations.bucketExists(bucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    var result = fileOperations.getBucketListResult(exchange, bucketName);

    sendResponse(exchange, 200, result.toXML(), "application/xml");
  }

  private void handleCreateBucket(S3HttpExchange exchange, String bucketName)
      throws IOException, StorageException {

    if (fileOperations.bucketExists(bucketName)) {
      sendError(exchange, 409, "BucketAlreadyExists");
      return;
    }

    fileOperations.createDirectory(bucketName);
    sendResponse(exchange, 200, "", "application/xml");
  }

  private void handleDeleteBucket(S3HttpExchange exchange, String bucketName)
      throws IOException, StorageException {

    if (!fileOperations.bucketExists(bucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    if (fileOperations.bucketHasFiles(bucketName)) {
      sendError(exchange, 409, "BucketNotEmpty");
      return;
    }

    fileOperations.handleDeleteObject(bucketName, "");
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

    if (fileOperations.objectNotExists(sourceBucketName, sourceKey)) {
      sendError(exchange, 404, "NoSuchKey");
      return;
    }

    if (!fileOperations.bucketExists(destBucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    fileOperations.copyObject(sourceBucketName, sourceKey, destBucketName, destKey);

    var result = new CopyObjectResult(destBucketName, destKey, fileOperations);
    sendResponse(exchange, 200, result.toXML(), "application/xml");
  }

  private void handleGetObject(S3HttpExchange exchange, String bucketName, String key)
      throws IOException, StorageException {
    if (fileOperations.objectNotExists(bucketName, key)) {
      sendError(exchange, 404, "NoSuchKey");
      return;
    }

    fileOperations.getObject(exchange, bucketName, key);
  }

  private void handlePutObject(
      S3HttpExchange exchange, String bucketName, String key, byte[] payload)
      throws IOException, StorageException {
    if (!fileOperations.bucketExists(bucketName)) {
      sendError(exchange, 404, "NoSuchBucket");
      return;
    }

    String eTag = fileOperations.handlePutObject(bucketName, key, payload);
    exchange.getResponseHeaders().addHeader("ETag", eTag);
    sendResponse(exchange, 200, "", "application/xml");
  }

  private void handleDeleteObject(S3HttpExchange exchange, String bucketName, String key)
      throws IOException, StorageException {
    if (fileOperations.objectNotExists(bucketName, key)) {
      sendError(exchange, 404, "NoSuchKey");
      return;
    }

    fileOperations.handleDeleteObject(bucketName, key);
    sendResponse(exchange, 204, "", "application/xml");
  }

  private void handlePreSignedUrlGeneration(S3HttpExchange exchange) throws IOException {
    try {
      Map<String, String> params = parseRequestParameters(exchange);
      String method = params.get("method");
      String path = params.get("path");
      String accessKey = params.get("accessKey");
      long expiration = Long.parseLong(params.get("expiration"));

      String preSignedUrl =
          authenticator.generatePreSignedUrl(
              method, path, accessKey, expiration, exchange.getRequestHeaders());
      sendResponse(exchange, 200, preSignedUrl, "text/plain");
    } catch (Exception e) {
      sendError(exchange, 400, "InvalidRequest");
    }
  }
}
