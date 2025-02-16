package dev.totis.tinys3;

import static org.junit.jupiter.api.Assertions.*;

import dev.totis.tinys3.auth.Credentials;
import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import io.minio.messages.Bucket;
import io.minio.messages.Item;
import io.minio.messages.Part;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MinioIntegrationTest {
  private MinioClient minioClient;
  private CustomMinioClient customMinioClient;
  private static final String BUCKET_NAME = "mybucket";
  private static final int DEFAULT_PORT = 8000;
  private static final String ENDPOINT = "http://localhost:" + DEFAULT_PORT;
  private static final Credentials credential = new Credentials("12345", "12345", "us-east-1");

  private S3Server server;

  @BeforeAll
  void setup() {
    customMinioClient =
        new CustomMinioClient(
            new MinioAsyncClient.Builder()
                .endpoint(ENDPOINT)
                .region(credential.region())
                .credentials(credential.accessKey(), credential.secretKey())
                .build());
    minioClient =
        MinioClient.builder()
            .endpoint(ENDPOINT)
            .region(credential.region())
            .credentials(credential.accessKey(), credential.secretKey())
            .build();

    server =
        new S3Server.Builder()
            .withPort(DEFAULT_PORT)
            .withStorageDir("storage")
            // .withInMemory()
            .withCredentials(credential)
            .build();

    server.start();
    System.out.println("Minio server started");
  }

  @AfterAll
  void stop() {
    server.stop();
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
    File testFile = createTestFile(objectName, "test");

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
  void testUploadMultipleFiles() throws Exception {
    String objectName = "generated_20mb.file";
    var file = this.getClass().getClassLoader().getResource(objectName).getFile();
    minioClient.uploadObject(
        UploadObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).filename(file).build());

    StatObjectResponse stat =
        minioClient.statObject(
            StatObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).build());

    assertNotNull(stat, "File metadata should not be null");
    assertEquals(objectName, stat.object(), "Object name should match");
    assertEquals(20 * 1024 * 1024, stat.size(), "File size should be 20MB");
    assertEquals("application/octet-stream", stat.contentType(), "Content type should be binary");
  }

  @Test
  void testRecursiveDirectoryUpload() throws Exception {
    String sourceDir = "Test";
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

  @Test
  void uploadMultipart() throws Exception {
    String objectName = "multipart-test-object";
    String bucketName = "mybucket";
    int partSize = 5 * 1024 * 1024; // 5MB parts
    int numParts = 3;
    byte[] testData = new byte[partSize * numParts];
    new Random().nextBytes(testData);

    String uploadId =
        customMinioClient.initMultiPartUpload(bucketName, "us-east-1", objectName, null, null);
    Map<Integer, String> urls = new HashMap<>();
    for (int i = 1; i <= numParts; i++) {
      int finalI = i;
      String signedUrl =
          minioClient.getPresignedObjectUrl(
              GetPresignedObjectUrlArgs.builder()
                  .method(Method.PUT)
                  .bucket(bucketName)
                  .object(objectName)
                  .extraQueryParams(
                      new HashMap<String, String>() {
                        {
                          put("uploadId", uploadId);
                          put("partNumber", String.valueOf(finalI));
                        }
                      })
                  .expiry(5, TimeUnit.MINUTES)
                  .build());
      urls.put(finalI, signedUrl);
    }

    Map<Integer, String> completedParts = new HashMap<>();
    List<Part> parts = new ArrayList<>();
    for (int i = 1; i <= numParts; i++) {
      // Calculate the part data
      int start = (i - 1) * partSize;
      int end = (i == numParts) ? testData.length : i * partSize;
      byte[] partData = Arrays.copyOfRange(testData, start, end);

      String url = urls.get(i);
      HttpClient httpClient = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .PUT(HttpRequest.BodyPublishers.ofByteArray(partData))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      // Check if upload was successful
      if (response.statusCode() == 200) {
        String etag =
            response
                .headers()
                .firstValue("ETag")
                .orElseThrow(() -> new RuntimeException("ETag not found in response"));
        completedParts.put(i, etag);
        parts.add(new Part(i, etag));
      } else {
        throw new RuntimeException("Failed to upload part " + i + ": " + response.statusCode());
      }
    }
    ObjectWriteResponse uploadIdNew =
        customMinioClient.completeMultipartUpload(
            bucketName, "us-east-1", objectName, uploadId, parts, null, null);
    assertNotNull(uploadIdNew);
    assertEquals(numParts, completedParts.size());

    // Verify the file exists and has correct size
    StatObjectResponse stat =
        minioClient.statObject(
            StatObjectArgs.builder().bucket(bucketName).object(objectName).build());

    assertNotNull(stat);
    assertEquals(testData.length, stat.size());

    // Optional: Verify content if needed
    GetObjectResponse response =
        minioClient.getObject(
            GetObjectArgs.builder().bucket(bucketName).object(objectName).build());

    byte[] downloadedData = response.readAllBytes();
    assertArrayEquals(testData, downloadedData);
  }

  private File createTestFile(String filename, String content) throws Exception {
    File file = new File(filename);
    Files.write(file.toPath(), content.getBytes());
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

  @Test
  void testBucketOperations() throws Exception {
    String testBucket = "test-bucket-ops";

    boolean bucketExists =
        minioClient.bucketExists(BucketExistsArgs.builder().bucket(testBucket).build());

    if (!bucketExists) {
      minioClient.makeBucket(MakeBucketArgs.builder().bucket(testBucket).build());
    }

    // Verify bucket exists
    boolean exists =
        minioClient.bucketExists(BucketExistsArgs.builder().bucket(testBucket).build());
    assertTrue(exists, "Bucket should exist after creation");

    // List buckets
    List<Bucket> buckets = minioClient.listBuckets();
    assertTrue(
        buckets.stream().anyMatch(b -> b.name().equals(testBucket)),
        "Test bucket should be in bucket list");

    // Remove bucket
    minioClient.removeBucket(RemoveBucketArgs.builder().bucket(testBucket).build());

    // Verify bucket no longer exists
    exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(testBucket).build());
    assertFalse(exists, "Bucket should not exist after deletion");
  }

  @Test
  void testObjectOperations() throws Exception {
    String objectName = "test-object123.txt";
    String content = "Hello, MinIO!";

    // Create test file
    File testFile = createTestFile(objectName, content);

    // Upload object
    minioClient.uploadObject(
        UploadObjectArgs.builder()
            .bucket(BUCKET_NAME)
            .object(objectName)
            .filename(testFile.getAbsolutePath())
            .build());

    // Get object info
    StatObjectResponse stat =
        minioClient.statObject(
            StatObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).build());
    assertEquals(objectName, stat.object());

    // Copy object
    String copyObjectName = "copy-" + objectName;
    minioClient.copyObject(
        CopyObjectArgs.builder()
            .bucket(BUCKET_NAME)
            .object(copyObjectName)
            .source(CopySource.builder().bucket(BUCKET_NAME).object(objectName).build())
            .build());

    // Verify copy exists
    StatObjectResponse copyStat =
        minioClient.statObject(
            StatObjectArgs.builder().bucket(BUCKET_NAME).object(copyObjectName).build());
    assertEquals(copyObjectName, copyStat.object());
    assertEquals(stat.size(), copyStat.size());

    // Download object
    InputStream stream =
        minioClient.getObject(
            GetObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).build());
    String downloadedContent = new String(stream.readAllBytes());
    assertEquals(content, downloadedContent);

    // Remove objects
    cleanUpData(copyObjectName);

    cleanUpData(objectName);

    // Verify objects are deleted
    assertThrows(
        ErrorResponseException.class,
        () ->
            minioClient.statObject(
                StatObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).build()));

    // Cleanup
    testFile.delete();
  }

  @Test
  void testConcurrentOperations() throws Exception {
    int numThreads = 50;
    int requestsPerThread = 100;
    int totalRequests = numThreads * requestsPerThread;

    try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(totalRequests);
      AtomicInteger successfulRequests = new AtomicInteger(0);
      AtomicInteger failedRequests = new AtomicInteger(0);

      byte[] testData = "Test content for concurrent operations".getBytes();

      List<Future<?>> futures =
          IntStream.range(0, numThreads)
              .mapToObj(
                  threadId ->
                      executorService.submit(
                          () ->
                              IntStream.range(0, requestsPerThread)
                                  .forEach(
                                      performRequestTests(
                                          threadId,
                                          testData,
                                          successfulRequests,
                                          failedRequests,
                                          latch))))
              .collect(Collectors.toList());

      boolean completed = latch.await(5, TimeUnit.MINUTES);

      assertTrue(completed && !futures.isEmpty(), "Not all requests completed within timeout");

      int totalProcessed = successfulRequests.get() + failedRequests.get();
      assertEquals(
          totalRequests, totalProcessed, "Total processed requests should match expected total");

      double successRate = (double) successfulRequests.get() / totalRequests * 100;
      System.out.printf(
          "Success rate: %.2f%% (%d/%d requests successful)%n",
          successRate, successfulRequests.get(), totalRequests);

      assertTrue(successRate >= 100, "Success rate should be 100%");
    }
  }

  @NotNull
  private IntConsumer performRequestTests(
      int threadId,
      byte[] testData,
      AtomicInteger successfulRequests,
      AtomicInteger failedRequests,
      CountDownLatch latch) {
    return requestId -> {
      String objectName =
          String.format("concurrent-test/thread-%d-object-%d.txt", threadId, requestId);

      try {
        minioPutObject(objectName, testData);

        downloadAndVerifyData(objectName, testData, successfulRequests, failedRequests);

        cleanUpData(objectName);

      } catch (Exception e) {
        failedRequests.incrementAndGet();
        System.err.printf(
            "Error in thread %d, request %d: %s%n", threadId, requestId, e.getMessage());
      } finally {
        latch.countDown();
      }
    };
  }

  private void cleanUpData(String objectName)
      throws ErrorResponseException,
          InsufficientDataException,
          InternalException,
          InvalidKeyException,
          InvalidResponseException,
          IOException,
          NoSuchAlgorithmException,
          ServerException,
          XmlParserException {
    // Clean up
    minioClient.removeObject(
        RemoveObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).build());
  }

  private void downloadAndVerifyData(
      String objectName,
      byte[] testData,
      AtomicInteger successfulRequests,
      AtomicInteger failedRequests)
      throws IOException {
    // Download and verify object
    try (InputStream stream =
        minioClient.getObject(
            GetObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).build())) {

      byte[] downloadedData = stream.readAllBytes();
      if (Arrays.equals(testData, downloadedData)) {
        successfulRequests.incrementAndGet();
      } else {
        failedRequests.incrementAndGet();
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void minioPutObject(String objectName, byte[] testData)
      throws ErrorResponseException,
          InsufficientDataException,
          InternalException,
          InvalidKeyException,
          InvalidResponseException,
          IOException,
          NoSuchAlgorithmException,
          ServerException,
          XmlParserException {
    // Upload object
    minioClient.putObject(
        PutObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).stream(
                new ByteArrayInputStream(testData), testData.length, -1)
            .build());
  }
}
