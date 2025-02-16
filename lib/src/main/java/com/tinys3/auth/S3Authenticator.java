package com.tinys3.auth;

import com.tinys3.http.S3HttpExchange;

public interface S3Authenticator {
  boolean authenticateRequest(S3HttpExchange exchange, byte[] payload);

  Credentials getCredentials(S3HttpExchange exchange);
}
