package dev.totis.tinys3;

import java.nio.file.Path;

public class PartInfo {
  int partNumber;
  String eTag;
  Path tempPath;

  PartInfo(int partNumber, String eTag, Path tempPath) {
    this.partNumber = partNumber;
    this.eTag = eTag;
    this.tempPath = tempPath;
  }
}
