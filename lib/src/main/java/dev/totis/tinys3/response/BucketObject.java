package dev.totis.tinys3.response;

import java.nio.file.attribute.FileTime;

public record BucketObject(String path, long size, FileTime lastModified) {}
