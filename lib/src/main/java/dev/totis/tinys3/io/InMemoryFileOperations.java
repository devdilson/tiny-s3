package dev.totis.tinys3.io;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryFileOperations implements FileOperations {
  private final Map<String, FileData> storage;

  private static class FileData {
    byte[] content;
    boolean isDirectory;
    long lastModified;

    FileData(byte[] content, boolean isDirectory) {
      this.content = content;
      this.isDirectory = isDirectory;
      this.lastModified = System.currentTimeMillis();
    }
  }

  public InMemoryFileOperations() {
    this.storage = new ConcurrentHashMap<>();
  }

  @Override
  public void createDirectory(String path) throws StorageException {
    if (exists(path)) {
      throw new StorageException("Directory already exists: " + path);
    }
    storage.put(path, new FileData(null, true));
  }

  @Override
  public boolean exists(String path) {
    return storage.containsKey(path);
  }

  @Override
  public void createParentDirectories(String path) throws StorageException {
    String parent = getParentPath(path);
    if (parent != null && !exists(parent)) {
      createDirectory(parent);
    }
  }

  @Override
  public void appendToFile(String path, byte[] data) throws StorageException {
    FileData fileData = storage.get(path);
    if (fileData == null) {
      writeFile(path, data);
      storage.put(path, new FileData(data, false));
      return;
    }
    byte[] existing = fileData.content;
    byte[] newContent = new byte[data.length + existing.length];
    System.arraycopy(existing, 0, newContent, 0, existing.length);
    System.arraycopy(data, 0, newContent, existing.length, data.length);
    storage.put(path, new FileData(newContent, fileData.isDirectory));
  }

  @Override
  public void writeFile(String path, byte[] data) throws StorageException {
    createParentDirectories(path);
    storage.put(path, new FileData(data, false));
  }

  @Override
  public void writeTempFile(String path, byte[] data) throws StorageException {
    writeFile(path, data);
  }

  @Override
  public byte[] readTempFile(String path) {
    FileData fileData = storage.get(path);
    return fileData == null ? null : fileData.content;
  }

  @Override
  public InputStream readFileStream(String path) throws StorageException {
    FileData data = storage.get(path);
    if (data == null || data.isDirectory) {
      throw new StorageException("File not found or is a directory: " + path);
    }
    return new ByteArrayInputStream(data.content);
  }

  @Override
  public void delete(String path) throws StorageException {
    // If it's a directory, delete all children first
    if (exists(path) && storage.get(path).isDirectory) {
      List<String> children =
          storage.keySet().stream().filter(key -> key.startsWith(path + "/")).toList();

      if (!children.isEmpty() && isDirectoryNotEmpty(path)) {
        throw new StorageException("Directory not empty: " + path);
      }

      children.forEach(storage::remove);
    }

    storage.remove(path);
  }

  @Override
  public FileEntry[] list(String bucketName) throws StorageException {
    String prefix = bucketName + "/";
    return storage.entrySet().stream()
        .filter(entry -> entry.getKey().startsWith(prefix))
        .map(
            entry -> {
              FileData data = entry.getValue();
              String objectName = entry.getKey().substring(prefix.length());
              return new FileEntry(
                  objectName,
                  data.isDirectory,
                  data.isDirectory ? 0 : data.content.length,
                  data.lastModified);
            })
        .toArray(FileEntry[]::new);
  }

  @Override
  public long getSize(String path) throws StorageException {
    FileData data = storage.get(path);
    if (data == null) {
      throw new StorageException("Path does not exist: " + path);
    }
    return data.isDirectory ? 0 : data.content.length;
  }

  @Override
  public FileTime getLastModifiedTime(String path) throws StorageException {
    FileData data = storage.get(path);
    if (data == null) {
      throw new StorageException("Path does not exist: " + path);
    }
    return FileTime.fromMillis(data.lastModified);
  }

  @Override
  public void copy(String sourcePath, String destinationPath) throws StorageException {
    FileData sourceData = storage.get(sourcePath);
    if (sourceData == null) {
      throw new StorageException("Source path does not exist: " + sourcePath);
    }

    createParentDirectories(destinationPath);
    storage.put(destinationPath, new FileData(sourceData.content, sourceData.isDirectory));
  }

  @Override
  public String createTempDirectory(String prefix) throws StorageException {
    String tempPath = "/temp-" + prefix + "-" + UUID.randomUUID();
    createDirectory(tempPath);
    return tempPath;
  }

  @Override
  public boolean isDirectoryNotEmpty(String path) throws StorageException {
    FileData data = storage.get(path);
    if (data == null || !data.isDirectory) {
      throw new StorageException("Path does not exist or is not a directory: " + path);
    }

    String prefix = path + "/";
    return storage.keySet().stream().anyMatch(key -> key.startsWith(prefix));
  }

  @Override
  public String getObjectPath(String bucketName, String key) {
    if (key == null || key.isEmpty()) {
      return bucketName;
    }
    return bucketName + "/" + key;
  }

  @Override
  public FileEntry[] listBuckets() throws StorageException {
    storage
        .entrySet()
        .forEach(
            entry -> {
              System.out.println(entry.getKey());
            });
    return storage.entrySet().stream()
        .map(
            entry -> {
              FileData data = entry.getValue();
              String bucketName = entry.getKey().replace("/", "");

              return new FileEntry(
                  bucketName,
                  data.isDirectory,
                  data.isDirectory ? 0 : data.content.length,
                  data.lastModified);
            })
        .toArray(FileEntry[]::new);
  }

  @Override
  public void deleteTempFile(String string) throws StorageException {
    storage.remove(string);
  }

  private String getParentPath(String path) {
    int lastSlash = path.lastIndexOf('/');
    return lastSlash > 0 ? path.substring(0, lastSlash) : null;
  }
}
