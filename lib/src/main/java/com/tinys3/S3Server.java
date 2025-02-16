package com.tinys3;

import com.sun.net.httpserver.HttpServer;
import com.tinys3.auth.Credentials;
import com.tinys3.auth.DefaultAuthenticator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Executors;

public class S3Server {

  public static void main(String[] args) throws IOException {
    int port = 8080;
    Credentials credential = new Credentials("12345", "12345", "us-east-1");
    HttpServer server = getHttpServer(port, Map.of(credential.getAccessKey(), credential));
    server.start();
    System.out.println("S3 Server started on port " + port);
  }

  public static HttpServer getHttpServer(int port, Map<String, Credentials> credentials) {
    String storagePath = "storage";
    try {
      Files.createDirectories(Paths.get(storagePath));
      HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
      server.createContext(
          "/", new S3Handler(credentials, storagePath, new DefaultAuthenticator(credentials)));
      server.setExecutor(Executors.newFixedThreadPool(24));
      return server;
    } catch (IOException e) {
      throw new RuntimeException("Failed to initialize storage", e);
    }
  }
}
