package dev.totis.tinys3.io;

import java.io.InputStream;
import java.nio.file.attribute.FileTime;

public interface FileOperations {
  void createDirectory(String path) throws StorageException;

  boolean exists(String path);

  void createParentDirectories(String path) throws StorageException;

  void appendToFile(String path, byte[] data) throws StorageException;

  void writeFile(String path, byte[] data) throws StorageException;

  void writeTempFile(String path, byte[] data) throws StorageException;

  void writeFileStream(String path, InputStream inputStream) throws StorageException;

  byte[] readFile(String path) throws StorageException;

  byte[] readTempFile(String path) throws StorageException;

  InputStream readFileStream(String path) throws StorageException;

  void delete(String path) throws StorageException;

  FileEntry[] list(String bucketName) throws StorageException;

  long getSize(String path) throws StorageException;

  FileTime getLastModifiedTime(String path) throws StorageException;

  void copy(String sourcePath, String destinationPath) throws StorageException;

  String createTempDirectory(String prefix) throws StorageException;

  boolean isDirectoryNotEmpty(String path) throws StorageException;

  String getObjectPath(String bucketName, String key);

  FileEntry[] listBuckets() throws StorageException;

  void deleteTempFile(String string) throws StorageException;
}
