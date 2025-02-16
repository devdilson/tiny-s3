package com.tinys3.response;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

public record BucketObject(Path path, long size, FileTime lastModified) {}
