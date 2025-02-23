package dev.totis.tinys3;

import com.sun.net.httpserver.HttpServer;
import dev.totis.tinys3.auth.Credentials;
import dev.totis.tinys3.auth.DefaultAuthenticator;
import dev.totis.tinys3.http.HttpExchangeAdapter;
import dev.totis.tinys3.http.S3HttpExchange;
import dev.totis.tinys3.io.InMemoryFileOperations;
import dev.totis.tinys3.io.NioFileOperations;
import dev.totis.tinys3.logging.S3Logger;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class S3Server {
  private final HttpServer server;
  private final ExecutorService executor;

  private S3Server(HttpServer server, ExecutorService executor) {
    this.server = server;
    this.executor = executor;
  }

  public void start() {
    S3Logger.getInstance().log("Starting S3Server");
    server.start();
  }

  public void stop() {
    S3Logger.getInstance().log("Stopping S3Server");
    server.stop(0);
    executor.shutdown();
  }

  public static class Builder {
    private String host;
    private int port = 8000;
    private boolean inMemory = false;
    private final Map<String, Credentials> credentialsMap = new HashMap<>();
    private String storageDir = "storage";
    private ExecutorService customExecutor = null;

    public Builder withHost(String host) {
      this.host = host;
      return this;
    }

    public Builder withPort(int port) {
      this.port = port;
      return this;
    }

    public Builder withInMemory() {
      this.inMemory = true;
      return this;
    }

    public Builder withCredentials(Credentials credentials) {
      this.credentialsMap.put(credentials.accessKey(), credentials);
      return this;
    }

    public Builder withStorageDir(String storageDir) {
      this.storageDir = storageDir;
      return this;
    }

    public Builder withCustomExecutor(ExecutorService executor) {
      this.customExecutor = executor;
      return this;
    }

    public S3Server build() {
      try {
        if (!inMemory) {
          Files.createDirectories(Paths.get(storageDir));
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        ExecutorService executor =
            customExecutor != null ? customExecutor : Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        var fileOperations =
            new DefaultS3FileOperations(
                inMemory ? new InMemoryFileOperations() : new NioFileOperations(storageDir));

        var handler =
            new S3Handler(
                host, credentialsMap, new DefaultAuthenticator(credentialsMap), fileOperations);

        S3HttpServerAdapter adapter =
            () ->
                (e) -> {
                  S3HttpExchange request = new HttpExchangeAdapter(e);
                  handler.handle(S3Context.create(request), request);
                };

        server.createContext("/", adapter.getHandler());

        return new S3Server(server, executor);
      } catch (IOException e) {
        throw new RuntimeException("Failed to initialize server", e);
      }
    }
  }
}
