package dev.totis.tinys3.io;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

public class NioFileOperations implements FileOperations {

  private final String storagePath;

  public NioFileOperations(String storagePath) {
    this.storagePath = storagePath;
  }

  @Override
  public void createDirectory(String path) throws StorageException {
    try {
      Files.createDirectory(Paths.get(storagePath, path));
    } catch (IOException e) {
      throw new StorageException("Failed to create directory: " + path, e);
    }
  }

  @Override
  public boolean exists(String path) {
    return Files.exists(Paths.get(storagePath, path));
  }

  @Override
  public void createParentDirectories(String path) throws StorageException {
    try {
      Files.createDirectories(Paths.get(storagePath, path).getParent());
    } catch (IOException e) {
      throw new StorageException("Failed to create parent directories: " + path, e);
    }
  }

  @Override
  public void appendToFile(String path, byte[] data) throws StorageException {
    try (var fw = new FileOutputStream(Paths.get(storagePath, path).toFile(), true)) {
      fw.write(data);
      fw.flush();
    } catch (IOException e) {
      throw new StorageException("Failed to append to file: " + path, e);
    }
  }

  @Override
  public void writeFile(String path, byte[] data) throws StorageException {
    try {
      Files.write(Paths.get(storagePath, path), data);
    } catch (IOException e) {
      throw new StorageException("Failed to write file: " + path, e);
    }
  }

  @Override
  public void writeTempFile(String path, byte[] data) throws StorageException {
    try {
      Files.write(Paths.get(path), data);
    } catch (IOException e) {
      throw new StorageException("Failed to write file: " + path, e);
    }
  }

  @Override
  public void writeFileStream(String path, InputStream inputStream) throws StorageException {}

  @Override
  public byte[] readFile(String path) throws StorageException {
    try {
      return Files.readAllBytes(Paths.get(storagePath, path));
    } catch (IOException e) {
      throw new StorageException("Failed to read file: " + path, e);
    }
  }

  @Override
  public byte[] readTempFile(String path) throws StorageException {
    try {
      return Files.readAllBytes(Paths.get(path));
    } catch (IOException e) {
      throw new StorageException("Failed to read file: " + path, e);
    }
  }

  @Override
  public InputStream readFileStream(String path) throws StorageException {
    try {
      return Files.newInputStream(Paths.get(storagePath, path));
    } catch (IOException e) {
      throw new StorageException("Failed to create input stream for file: " + path, e);
    }
  }

  @Override
  public void delete(String path) throws StorageException {
    try {
      Files.delete(Paths.get(storagePath, path));
    } catch (IOException e) {
      throw new StorageException("Failed to delete: " + path, e);
    }
  }

  @Override
  public FileEntry[] list(String bucketName) throws StorageException {
    try {
      return Files.walk(Paths.get(storagePath, bucketName))
          .map(
              p -> {
                try {
                  BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                  String objectName =
                      p.toString().replace(storagePath, "").replace("/" + bucketName + "/", "");
                  return new FileEntry(
                      objectName,
                      attrs.isDirectory(),
                      attrs.size(),
                      attrs.lastModifiedTime().toMillis());
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              })
          .toArray(FileEntry[]::new);
    } catch (IOException e) {
      throw new StorageException("Failed to list directory: " + storagePath, e);
    }
  }

  @Override
  public long getSize(String path) throws StorageException {
    try {
      return Files.size(Paths.get(storagePath, path));
    } catch (IOException e) {
      throw new StorageException("Failed to get size: " + path, e);
    }
  }

  @Override
  public FileTime getLastModifiedTime(String path) throws StorageException {
    try {
      return Files.getLastModifiedTime(Paths.get(storagePath, path));
    } catch (IOException e) {
      throw new StorageException("Failed to get last modified time: " + path, e);
    }
  }

  @Override
  public void copy(String sourcePath, String destinationPath) throws StorageException {
    try {
      Files.copy(
          Paths.get(storagePath, sourcePath),
          Paths.get(storagePath, destinationPath),
          StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new StorageException("Failed to copy from " + sourcePath + " to " + destinationPath, e);
    }
  }

  @Override
  public String createTempDirectory(String prefix) throws StorageException {
    try {
      return Files.createTempDirectory(prefix).toString();
    } catch (IOException e) {
      throw new StorageException("Failed to create temp directory", e);
    }
  }

  @Override
  public boolean isDirectoryNotEmpty(String path) throws StorageException {
    try {
      return Files.list(Paths.get(storagePath, path)).findFirst().isPresent();
    } catch (IOException e) {
      throw new StorageException("Failed to check if directory is empty: " + path, e);
    }
  }

  @Override
  public String getObjectPath(String bucketName, String key) {
    return bucketName + "/" + key;
  }

  @Override
  public FileEntry[] listBuckets() throws StorageException {
    try {
      return Files.list(Paths.get(storagePath))
          .map(
              p -> {
                try {
                  BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                  return new FileEntry(
                      p.toString().replace(storagePath, "").substring(1),
                      attrs.isDirectory(),
                      attrs.size(),
                      attrs.lastModifiedTime().toMillis());
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              })
          .toArray(FileEntry[]::new);
    } catch (IOException e) {
      throw new StorageException("Failed to list directory: " + storagePath, e);
    }
  }

  @Override
  public void deleteTempFile(String path) throws StorageException {
    try {
      Files.delete(Paths.get(path));
    } catch (IOException e) {
      throw new StorageException("Failed to delete: " + path, e);
    }
  }
}
