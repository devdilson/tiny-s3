package dev.totis.tinys3;

import static dev.totis.tinys3.S3Utils.copyPayload;
import static dev.totis.tinys3.S3Utils.createErrorResponse;

import dev.totis.tinys3.http.S3HttpExchange;
import dev.totis.tinys3.http.S3HttpHeaders;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class S3Context {

  private final Map<String, String> headers = new HashMap<>();
  private Map<String, String> queriesParams = new HashMap<>();
  private Map<String, String> requestParams = new HashMap<>();
  private String contentType;
  private String path;
  private String method;
  private String query;
  private S3HttpExchange httpExchange;
  private byte[] payload;
  private String bucketName;
  private String objectKey;

  public static S3Context create(S3HttpExchange exchange) throws IOException {
    S3Context s3Context = new S3Context();
    S3HttpHeaders requestHeaders = exchange.getRequestHeaders();
    for (var header : requestHeaders.entrySet()) {
      if (header.getKey().toLowerCase().startsWith("x-amz")) {
        s3Context.headers.put(header.getKey(), header.getValue().getFirst());
      }
    }
    s3Context.contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    s3Context.method = exchange.getRequestMethod();
    s3Context.path = exchange.getRequestURI().getPath();
    s3Context.query = Objects.requireNonNullElse(exchange.getRequestURI().getQuery(), "");
    s3Context.httpExchange = exchange;
    s3Context.queriesParams = parseQueryString(s3Context.query);
    s3Context.requestParams = parseQueryString(s3Context.query);

    if (exchange.getRequestBody() != null) {
      s3Context.payload = copyPayload(exchange);
    }

    return s3Context;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public byte[] getPayload() {
    return payload;
  }

  public String getContentType() {
    return contentType;
  }

  public String getPath() {
    return path;
  }

  public String getMethod() {
    return method;
  }

  public String getQuery() {
    return query;
  }

  public S3HttpExchange getHttpExchange() {
    return httpExchange;
  }

  public boolean isPostBucketUpload() {
    return contentType != null
        && contentType.contains("multipart/form-data")
        && method.equals("POST");
  }

  public boolean isFrontendTesting() {
    String userAgent = httpExchange.getRequestHeaders().getFirst("User-Agent");
    return userAgent != null
        && userAgent.contains("Mozilla")
        && !httpExchange.getRequestHeaders().containsHeader("X-amz-date")
        && !isPreSignedUrl(httpExchange.getRequestURI().toString());
  }

  public boolean isPreSignedUrl(String requestUrl) {
    return requestUrl.contains("X-Amz-Algorithm=");
  }

  public boolean isPresignedUrlGeneration() {
    return method.equals("POST") && query.contains("presigned-url");
  }

  public boolean isListBucketsRequest() {
    return path.equals("/") && method.equals("GET");
  }

  public Map<String, String> getQueriesParams() {
    return queriesParams;
  }

  public void sendResponse(int code, String response, String contentType) throws IOException {
    S3Utils.sendResponse(httpExchange, code, response, contentType);
  }

  public void sendError(int code, String errorCode) throws IOException {
    String response = createErrorResponse(errorCode);
    sendResponse(code, response, "application/xml");
  }

  public String getBucketName() {
    return bucketName;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public void setBucketName(String bucketName) {
    this.bucketName = bucketName;
  }

  public void setObjectKey(String objectKey) {
    this.objectKey = objectKey;
  }

  public Map<String, String> getRequestParams() {
    return requestParams;
  }

  public static Map<String, String> parseQueryString(String query) {
    Map<String, String> params = new HashMap<>();
    if (query != null) {
      for (String param : query.split("&")) {
        String[] parts = param.split("=", 2);
        if (parts.length == 2) {
          params.put(parts[0], parts[1]);
        }
      }
    }
    return params;
  }
}
