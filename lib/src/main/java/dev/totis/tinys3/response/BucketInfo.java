package dev.totis.tinys3.response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

public record BucketInfo(String name, FileTime creationTime, String accessKey) {
  public static ListAllBucketsResult.BucketInfo fromPath(Path bucket, String accessKey)
      throws IOException {
    return new ListAllBucketsResult.BucketInfo(
        bucket.getFileName().toString(),
        (FileTime) Files.getAttribute(bucket, "creationTime"),
        accessKey);
  }
}
