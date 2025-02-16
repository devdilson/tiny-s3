package com.tinys3.auth;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.tinys3.S3ServerVerifier;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tinys3.S3Utils.parseQueryString;

public class DefaultAuthenticator implements S3Authenticator {

  private Map<String, Credentials> credentials;

  public DefaultAuthenticator(Map<String, Credentials> credentials) {
    this.credentials = credentials;
  }

  @Override
  public boolean authenticateRequest(HttpExchange exchange, byte[] payload) {
    try {
      Headers headers = exchange.getRequestHeaders();
      String accessKey;
      Map<String, String> queryParams = parseQueryString(exchange.getRequestURI().getQuery());

      String authHeader = headers.getFirst("Authorization");
      String dateHeader = headers.getFirst("X-Amz-Date");
      boolean isPreSigned =
          queryParams.containsKey("X-Amz-Algorithm")
              && queryParams.get("X-Amz-Algorithm").equals("AWS4-HMAC-SHA256");
      if (isPreSigned) {
        accessKey = queryParams.get("X-Amz-Credential").split("/")[0];
      } else {
        Pattern pattern = Pattern.compile("Credential=([^/,]+)");
        Matcher matcher = pattern.matcher(authHeader);
        if (!matcher.find()) {
          return false;
        }
        accessKey = matcher.group(1);
        if (dateHeader == null) {
          return false;
        }
      }

      Map<String, String> headerMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        if (!entry.getValue().isEmpty()) {
          headerMap.put(entry.getKey(), entry.getValue().get(0));
        }
      }

      String requestURL = exchange.getRequestURI().toString();
      String host = headers.getFirst("Host");
      if (host != null) {
        requestURL = "http://" + host + requestURL;
      }

      var verifier =
          new S3ServerVerifier(credentials.get(accessKey));
      return verifier.verifyRequest(
          requestURL, exchange.getRequestMethod(), headerMap, dateHeader, authHeader, payload);

    } catch (Exception e) {
      e.printStackTrace();
      return false;
    }
  }
}
