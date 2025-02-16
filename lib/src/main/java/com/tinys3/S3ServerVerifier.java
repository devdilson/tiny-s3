package com.tinys3;

import com.tinys3.auth.Credentials;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

import static com.tinys3.CanonicalRequest.createCanonicalRequest;
import static com.tinys3.CanonicalRequest.createPresignedCanonicalRequest;
import static com.tinys3.S3Utils.*;

public class S3ServerVerifier {
  private final Credentials credentials;

  public static final Set<String> SIGNED_HEADERS =
      new HashSet<>(Arrays.asList("host", "x-amz-date", "x-amz-security-token"));

  public S3ServerVerifier(Credentials credential) {
    this.credentials = credential;
  }

  public boolean verifyRequest(
      String requestUrl,
      String method,
      Map<String, String> headers,
      String dateHeader,
      String authHeader,
      byte[] payload)
      throws Exception {
    // Check if this is a pre-signed URL request
    if (isPreSignedUrl(requestUrl)) {
      return verifyPreSignedUrl(requestUrl, method);
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

    if (!credentialRegion.equals(credentials.getRegion()) || !"s3".equals(credentialService)) {
      return false;
    }

    String canonicalRequest =
        createCanonicalRequest(method, requestUrl, headers, signedHeaders, payload);
    String stringToSign =
        createStringToSign(dateHeader, credentialDate, credentialRegion, canonicalRequest);
    byte[] signingKey = getSigningKey(credentialDate);
    String calculatedSignature = calculateSignature(stringToSign, signingKey);

    return calculatedSignature.equals(providedSignature);
  }

  private boolean isPreSignedUrl(String requestUrl) {
    return requestUrl.contains("X-Amz-Signature=");
  }

  public boolean verifyPreSignedUrl(String requestUrl, String method) throws Exception {
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

    if (!credentialRegion.equals(credentials.getRegion()) || !"s3".equals(credentialService)) {
      return false;
    }

    if (verifyExpirationDate(date, expires)) {
      return false;
    }

    // Sign with original canonical request
    if (method.equals("PUT")) {
      method = "GET";
    }

    // Create canonical request without the signature
    String canonicalRequest =
        createPresignedCanonicalRequest(method, url, queryParams, signedHeaders);
    String stringToSign =
        createStringToSign(date, credentialDate, credentialRegion, canonicalRequest);
    byte[] signingKey = getSigningKey(credentialDate);
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

  private byte[] getSigningKey(String dateStamp) throws Exception {
    byte[] kSecret = ("AWS4" + credentials.getSecretKey()).getBytes(StandardCharsets.UTF_8);
    byte[] kDate = hmacSHA256(dateStamp, kSecret);
    byte[] kRegion = hmacSHA256(credentials.getRegion(), kDate);
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
