package com.tinys3.io;

public record FileEntry(String path, boolean isDirectory, long size, long lastModified) {}
