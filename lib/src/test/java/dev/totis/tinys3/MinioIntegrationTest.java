package dev.totis.tinys3;

import static org.junit.jupiter.api.Assertions.*;

import dev.totis.tinys3.auth.Credentials;
import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import io.minio.messages.Bucket;
import io.minio.messages.Item;
import io.minio.messages.Part;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
            .withInMemory()
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
    var file =
        Objects.requireNonNull(this.getClass().getClassLoader().getResource(objectName)).getFile();
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

    Path testDir = createTestDirectory(sourceDir);

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

    StatObjectResponse stat =
        minioClient.statObject(
            StatObjectArgs.builder().bucket(bucketName).object(objectName).build());

    assertNotNull(stat);
    assertEquals(testData.length, stat.size());

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
        .sorted(Comparator.reverseOrder())
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

    boolean exists =
        minioClient.bucketExists(BucketExistsArgs.builder().bucket(testBucket).build());
    assertTrue(exists, "Bucket should exist after creation");

    List<Bucket> buckets = minioClient.listBuckets();
    assertTrue(
        buckets.stream().anyMatch(b -> b.name().equals(testBucket)),
        "Test bucket should be in bucket list");

    minioClient.removeBucket(RemoveBucketArgs.builder().bucket(testBucket).build());

    exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(testBucket).build());
    assertFalse(exists, "Bucket should not exist after deletion");
  }

  @Test
  void testObjectOperations() throws Exception {
    String objectName = "test-object123.txt";
    String content = "Hello, MinIO!";

    File testFile = createTestFile(objectName, content);

    minioClient.uploadObject(
        UploadObjectArgs.builder()
            .bucket(BUCKET_NAME)
            .object(objectName)
            .filename(testFile.getAbsolutePath())
            .build());

    StatObjectResponse stat =
        minioClient.statObject(
            StatObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).build());
    assertEquals(objectName, stat.object());

    String copyObjectName = "copy-" + objectName;
    minioClient.copyObject(
        CopyObjectArgs.builder()
            .bucket(BUCKET_NAME)
            .object(copyObjectName)
            .source(CopySource.builder().bucket(BUCKET_NAME).object(objectName).build())
            .build());

    StatObjectResponse copyStat =
        minioClient.statObject(
            StatObjectArgs.builder().bucket(BUCKET_NAME).object(copyObjectName).build());
    assertEquals(copyObjectName, copyStat.object());
    assertEquals(stat.size(), copyStat.size());

    InputStream stream =
        minioClient.getObject(
            GetObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).build());
    String downloadedContent = new String(stream.readAllBytes());
    assertEquals(content, downloadedContent);

    cleanUpData(copyObjectName);

    cleanUpData(objectName);

    assertThrows(
        ErrorResponseException.class,
        () ->
            minioClient.statObject(
                StatObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).build()));

    testFile.delete();
  }

  @Test
  void testConcurrentOperations() throws Exception {
    int numThreads = 50;
    int requestsPerThread = 10;
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

  @Test
  void testPostObject() throws Exception {
    String objectName = "post-test-file.txt";
    String content = "Test content for POST upload";
    byte[] data = content.getBytes();

    Map<String, String> reqParams = new HashMap<>();
    reqParams.put("Content-Type", "text/plain");

    PostPolicy policy = new PostPolicy(BUCKET_NAME, ZonedDateTime.now().plusMinutes(5));
    policy.addEqualsCondition("key", objectName);
    policy.addEqualsCondition("Content-Type", "text/plain");
    policy.addContentLengthRangeCondition(1, 1024 * 1024); // 1B to 1MB

    Map<String, String> formData = minioClient.getPresignedPostFormData(policy);

    String boundary = "------------------------" + System.currentTimeMillis();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();

    for (Map.Entry<String, String> entry : formData.entrySet()) {
      baos.write(("--" + boundary + "\r\n").getBytes());
      baos.write(
          ("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n").getBytes());
      baos.write((entry.getValue() + "\r\n").getBytes());
    }

    baos.write(("--" + boundary + "\r\n").getBytes());
    baos.write(
        ("Content-Disposition: form-data; name=\"file\"; filename=\"" + objectName + "\"\r\n")
            .getBytes());
    baos.write(("Content-Type: text/plain\r\n\r\n").getBytes());
    baos.write(data);
    baos.write(("\r\n--" + boundary + "--\r\n").getBytes());

    HttpClient httpClient = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT + "/" + BUCKET_NAME))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    assertEquals(200, response.statusCode(), "POST upload should succeed");

    StatObjectResponse stat =
        minioClient.statObject(
            StatObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).build());

    assertNotNull(stat, "Object stats should not be null");
    assertEquals(objectName, stat.object(), "Object name should match");
    assertEquals(data.length, stat.size(), "File size should match");

    try (InputStream stream =
        minioClient.getObject(
            GetObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).build())) {
      String downloadedContent = new String(stream.readAllBytes());
      assertEquals(content, downloadedContent, "Object content should match");
    }

    cleanUpData(objectName);
  }

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
    minioClient.removeObject(
        RemoveObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).build());
  }

  private void downloadAndVerifyData(
      String objectName,
      byte[] testData,
      AtomicInteger successfulRequests,
      AtomicInteger failedRequests)
      throws IOException {
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
    minioClient.putObject(
        PutObjectArgs.builder().bucket(BUCKET_NAME).object(objectName).stream(
                new ByteArrayInputStream(testData), testData.length, -1)
            .build());
  }
}
