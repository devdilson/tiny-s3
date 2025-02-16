package com.tinys3.io;

import java.nio.file.attribute.FileTime;

/**
 * Interface for abstracting file system operations. This interface removes direct dependencies on
 * java.io and java.nio.
 */
public interface FileOperations {
  /** Creates a directory at the specified path */
  void createDirectory(String path) throws StorageException;

  /** Checks if a path exists */
  boolean exists(String path);

  /** Creates parent directories for the given path if they don't exist */
  void createParentDirectories(String path) throws StorageException;

  /** Writes data to a file */
  void writeFile(String path, byte[] data) throws StorageException;

  /** Reads data from a file */
  byte[] readFile(String path) throws StorageException;

  /** Deletes a file or directory */
  void delete(String path) throws StorageException;

  /** Lists files and directories in the given path */
  FileEntry[] list(String bucketName) throws StorageException;

  /** Gets the size of a file */
  long getSize(String path) throws StorageException;

  /** Gets the last modified time of a file */
  FileTime getLastModifiedTime(String path) throws StorageException;

  /** Copies a file from source to destination */
  void copy(String sourcePath, String destinationPath) throws StorageException;

  /** Creates a temporary directory */
  String createTempDirectory(String prefix) throws StorageException;

  /** Checks if the directory is empty */
  boolean isDirectoryEmpty(String path) throws StorageException;

  String getObjectPath(String bucketName, String key);

  FileEntry[] listBuckets() throws StorageException;

  /** Represents a file or directory entry */
  public class FileEntry {
    private final String path;
    private final boolean isDirectory;
    private final long size;
    private final long lastModified;

    public FileEntry(String path, boolean isDirectory, long size, long lastModified) {
      this.path = path;
      this.isDirectory = isDirectory;
      this.size = size;
      this.lastModified = lastModified;
    }

    public String getPath() {
      return path;
    }

    public boolean isDirectory() {
      return isDirectory;
    }

    public long getSize() {
      return size;
    }

    public long getLastModified() {
      return lastModified;
    }
  }
}
