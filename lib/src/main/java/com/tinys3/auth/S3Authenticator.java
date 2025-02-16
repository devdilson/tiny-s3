package com.tinys3.auth;

import com.sun.net.httpserver.HttpExchange;

public interface S3Authenticator {
  boolean authenticateRequest(HttpExchange exchange, byte[] payload);

  Credentials getCredentials(HttpExchange exchange);
}
