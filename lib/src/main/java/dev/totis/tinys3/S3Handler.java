package dev.totis.tinys3;

import static dev.totis.tinys3.S3Utils.*;

import dev.totis.tinys3.auth.Credentials;
import dev.totis.tinys3.auth.S3Authenticator;
import dev.totis.tinys3.frontend.BadFrontend;
import dev.totis.tinys3.http.S3HttpExchange;
import dev.totis.tinys3.http.S3HttpHeaders;
import dev.totis.tinys3.io.StorageException;
import dev.totis.tinys3.response.CopyObjectResult;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class S3Handler {

  private static final Logger LOGGER = LoggerFactory.getLogger(S3Handler.class);

  private final S3Authenticator authenticator;
  private final S3FileOperations fileOperations;
  private final String baseURL;

  public S3Handler(String baseURL, S3Authenticator authenticator, S3FileOperations fileOperations) {
    this.baseURL = baseURL;
    this.authenticator = authenticator;
    this.fileOperations = fileOperations;
  }

  public void handle(S3Context s3Context, S3HttpExchange exchange) throws IOException {
    try {

      if (s3Context.isFrontendTesting()) {
        sendResponse(
            exchange,
            200,
            BadFrontend.FRONTEND.replace("http://localhost:8000", baseURL),
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

      if (!authenticator.authenticateRequest(s3Context)) {
        s3Context.sendError(403, "XAmzContentSHA256Mismatch");
        return;
      }

      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();
      String query = Objects.requireNonNullElse(exchange.getRequestURI().getQuery(), "");

      if (s3Context.isPreSignedUrlGeneration()) {
        handlePreSignedUrlGeneration(s3Context);
        return;
      }

      if (s3Context.isListBucketsRequest()) {
        handleListBuckets(s3Context, authenticator.getCredentials(exchange));
        return;
      }

      String[] pathParts = path.split("/", 3);
      String bucketName = pathParts.length > 1 ? pathParts[1] : "";
      String objectKey = pathParts.length > 2 ? pathParts[2] : "";

      if (!S3Utils.isValidPath(bucketName) || !S3Utils.isValidPath(objectKey)) {
        s3Context.sendError(400, "InvalidRequest");
      }

      s3Context.setBucketName(bucketName);
      s3Context.setObjectKey(objectKey);

      if (query.contains("uploads")) {
        handleMultipartUpload(s3Context);
        return;
      } else if (query.contains("uploadId")) {
        handleMultipartOperation(s3Context);
        return;
      } else if (objectKey.isEmpty()) {
        handleBucketOperation(s3Context);
        return;
      }

      handleObjectOperation(s3Context, bucketName, objectKey, method, s3Context.getPayload());

    } catch (Exception e) {
      LOGGER.info("Could not process request", e);
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

  private void handleListBuckets(S3Context s3Context, Credentials credentials)
      throws IOException, StorageException {
    var result = fileOperations.getListAllBucketsResult(credentials.accessKey());
    s3Context.sendResponse(200, result.toXML(), "application/xml");
  }

  private void handleBucketOperation(S3Context s3Context) throws IOException, StorageException {
    if ("delete".equals(s3Context.getQuery())) {
      DeleteObjectsPostHandler handler = new DeleteObjectsPostHandler(fileOperations);
      handler.handleDeleteObjects(s3Context);
      return;
    }
    switch (s3Context.getMethod()) {
      case "HEAD":
        handleHeadObject(s3Context);
        break;
      case "GET":
        handleListObjects(s3Context);
        break;
      case "PUT":
        handleCreateBucket(s3Context);
        break;
      case "DELETE":
        handleDeleteBucket(s3Context);
        break;
      default:
        s3Context.sendError(405, "MethodNotAllowed");
    }
  }

  private void handleHeadObject(S3Context s3Context) throws IOException, StorageException {
    String bucketName = s3Context.getBucketName();
    String objectKey = s3Context.getObjectKey();
    if (!fileOperations.bucketExists(bucketName)) {
      s3Context.sendError(404, "NoSuchBucket");
      return;
    }

    if (fileOperations.objectNotExists(bucketName, objectKey) && !objectKey.isEmpty()) {
      s3Context.sendError(404, "NoSuchKey");
      return;
    }

    S3HttpHeaders responseHeaders = s3Context.getHttpExchange().getResponseHeaders();
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

    s3Context.sendResponse(200, "", "application/octet-stream");
  }

  private void handleListObjects(S3Context s3Context) throws IOException, StorageException {
    if (!fileOperations.bucketExists(s3Context.getBucketName())) {
      s3Context.sendError(404, "NoSuchBucket");
      return;
    }

    var result = fileOperations.getBucketListResult(s3Context, s3Context.getBucketName());

    s3Context.sendResponse(200, result.toXML(), "application/xml");
  }

  private void handleCreateBucket(S3Context s3Context) throws IOException, StorageException {
    String bucketName = s3Context.getBucketName();
    if (fileOperations.bucketExists(bucketName)) {
      s3Context.sendError(409, "BucketAlreadyExists");
      return;
    }

    fileOperations.createDirectory(bucketName);
    s3Context.sendResponse(200, "", "application/xml");
  }

  private void handleDeleteBucket(S3Context s3Context) throws IOException, StorageException {
    String bucketName = s3Context.getBucketName();
    if (!fileOperations.bucketExists(bucketName)) {
      s3Context.sendError(404, "NoSuchBucket");
      return;
    }

    if (fileOperations.bucketHasFiles(bucketName)) {
      s3Context.sendError(409, "BucketNotEmpty");
      return;
    }

    fileOperations.handleDeleteObject(bucketName, "");
    s3Context.sendResponse(204, "", "application/xml");
  }

  private void handleObjectOperation(
      S3Context s3Context, String bucketName, String key, String method, byte[] payload)
      throws IOException, StorageException {

    switch (method) {
      case "GET":
        handleGetObject(s3Context);
        break;
      case "HEAD":
        handleHeadObject(s3Context);
        break;
      case "PUT":
        if (s3Context.getHttpExchange().getResponseHeaders().containsHeader("x-amz-copy-source")) {
          handleCopyObject(s3Context, bucketName, key);
        } else {
          handlePutObject(s3Context);
        }
        break;
      case "DELETE":
        handleDeleteObject(s3Context);
        break;
      default:
        s3Context.sendError(405, "MethodNotAllowed");
    }
  }

  private void handleCopyObject(S3Context s3Context, String destBucketName, String destKey)
      throws IOException, StorageException {
    String copySource =
        s3Context.getHttpExchange().getRequestHeaders().getFirst("x-amz-copy-source");
    if (copySource == null) {
      s3Context.sendError(400, "MissingParameter");
      return;
    }

    if (copySource.startsWith("/")) {
      copySource = copySource.substring(1);
    }
    String[] sourceParts = copySource.split("/", 2);
    if (sourceParts.length != 2) {
      s3Context.sendError(400, "InvalidRequest");
      return;
    }

    String sourceBucketName = sourceParts[0];
    String sourceKey = sourceParts[1];

    if (fileOperations.objectNotExists(sourceBucketName, sourceKey)) {
      s3Context.sendError(404, "NoSuchKey");
      return;
    }

    if (!fileOperations.bucketExists(destBucketName)) {
      s3Context.sendError(404, "NoSuchBucket");
      return;
    }

    fileOperations.copyObject(sourceBucketName, sourceKey, destBucketName, destKey);

    var result = new CopyObjectResult(destBucketName, destKey, fileOperations);
    s3Context.sendResponse(200, result.toXML(), "application/xml");
  }

  private void handleGetObject(S3Context s3Context) throws IOException, StorageException {
    if (fileOperations.objectNotExists(s3Context.getBucketName(), s3Context.getObjectKey())) {
      s3Context.sendError(404, "NoSuchKey");
      return;
    }

    fileOperations.getObject(
        s3Context.getHttpExchange(), s3Context.getBucketName(), s3Context.getObjectKey());
  }

  private void handlePutObject(S3Context s3Context) throws IOException, StorageException {
    String bucketName = s3Context.getBucketName();
    String objectKey = s3Context.getObjectKey();
    byte[] payload = s3Context.getPayload();
    if (!fileOperations.bucketExists(bucketName)) {
      s3Context.sendError(404, "NoSuchBucket");
      return;
    }

    String eTag = fileOperations.handlePutObject(bucketName, objectKey, payload);
    s3Context.getHttpExchange().getResponseHeaders().addHeader("ETag", eTag);
    s3Context.sendResponse(200, "", "application/xml");
  }

  private void handleDeleteObject(S3Context s3Context) throws IOException, StorageException {
    String bucketName = s3Context.getBucketName();
    String objectKey = s3Context.getObjectKey();
    if (fileOperations.objectNotExists(bucketName, objectKey)) {
      s3Context.sendError(404, "NoSuchKey");
      return;
    }

    fileOperations.handleDeleteObject(bucketName, objectKey);
    s3Context.sendResponse(204, "", "application/xml");
  }

  private void handlePreSignedUrlGeneration(S3Context s3Context) throws IOException {
    try {
      Map<String, String> params = s3Context.getRequestParams();
      String method = params.get("method");
      String path = params.get("path");
      String accessKey = params.get("accessKey");
      long expiration = Long.parseLong(params.get("expiration"));

      String preSignedUrl =
          authenticator.generatePreSignedUrl(
              method, path, accessKey, expiration, s3Context.getHttpExchange().getRequestHeaders());
      s3Context.sendResponse(200, preSignedUrl, "text/plain");
    } catch (Exception e) {
      s3Context.sendError(400, "InvalidRequest");
    }
  }
}
