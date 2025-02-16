package com.tinys3.http;

import com.sun.net.httpserver.HttpExchange;
import java.util.List;
import java.util.Map;

public class S3HttpHeadersAdapter implements S3HttpHeaders {

  private final HttpExchange httpExchange;

  public S3HttpHeadersAdapter(HttpExchange httpExchange) {
    this.httpExchange = httpExchange;
  }

  @Override
  public void addHeader(String key, String value) {
    httpExchange.getResponseHeaders().add(key, value);
  }

  @Override
  public boolean containsHeader(String headerName) {
    return httpExchange.getRequestHeaders().containsKey(headerName);
  }

  @Override
  public String getFirst(String header) {
    return httpExchange.getRequestHeaders().getFirst(header);
  }

  @Override
  public void forEach(HeaderConsumer consumer) {
    httpExchange.getRequestHeaders().forEach(consumer::accept);
  }

  @Override
  public Iterable<? extends Map.Entry<String, List<String>>> entrySet() {
    return httpExchange.getRequestHeaders().entrySet();
  }
}
