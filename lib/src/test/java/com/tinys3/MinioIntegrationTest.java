package com.tinys3;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.tinys3.auth.Credentials;
import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.Item;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MinioIntegrationTest {
  private MinioClient minioClient;
  private static final String BUCKET_NAME = "mybucket";
  private static final int DEFAULT_PORT = 8000;
  private static final String ENDPOINT = "http://localhost:" + DEFAULT_PORT;
  private static final String ACCESS_KEY = "12345";
  private static final String SECRET_KEY = "12345";

  private HttpServer server;

  @BeforeAll
  void setup() {
    minioClient =
        MinioClient.builder().endpoint(ENDPOINT).credentials(ACCESS_KEY, SECRET_KEY).build();
    server =
        S3Server.getHttpServer(
            DEFAULT_PORT, Map.of(ACCESS_KEY, new Credentials(ACCESS_KEY, SECRET_KEY, "us-east-1")));
    server.start();
    System.out.println("Minio server started");
  }

  @AfterAll
  void stop() {
    server.stop(0);
    System.out.println("Minio server stopped");
  }

  @BeforeEach
  void ensureBucketExists() throws Exception {
    boolean bucketExists =
        minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET_NAME).build());

    if (!bucketExists) {
      minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET_NAME).build());
    }
  }

  @Test
  void testListBucket() throws Exception {
    Iterable<Result<Item>> results =
        minioClient.listObjects(ListObjectsArgs.builder().bucket(BUCKET_NAME).build());

    List<String> objects = new ArrayList<>();
    for (Result<Item> result : results) {
      Item item = result.get();
      objects.add(item.objectName());
    }

    assertNotNull(objects);
  }

  @Test
  void testGeneratePresignedUrl() throws Exception {
    String objectName = "README.md";
    String url =
        minioClient.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .bucket(BUCKET_NAME)
                .object(objectName)
                .method(Method.GET)
                .expiry(1, TimeUnit.HOURS)
                .build());

    assertNotNull(url);
    assertTrue(url.contains(ENDPOINT));
    assertTrue(url.contains(BUCKET_NAME));
    assertTrue(url.contains(objectName));
  }

  @Test
  void testUploadSingleFile() throws Exception {
    // Test equivalent to: aws s3 cp README.md s3://mybucket/
    String objectName = "README.md";
    File testFile = createTestFile(objectName);

    minioClient.uploadObject(
        UploadObjectArgs.builder()
            .bucket(BUCKET_NAME)
            .object(objectName)
            .filename(testFile.getAbsolutePath())
            .build());

    // Verify upload
    StatObjectResponse stat =
        minioClient.statObject(
            StatObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).build());

    assertNotNull(stat);
    assertEquals(objectName, stat.object());

    testFile.delete();
  }

  @Test
  void testRecursiveDirectoryUpload() throws Exception {
    // Test equivalent to: aws s3 cp ./TypeScript-Client s3://mybucket/myfolder --recursive
    String sourceDir = "TypeScript-Client";
    String targetPrefix = "myfolder/";

    // Create test directory structure
    Path testDir = createTestDirectory(sourceDir);

    // Upload directory recursively
    Files.walk(testDir)
        .filter(Files::isRegularFile)
        .forEach(
            file -> {
              try {
                String objectName = targetPrefix + testDir.relativize(file).toString();
                minioClient.uploadObject(
                    UploadObjectArgs.builder()
                        .bucket(BUCKET_NAME)
                        .object(objectName)
                        .filename(file.toString())
                        .build());
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    // Verify uploads
    Iterable<Result<Item>> results =
        minioClient.listObjects(
            ListObjectsArgs.builder()
                .bucket(BUCKET_NAME)
                .prefix(targetPrefix)
                .recursive(true)
                .build());

    List<String> uploadedObjects = new ArrayList<>();
    for (Result<Item> result : results) {
      uploadedObjects.add(result.get().objectName());
    }

    assertFalse(uploadedObjects.isEmpty());
    // Add more specific assertions based on expected directory structure

    // Cleanup
    deleteDirectory(testDir);
  }

  private File createTestFile(String filename) throws Exception {
    File file = new File(filename);
    Files.write(file.toPath(), "Test content".getBytes());
    return file;
  }

  private Path createTestDirectory(String dirName) throws Exception {
    Path dir = Paths.get(dirName);
    Files.createDirectories(dir);

    // Create some test files
    Files.write(dir.resolve("file1.ts"), "Test content 1".getBytes());
    Files.write(dir.resolve("file2.ts"), "Test content 2".getBytes());

    // Create subdirectory with files
    Path subDir = dir.resolve("subdir");
    Files.createDirectories(subDir);
    Files.write(subDir.resolve("file3.ts"), "Test content 3".getBytes());

    return dir;
  }

  private void deleteDirectory(Path dir) throws Exception {
    Files.walk(dir)
        .sorted((a, b) -> b.compareTo(a))
        .forEach(
            path -> {
              try {
                Files.delete(path);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
  }
}
