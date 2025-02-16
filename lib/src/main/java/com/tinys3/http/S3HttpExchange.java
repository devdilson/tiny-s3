package com.tinys3.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

public interface S3HttpExchange {
  // Request related methods
  String getRequestMethod();

  URI getRequestURI();

  S3HttpHeaders getRequestHeaders();

  InputStream getRequestBody();

  // Response related methods
  S3HttpHeaders getResponseHeaders();

  OutputStream getResponseBody();

  void sendResponseHeaders(int rCode, long responseLength) throws IOException;

  void close();
}
