package com.tinys3.auth;

import static com.tinys3.S3Utils.parseQueryString;

import com.tinys3.S3ServerVerifier;
import com.tinys3.http.S3HttpExchange;
import com.tinys3.http.S3HttpHeaders;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DefaultAuthenticator implements S3Authenticator {
  private static final String AWS_ALGORITHM = "AWS4-HMAC-SHA256";
  private static final Pattern CREDENTIAL_PATTERN = Pattern.compile("Credential=([^/,]+)");

  private final Map<String, Credentials> credentials;

  public DefaultAuthenticator(Map<String, Credentials> credentials) {
    this.credentials = credentials;
  }

  @Override
  public boolean authenticateRequest(S3HttpExchange exchange, byte[] payload) {
    try {
      Optional<String> accessKey = extractAccessKey(exchange);
      if (accessKey.isEmpty()) {
        return false;
      }

      Credentials userCredentials = credentials.get(accessKey.get());
      if (userCredentials == null) {
        return false;
      }

      return verifySignature(exchange, payload, userCredentials);
    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
  }

  @Override
  public Credentials getCredentials(S3HttpExchange exchange) {
    try {
      Optional<String> accessKey = extractAccessKey(exchange);
      return accessKey.map(credentials::get).orElse(null);
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  private Optional<String> extractAccessKey(S3HttpExchange exchange) {
    S3HttpHeaders headers = exchange.getRequestHeaders();
    Map<String, String> queryParams = parseQueryString(exchange.getRequestURI().getQuery());

    String authHeader = headers.getFirst("Authorization");
    String dateHeader = headers.getFirst("X-Amz-Date");

    // Check if this is a pre-signed URL request
    if (isPreSignedRequest(queryParams)) {
      String credential = queryParams.get("X-Amz-Credential");
      return Optional.ofNullable(credential).map(c -> c.split("/")[0]);
    }

    // Check regular authentication header
    if (authHeader != null) {
      Matcher matcher = CREDENTIAL_PATTERN.matcher(authHeader);
      if (matcher.find() && dateHeader != null) {
        return Optional.of(matcher.group(1));
      }
    }

    return Optional.empty();
  }

  private boolean isPreSignedRequest(Map<String, String> queryParams) {
    return queryParams.containsKey("X-Amz-Algorithm")
        && AWS_ALGORITHM.equals(queryParams.get("X-Amz-Algorithm"));
  }

  private boolean verifySignature(
      S3HttpExchange exchange, byte[] payload, Credentials userCredentials) throws Exception {
    S3HttpHeaders headers = exchange.getRequestHeaders();
    Map<String, String> headerMap = convertHeaders(headers);

    String requestURL = constructRequestURL(exchange);
    String dateHeader = headers.getFirst("X-Amz-Date");
    String authHeader = headers.getFirst("Authorization");

    var verifier = new S3ServerVerifier(userCredentials);
    return verifier.verifyRequest(
        requestURL, exchange.getRequestMethod(), headerMap, dateHeader, authHeader, payload);
  }

  private Map<String, String> convertHeaders(S3HttpHeaders headers) {
    Map<String, String> headerMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (!entry.getValue().isEmpty()) {
        headerMap.put(entry.getKey(), entry.getValue().get(0));
      }
    }
    return headerMap;
  }

  private String constructRequestURL(S3HttpExchange exchange) {
    URI uri = exchange.getRequestURI();
    String host = exchange.getRequestHeaders().getFirst("Host");

    return (host != null) ? String.format("http://%s%s", host, uri) : uri.toString();
  }
}
