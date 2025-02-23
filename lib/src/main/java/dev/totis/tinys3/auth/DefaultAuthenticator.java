package dev.totis.tinys3.auth;

import static dev.totis.tinys3.CanonicalRequest.createCanonicalRequest;
import static dev.totis.tinys3.CanonicalRequest.createPresignedCanonicalRequest;
import static dev.totis.tinys3.S3Utils.*;
import static dev.totis.tinys3.S3Utils.bytesToHex;

import dev.totis.tinys3.S3Context;
import dev.totis.tinys3.S3Utils;
import dev.totis.tinys3.http.S3HttpExchange;
import dev.totis.tinys3.http.S3HttpHeaders;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DefaultAuthenticator implements S3Authenticator {
  private static final String AWS_ALGORITHM = "AWS4-HMAC-SHA256";
  private static final Pattern CREDENTIAL_PATTERN = Pattern.compile("Credential=([^/,]+)");

  private final Map<String, Credentials> credentials;

  public DefaultAuthenticator(Map<String, Credentials> credentials) {
    this.credentials = credentials;
  }

  @Override
  public boolean authenticateRequest(S3Context context) {
    try {
      Optional<String> accessKey = extractAccessKey(context.getHttpExchange());
      if (accessKey.isEmpty()) {
        return false;
      }

      Credentials userCredentials = credentials.get(accessKey.get());
      if (userCredentials == null) {
        return false;
      }

      return verifySignature(context, userCredentials);
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

  @Override
  public String generatePreSignedUrl(
      String method, String path, String accessKey, long expiration, S3HttpHeaders requestHeaders)
      throws NoSuchAlgorithmException, InvalidKeyException {
    Credentials credential = credentials.get(accessKey);
    return generatePreSignedUrl(method, path, credential, expiration, requestHeaders);
  }

  private Optional<String> extractAccessKey(S3HttpExchange exchange) {
    S3HttpHeaders headers = exchange.getRequestHeaders();
    Map<String, String> queryParams = parseQueryString(exchange.getRequestURI().getQuery());

    String authHeader = headers.getFirst("Authorization");
    String dateHeader = headers.getFirst("X-Amz-Date");

    // Check if this is a pre-signed URL request
    if (isPreSignedRequest(exchange, queryParams)) {
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

  private boolean isPreSignedRequest(S3HttpExchange exchange, Map<String, String> queryParams) {
    String key = "X-Amz-Algorithm";
    if (exchange.getRequestHeaders().containsHeader(key)) {
      return true;
    }
    return AWS_ALGORITHM.equals(queryParams.get(key));
  }

  private boolean verifySignature(S3Context s3Context, Credentials userCredentials)
      throws Exception {
    S3HttpHeaders headers = s3Context.getHttpExchange().getRequestHeaders();
    Map<String, String> headerMap = convertHeaders(headers);

    String requestURL = constructRequestURL(s3Context.getHttpExchange());
    String dateHeader = headers.getFirst("X-Amz-Date");
    String authHeader = headers.getFirst("Authorization");

    return verifyRequest(s3Context, requestURL, headerMap, dateHeader, authHeader, userCredentials);
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
    String isHttps =
        Objects.requireNonNullElse(
            exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"), "http");
    String protocol = isHttps.equals("https") ? "https" : "http";
    return (host != null) ? String.format("%s://%s%s", protocol, host, uri) : uri.toString();
  }

  public boolean verifyRequest(
      S3Context s3Context,
      String requestUrl,
      Map<String, String> headers,
      String dateHeader,
      String authHeader,
      Credentials userCredentials)
      throws Exception {
    // Check if this is a pre-signed URL request
    if (s3Context.isPreSignedUrl(requestUrl)) {
      return verifyPreSignedUrl(requestUrl, s3Context.getMethod(), userCredentials);
    }

    // Original header-based verification
    if (!authHeader.startsWith("AWS4-HMAC-SHA256 ")) {
      return false;
    }

    Map<String, String> authParts = parseAuthHeader(authHeader);
    String credential = authParts.get("Credential");
    String signedHeaders = authParts.get("SignedHeaders");
    String providedSignature = authParts.get("Signature");

    String[] credParts = credential.split("/");
    if (credParts.length < 5) return false;
    String credentialDate = credParts[1];
    String credentialRegion = credParts[2];
    String credentialService = credParts[3];

    if (!credentialRegion.equals(userCredentials.region()) || !"s3".equals(credentialService)) {
      return false;
    }

    String canonicalRequest =
        createCanonicalRequest(
            s3Context.getMethod(), requestUrl, headers, signedHeaders, s3Context.getPayload());
    System.out.println("==== HEADERS ====");
    System.out.println(headers);
    System.out.println("========================");
    System.out.println("==== CANONICAL REQUEST: ");
    System.out.println(canonicalRequest);
    System.out.println("========================");
    String stringToSign =
        createStringToSign(dateHeader, credentialDate, credentialRegion, canonicalRequest);
    byte[] signingKey = getSigningKey(credentialDate, userCredentials);
    String calculatedSignature = calculateSignature(stringToSign, signingKey);

    return calculatedSignature.equals(providedSignature);
  }

  public boolean verifyPreSignedUrl(String requestUrl, String method, Credentials userCredentials)
      throws Exception {
    URL url = new URL(requestUrl);
    Map<String, String> queryParams = parseQueryString(url.getQuery());

    // Extract required parameters
    String algorithm = queryParams.get("X-Amz-Algorithm");
    String credential = queryParams.get("X-Amz-Credential");
    String signedHeaders = queryParams.get("X-Amz-SignedHeaders");
    String signature = queryParams.get("X-Amz-Signature");
    String date = queryParams.get("X-Amz-Date");
    String expires = queryParams.get("X-Amz-Expires");

    // Validate required parameters
    if (algorithm == null
        || credential == null
        || signedHeaders == null
        || signature == null
        || date == null
        || expires == null) {
      return false;
    }

    // Verify algorithm
    if (!"AWS4-HMAC-SHA256".equals(algorithm)) {
      return false;
    }

    // Parse and verify credential
    String[] credParts = credential.split("/");
    if (credParts.length != 5) return false;

    String credentialDate = credParts[1];
    String credentialRegion = credParts[2];
    String credentialService = credParts[3];

    if (!credentialRegion.equals(userCredentials.region()) || !"s3".equals(credentialService)) {
      return false;
    }

    if (verifyExpirationDate(date, expires)) {
      return false;
    }

    // Create canonical request without the signature
    String canonicalRequest =
        createPresignedCanonicalRequest(method, url, queryParams, signedHeaders);
    String stringToSign =
        createStringToSign(date, credentialDate, credentialRegion, canonicalRequest);
    byte[] signingKey = getSigningKey(credentialDate, userCredentials);
    String calculatedSignature = calculateSignature(stringToSign, signingKey);

    return calculatedSignature.equals(signature);
  }

  private Map<String, String> parseQueryString(String query) {
    if (query == null || query.isEmpty()) {
      return new TreeMap<>(); // TreeMap automatically sorts keys lexicographically
    }

    return Arrays.stream(query.split("&"))
        .map(param -> param.split("=", 2))
        .collect(
            Collectors.toMap(
                param -> urlDecode(param[0]),
                param -> param.length > 1 ? urlDecode(param[1]) : "",
                (v1, v2) -> v2,
                TreeMap::new // Ensures sorting
                ));
  }

  public String generatePreSignedUrl(
      String method,
      String path,
      Credentials credentials,
      long expiration,
      S3HttpHeaders requestHeaders)
      throws NoSuchAlgorithmException, InvalidKeyException {
    String timestamp =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now());

    Map<String, String> queryParams = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    queryParams.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
    queryParams.put(
        "X-Amz-Credential",
        credentials.accessKey() + "/" + S3Utils.getCredentialScope(timestamp, credentials));
    queryParams.put("X-Amz-Date", timestamp);
    queryParams.put("X-Amz-Expires", String.valueOf(expiration));
    queryParams.put("X-Amz-SignedHeaders", "host");

    String canonicalRequest = createCanonicalRequest(method, path, queryParams, requestHeaders);
    String stringToSign = S3Utils.createStringToSign(canonicalRequest, timestamp, credentials);
    String signature = S3Utils.calculateSignature(stringToSign, timestamp, credentials);

    StringBuilder url = new StringBuilder(path).append("?");
    for (Map.Entry<String, String> param : queryParams.entrySet()) {
      url.append(param.getKey()).append("=").append(param.getValue()).append("&");
    }
    url.append("X-Amz-Signature=").append(signature);

    return url.toString();
  }

  private Map<String, String> parseAuthHeader(String authHeader) {
    return Arrays.stream(authHeader.substring("AWS4-HMAC-SHA256 ".length()).split(", "))
        .map(kv -> kv.split("="))
        .filter(kv -> kv.length == 2)
        .collect(Collectors.toMap(kv -> kv[0], kv -> kv[1]));
  }

  private String createStringToSign(
      String dateHeader, String credentialDate, String credentialRegion, String canonicalRequest)
      throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));

    return "AWS4-HMAC-SHA256\n"
        + dateHeader
        + "\n"
        + credentialDate
        + "/"
        + credentialRegion
        + "/s3/aws4_request\n"
        + bytesToHex(hash);
  }

  private byte[] getSigningKey(String dateStamp, Credentials credentials) throws Exception {
    byte[] kSecret = ("AWS4" + credentials.secretKey()).getBytes(StandardCharsets.UTF_8);
    byte[] kDate = hmacSHA256(dateStamp, kSecret);
    byte[] kRegion = hmacSHA256(credentials.region(), kDate);
    byte[] kService = hmacSHA256("s3", kRegion);
    return hmacSHA256("aws4_request", kService);
  }

  private String calculateSignature(String stringToSign, byte[] signingKey) throws Exception {
    return bytesToHex(hmacSHA256(stringToSign, signingKey));
  }

  private byte[] hmacSHA256(String data, byte[] key) throws Exception {
    return S3Utils.hmacSHA256(key, data);
  }
}
