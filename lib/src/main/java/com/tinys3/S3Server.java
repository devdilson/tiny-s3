package com.tinys3;

import com.sun.net.httpserver.HttpServer;
import com.tinys3.auth.Credentials;
import com.tinys3.auth.DefaultAuthenticator;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class S3Server {
  private static final int PORT = 8000;
  private static final Map<String, String> CREDENTIALS = new HashMap<>();

  private static final String STORAGE_PATH = "storage";


  static {
    try {
      Files.createDirectories(Paths.get(STORAGE_PATH));
    } catch (IOException e) {
      throw new RuntimeException("Failed to initialize storage", e);
    }
  }

  public static void main(String[] args) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
    Credentials credential = new Credentials("12345", "12345", "us-east-1");
    Map<String, Credentials> credentials = Map.of(credential.getAccessKey(), credential);
    server.createContext(
        "/", new S3Handler(credentials, STORAGE_PATH, new DefaultAuthenticator(credentials)));
    server.setExecutor(Executors.newFixedThreadPool(24));
    server.start();
    System.out.println("S3 Server started on port " + PORT);
  }
}
