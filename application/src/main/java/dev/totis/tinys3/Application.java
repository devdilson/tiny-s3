package dev.totis.tinys3;

import dev.totis.tinys3.auth.Credentials;
import java.util.Objects;

public class Application {

  public static void main(String[] args) {
    String accessKey = System.getenv("TINY_S3_ACCESS_KEY");
    String secretKey = System.getenv("TINY_S3_SECRET_KEY");

    Objects.requireNonNull(accessKey, "The access key is required");
    Objects.requireNonNull(secretKey, "The secret key is required");

    String storageFolder =
        Objects.requireNonNullElse(System.getenv("TINY_S3_STORAGE_DIR"), "/storage");
    String region = Objects.requireNonNullElse(System.getenv("TINY_S3_REGION"), "us-east-1");

    int port = Integer.parseInt(Objects.requireNonNullElse(System.getenv("TINY_S3_PORT"), "8000"));
    S3Server server =
        new S3Server.Builder()
            .withPort(port)
            .withCredentials(new Credentials(accessKey, secretKey, region))
            .withStorageDir(storageFolder) // Local directory for storage
            .build();
    server.start();
    System.out.println("Server started at port 8000");
  }
}
