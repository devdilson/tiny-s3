package com.tinys3.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

public class HttpExchangeAdapter implements S3HttpExchange {
  private final HttpExchange exchange;
  private final S3HttpHeadersAdapter headersAdapter;

  public HttpExchangeAdapter(HttpExchange exchange) {
    this.exchange = exchange;
    this.headersAdapter = new S3HttpHeadersAdapter(exchange);
  }

  @Override
  public String getRequestMethod() {
    return exchange.getRequestMethod();
  }

  @Override
  public URI getRequestURI() {
    return exchange.getRequestURI();
  }

  @Override
  public S3HttpHeaders getRequestHeaders() {
    return headersAdapter;
  }

  @Override
  public InputStream getRequestBody() {
    return exchange.getRequestBody();
  }

  @Override
  public S3HttpHeaders getResponseHeaders() {
    return headersAdapter;
  }

  @Override
  public OutputStream getResponseBody() {
    return exchange.getResponseBody();
  }

  @Override
  public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
    exchange.sendResponseHeaders(rCode, responseLength);
  }

  @Override
  public void close() {
    exchange.close();
  }
}
