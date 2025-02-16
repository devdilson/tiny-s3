package com.tinys3;

import com.tinys3.auth.Credentials;
import com.tinys3.http.S3HttpExchange;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

public class S3Utils {

  public static final DateTimeFormatter LAST_MODIFIED_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  public static byte[] hmacSHA256(byte[] key, String data)
      throws NoSuchAlgorithmException, InvalidKeyException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
  }

  public static String calculateSHA256Hash(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return bytesToHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 algorithm not found", e);
    }
  }

  public static String bytesToHex(byte[] bytes) {
    StringBuilder result = new StringBuilder();
    for (byte b : bytes) {
      result.append(String.format("%02x", b));
    }
    return result.toString();
  }

  static String createStringToSign(
      String canonicalRequest, String amzDate, Credentials credentials) {
    return "AWS4-HMAC-SHA256\n"
        + amzDate
        + '\n'
        + getCredentialScope(amzDate, credentials)
        + '\n'
        + calculateSHA256Hash(canonicalRequest);
  }

  public static String getCredentialScope(String timestamp, Credentials credentials) {
    return String.format("%s/%s/s3/aws4_request", timestamp.substring(0, 8), credentials.region());
  }

  public static String calculateSignature(
      String stringToSign, String timestamp, Credentials credentials)
      throws NoSuchAlgorithmException, InvalidKeyException {
    byte[] kSecret = ("AWS4" + credentials.secretKey()).getBytes(StandardCharsets.UTF_8);
    byte[] kDate = hmacSHA256(kSecret, timestamp.substring(0, 8));
    byte[] kRegion = hmacSHA256(kDate, credentials.region());
    byte[] kService = hmacSHA256(kRegion, "s3");
    byte[] kSigning = hmacSHA256(kService, "aws4_request");
    return bytesToHex(hmacSHA256(kSigning, stringToSign));
  }

  public static String urlEncode(String value) {
    try {
      // First, encode using URLEncoder (which encodes spaces as '+')
      String encoded = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);

      // Replace '+' with '%20' (AWS requires spaces to be encoded as '%20')
      encoded = encoded.replace("+", "%20");

      // Replace "%7E" with "~" (AWS allows '~' to remain unencoded)
      encoded = encoded.replace("%7E", "~");

      // Optionally, ensure other characters like '*' are not double-encoded
      // (URLEncoder.encode already encodes '*' as '%2A', so no need to replace it)

      return encoded;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static String urlDecode(String value) {
    try {
      return URLDecoder.decode(value, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static void sendResponse(
      S3HttpExchange exchange, int code, String response, String contentType) throws IOException {
    byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().addHeader("Content-Type", contentType);
    if ("HEAD".equals(exchange.getRequestMethod())) {
      // For HEAD requests, we send the content length but no body
      exchange.sendResponseHeaders(code, -1);
    } else {
      // For all other requests, send both headers and body
      exchange.sendResponseHeaders(code, responseBytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(responseBytes);
        os.flush();
      }
    }
  }

  public static void sendError(S3HttpExchange exchange, int code, String errorCode)
      throws IOException {
    String response = createErrorResponse(errorCode);
    sendResponse(exchange, code, response, "application/xml");
  }

  public static String createErrorResponse(String errorCode) {
    return String.format(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<Error><Code>%s</Code><Message>%s</Message></Error>",
        errorCode, getErrorMessage(errorCode));
  }

  public static String getErrorMessage(String errorCode) {
    Map<String, String> errorMessages = new HashMap<>();
    errorMessages.put("NoSuchBucket", "The specified bucket does not exist");
    errorMessages.put("BucketAlreadyExists", "The requested bucket name is not available");
    errorMessages.put("NoSuchKey", "The specified key does not exist");
    errorMessages.put(
        "InvalidAccessKeyId", "The AWS access key Id provided does not exist in our records");
    errorMessages.put(
        "SignatureDoesNotMatch",
        "The request signature we calculated does not match the signature you provided");
    errorMessages.put(
        "MethodNotAllowed", "The specified method is not allowed against this resource");
    errorMessages.put("InvalidRequest", "Invalid request parameters");
    errorMessages.put("BucketNotEmpty", "The bucket you tried to delete is not empty");
    errorMessages.put("NoSuchUpload", "The specified multipart upload does not exist");

    return errorMessages.getOrDefault(errorCode, "An error occurred");
  }

  public static Map<String, String> parseRequestParameters(S3HttpExchange exchange)
      throws IOException {
    Map<String, String> params = new HashMap<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String[] parts = line.split("=", 2);
        if (parts.length == 2) {
          params.put(parts[0].trim(), parts[1].trim());
        }
      }
    }
    return params;
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

  public static boolean verifyExpirationDate(String date, String expires) {
    try {
      // AWS uses ISO 8601 format: yyyyMMdd'T'HHmmss'Z'
      SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
      dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
      Date signedDate = dateFormat.parse(date);

      // Calculate expiration time
      long expiresIn = Long.parseLong(expires);
      long expirationTime = signedDate.getTime() + (expiresIn * 1000);
      long currentTime = System.currentTimeMillis();

      // Check if URL has expired
      if (currentTime > expirationTime) {
        return true;
      }
    } catch (ParseException e) {
      return true;
    }
    return false;
  }

  public static XMLStreamWriter createXMLStreamWriter(Writer writer) {
    try {
      XMLOutputFactory factory = XMLOutputFactory.newInstance();
      return factory.createXMLStreamWriter(writer);
    } catch (XMLStreamException e) {
      throw new RuntimeException("Failed to create XML writer", e);
    }
  }

  public static String calculateETag(Path objectPath, boolean isMultipart, List<String> partETags) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      if (!isMultipart) {
        // Single upload ETag
        byte[] hash = md.digest(Files.readAllBytes(objectPath));
        return "\"" + Base64.getEncoder().encodeToString(hash) + "\"";
      } else {
        // Multipart upload ETag
        for (String partETag : partETags) {
          // Remove quotes from part ETags
          String cleanPartETag = partETag.replaceAll("\"", "");
          md.update(Base64.getDecoder().decode(cleanPartETag));
        }
        String finalHash = Base64.getEncoder().encodeToString(md.digest());
        return "\"" + finalHash + "-" + partETags.size() + "\"";
      }
    } catch (Exception e) {
      return "\"dummy-etag\"";
    }
  }

  public static byte[] copyPayload(S3HttpExchange exchange) throws IOException {
    byte[] payload;
    InputStream inputStream = exchange.getRequestBody();
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    int read = 0;
    byte[] buff = new byte[1024];
    while ((read = inputStream.read(buff)) != -1) {
      bos.write(buff, 0, read);
    }
    payload = bos.toByteArray();
    return payload;
  }
}
