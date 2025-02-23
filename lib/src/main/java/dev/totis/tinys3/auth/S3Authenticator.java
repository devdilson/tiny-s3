package dev.totis.tinys3.auth;

import dev.totis.tinys3.S3Context;
import dev.totis.tinys3.http.S3HttpExchange;
import dev.totis.tinys3.http.S3HttpHeaders;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

public interface S3Authenticator {
  boolean authenticateRequest(S3Context s3Context);

  Credentials getCredentials(S3HttpExchange exchange);

  String generatePreSignedUrl(
      String method, String path, String accessKey, long expiration, S3HttpHeaders requestHeaders)
      throws NoSuchAlgorithmException, InvalidKeyException;
}
