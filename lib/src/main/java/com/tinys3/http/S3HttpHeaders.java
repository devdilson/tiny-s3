package com.tinys3.http;

import java.util.List;
import java.util.Map;

public interface S3HttpHeaders {

  void addHeader(String key, String value);

  boolean containsHeader(String headerName);

  String getFirst(String header);

  // Add iteration support
  void forEach(HeaderConsumer consumer);

  Iterable<? extends Map.Entry<String, List<String>>> entrySet();

  // Functional interface for header iteration
  @FunctionalInterface
  interface HeaderConsumer {
    void accept(String headerName, List<String> headerValues);
  }
}
