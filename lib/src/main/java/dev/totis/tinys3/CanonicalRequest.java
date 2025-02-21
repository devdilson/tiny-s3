package dev.totis.tinys3;

import static dev.totis.tinys3.S3ServerVerifier.SIGNED_HEADERS;
import static dev.totis.tinys3.S3Utils.*;

import dev.totis.tinys3.http.S3HttpHeaders;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

public class CanonicalRequest {

  public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

  public static String createCanonicalRequest(
      String method, String path, Map<String, String> queryParams, S3HttpHeaders headers) {
    StringBuilder canonical = new StringBuilder();

    canonical.append(method).append('\n');
    canonical.append(path.startsWith("/") ? path : "/" + path).append('\n');

    List<String> queryParts = new ArrayList<>();
    for (Map.Entry<String, String> param : new TreeMap<>(queryParams).entrySet()) {
      String key = URLEncoder.encode(param.getKey(), StandardCharsets.UTF_8).replace("+", "%20");
      String value =
          URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8).replace("+", "%20");
      queryParts.add(key + "=" + value);
    }
    canonical.append(String.join("&", queryParts)).append('\n');

    TreeMap<String, List<String>> canonicalHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.forEach(
        (key, values) -> {
          String lowerKey = key.toLowerCase();
          List<String> normalized =
              values.stream()
                  .map(v -> v.trim().replaceAll("\\s+", " "))
                  .collect(Collectors.toList());
          canonicalHeaders.put(lowerKey, normalized);
        });

    StringBuilder headerString = new StringBuilder();
    List<String> signedHeaders = new ArrayList<>();

    for (Map.Entry<String, List<String>> header : canonicalHeaders.entrySet()) {
      if (header.getKey().equals("authorization")) continue;
      headerString
          .append(header.getKey())
          .append(":")
          .append(String.join(",", header.getValue()))
          .append('\n');
      signedHeaders.add(header.getKey());
    }
    canonical.append(headerString);
    canonical.append('\n');

    canonical.append(String.join(";", signedHeaders)).append('\n');

    String payloadHash =
        method.equals("GET")
            ? "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            : "UNSIGNED-PAYLOAD";
    canonical.append(payloadHash);

    return canonical.toString();
  }

  public static String createPresignedCanonicalRequest(
      String method, URL url, Map<String, String> queryParams, String signedHeaders)
      throws Exception {
    String canonicalURI = url.getPath();

    // Create canonical query string without the signature
    Map<String, String> sortedParams = new TreeMap<>(queryParams);
    sortedParams.remove("X-Amz-Signature");

    String canonicalQueryString =
        sortedParams.entrySet().stream()
            .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
            .collect(Collectors.joining("&"));

    // Create canonical headers
    StringBuilder canonicalHeaders = new StringBuilder();
    for (String headerName : signedHeaders.split(";")) {
      if (SIGNED_HEADERS.contains(headerName.toLowerCase())) {
        String headerValue;
        if (headerName.equals("host")) {
          headerValue = getCanonicalHost(url);
        } else {
          headerValue = queryParams.getOrDefault("X-Amz-" + headerName, "").trim();
        }
        canonicalHeaders
            .append(headerName.toLowerCase())
            .append(":")
            .append(headerValue)
            .append("\n");
      }
    }

    String payloadHash = "UNSIGNED-PAYLOAD";

    return method
        + "\n"
        + canonicalURI
        + "\n"
        + canonicalQueryString
        + "\n"
        + canonicalHeaders
        + "\n"
        + signedHeaders
        + "\n"
        + payloadHash;
  }

  public static String createCanonicalRequest(
      String method, String url, Map<String, String> headers, String signedHeaders, byte[] payload)
      throws Exception {
    URL parsedUrl = new URL(url);
    String canonicalURI = parsedUrl.getPath();

    // Parse query string and sort it
    String query = parsedUrl.getQuery() != null ? parsedUrl.getQuery() : "";
    Map<String, String> queryParams = parseQueryString(query);
    String canonicalQueryString =
        queryParams.entrySet().stream()
            .sorted(Map.Entry.comparingByKey()) // Sort keys lexicographically
            .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
            .collect(Collectors.joining("&"));

    StringBuilder canonicalHeaders = new StringBuilder();
    for (String headerName : signedHeaders.split(";")) {
      String value = headers.getOrDefault(headerName.toLowerCase(), "").trim();
      if ("Accept-encoding".equalsIgnoreCase(headerName)) {
        // Only include if it's identity
        if ("identity".equals(value)) {
          System.out.println("Header is equal to identity");
          canonicalHeaders
              .append(headerName.toLowerCase())
              .append(":")
              .append("identity")
              .append("\n");
        } else if (headers.get("Cf-ray") != null && value.contains("gzip")) {
          canonicalHeaders
              .append(headerName.toLowerCase())
              .append(":")
              .append("identity")
              .append("\n");
          System.out.println("Found cloudflare header");
        } else {
          System.out.println(
              "Header is not equal to identity " + headers.get("Cf-ray") + " " + value);
        }
        continue;
      }
      // handle cloudflare overriding access

      canonicalHeaders.append(headerName.toLowerCase()).append(":").append(value).append("\n");
    }

    String payloadHash;
    if (isUnsignedPayload(headers)) {
      payloadHash = UNSIGNED_PAYLOAD;
    } else if (payload != null && payload.length > 0) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      payloadHash = bytesToHex(digest.digest(payload));
    } else {
      // Use empty string hash for requests without payload
      payloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    }

    return method
        + "\n"
        + canonicalURI
        + "\n"
        + canonicalQueryString
        + "\n"
        + canonicalHeaders
        + "\n"
        + signedHeaders
        + "\n"
        + payloadHash;
  }

  private static boolean isUnsignedPayload(Map<String, String> headers) {
    String unsignedPayload = headers.get("X-amz-content-sha256");
    return unsignedPayload.equals(UNSIGNED_PAYLOAD);
  }

  private static Map<String, String> parseQueryString(String query) {
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

  private static String getCanonicalHost(URL url) {
    String host = url.getHost().toLowerCase();
    int port = url.getPort();

    // Include port if it's non-standard
    if (port != -1) { // -1 means no port specified
      boolean isStandardPort =
          (url.getProtocol().equals("http") && port == 80)
              || (url.getProtocol().equals("https") && port == 443);
      if (!isStandardPort) {
        host = host + ":" + port;
      }
    }
    return host;
  }
}
