package com.tinys3;

import com.sun.net.httpserver.HttpServer;
import com.tinys3.auth.Credentials;
import com.tinys3.auth.DefaultAuthenticator;
import com.tinys3.http.HttpExchangeAdapter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class S3Server {

  public static void main(String[] args) throws IOException {
    int port = 8080;
    Credentials credential = new Credentials("12345", "12345", "us-east-1");
    HttpServer server = getHttpServer(port, Map.of(credential.accessKey(), credential));
    server.start();
    System.out.println("S3 Server started on port " + port);
  }

  public static HttpServer getHttpServer(int port, Map<String, Credentials> credentials) {
    String storagePath = "storage";
    try {
      Files.createDirectories(Paths.get(storagePath));
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
      server.setExecutor(executor);

      var handler = new S3Handler(credentials, storagePath, new DefaultAuthenticator(credentials));

      S3HttpServerAdapter adapter =
          () ->
              (e) -> {
                handler.handle(new HttpExchangeAdapter(e));
              };

      server.createContext("/", adapter.getHandler());
      server.setExecutor(Executors.newFixedThreadPool(24));
      return server;
    } catch (IOException e) {
      throw new RuntimeException("Failed to initialize storage", e);
    }
  }
}
