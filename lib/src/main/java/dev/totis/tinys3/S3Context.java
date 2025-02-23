package dev.totis.tinys3;

import dev.totis.tinys3.http.S3HttpExchange;
import dev.totis.tinys3.http.S3HttpHeaders;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static dev.totis.tinys3.S3Utils.copyPayload;

public class S3Context {

  private final Map<String, String> headers = new HashMap<>();
  private final Map<String, String> queriesParams = new HashMap<>();
  private String contentType;
  private String path;
  private String method;
  private String query;
  private S3HttpExchange httpExchange;
  private byte [] payload;

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
        && !S3ServerVerifier.isPreSignedUrl(httpExchange.getRequestURI().toString());
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

}
