package com.tinys3.response;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tinys3.S3Utils.createXMLStreamWriter;

public record BucketListResult(
    String bucketName,
    String prefix,
    String delimiter,
    int maxKeys,
    String continuationToken,
    String nextContinuationToken,
    Set<String> commonPrefixes,
    List<BucketObject> objects,
    Path bucketPath,
    boolean isV2) {
  public record BucketObject(Path path, long size, FileTime lastModified) {}

  public String toXML() {
    StringWriter writer = new StringWriter();
    XMLStreamWriter xml = createXMLStreamWriter(writer);
    try {
      xml.writeStartDocument();
      xml.writeStartElement(isV2 ? "ListBucketV2Result" : "ListBucketResult");

      xml.writeStartElement("Name");
      xml.writeCharacters(bucketName);
      xml.writeEndElement();

      xml.writeStartElement("Prefix");
      xml.writeCharacters(prefix);
      xml.writeEndElement();

      if (!delimiter.isEmpty()) {
        xml.writeStartElement("Delimiter");
        xml.writeCharacters(delimiter);
        xml.writeEndElement();
      }

      xml.writeStartElement("MaxKeys");
      xml.writeCharacters(String.valueOf(maxKeys));
      xml.writeEndElement();

      if (isV2) {
        xml.writeStartElement("KeyCount");
        xml.writeCharacters(String.valueOf(objects.size() + commonPrefixes.size()));
        xml.writeEndElement();

        if (continuationToken != null) {
          xml.writeStartElement("ContinuationToken");
          xml.writeCharacters(continuationToken);
          xml.writeEndElement();
        }
      }

      boolean isTruncated = nextContinuationToken != null;
      xml.writeStartElement("IsTruncated");
      xml.writeCharacters(String.valueOf(isTruncated));
      xml.writeEndElement();

      if (isTruncated) {
        if (isV2) {
          xml.writeStartElement("NextContinuationToken");
          xml.writeCharacters(nextContinuationToken);
          xml.writeEndElement();
        } else {
          xml.writeStartElement("NextMarker");
          xml.writeCharacters(nextContinuationToken);
          xml.writeEndElement();
        }
      }

      for (String commonPrefix : commonPrefixes) {
        xml.writeStartElement("CommonPrefixes");
        xml.writeStartElement("Prefix");
        xml.writeCharacters(commonPrefix);
        xml.writeEndElement();
        xml.writeEndElement();
      }

      for (BucketObject object : objects) {
        xml.writeStartElement("Contents");

        xml.writeStartElement("Key");
        xml.writeCharacters(bucketPath.relativize(object.path()).toString());
        xml.writeEndElement();

        xml.writeStartElement("Size");
        xml.writeCharacters(String.valueOf(object.size()));
        xml.writeEndElement();

        xml.writeStartElement("LastModified");
        xml.writeCharacters(object.lastModified().toString());
        xml.writeEndElement();

        xml.writeEndElement();
      }

      xml.writeEndElement();
      xml.writeEndDocument();

      return writer.toString();
    } catch (XMLStreamException e) {
      throw new RuntimeException(e);
    } finally {
      try {
        xml.close();
      } catch (XMLStreamException e) {
        e.printStackTrace();
      }
    }
  }

  // Builder for easier construction with optional fields
  public static class Builder {
    private String bucketName;
    private String prefix = "";
    private String delimiter = "";
    private int maxKeys = 1000;
    private String continuationToken;
    private String nextContinuationToken;
    private Set<String> commonPrefixes = new HashSet<>();
    private List<BucketObject> objects = new ArrayList<>();
    private Path bucketPath;
    private boolean isV2;

    public Builder bucketName(String bucketName) {
      this.bucketName = bucketName;
      return this;
    }

    public Builder prefix(String prefix) {
      this.prefix = prefix;
      return this;
    }

    public Builder delimiter(String delimiter) {
      this.delimiter = delimiter;
      return this;
    }

    public Builder maxKeys(int maxKeys) {
      this.maxKeys = maxKeys;
      return this;
    }

    public Builder continuationToken(String continuationToken) {
      this.continuationToken = continuationToken;
      return this;
    }

    public Builder nextContinuationToken(String nextContinuationToken) {
      this.nextContinuationToken = nextContinuationToken;
      return this;
    }

    public Builder commonPrefixes(Set<String> commonPrefixes) {
      this.commonPrefixes = commonPrefixes;
      return this;
    }

    public Builder objects(List<Path> paths) {
      this.objects =
          paths.stream()
              .map(
                  path -> {
                    try {
                      return new BucketObject(
                          path, Files.size(path), Files.getLastModifiedTime(path));
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  })
              .collect(Collectors.toList());
      return this;
    }

    public Builder bucketPath(Path bucketPath) {
      this.bucketPath = bucketPath;
      return this;
    }

    public Builder isV2(boolean isV2) {
      this.isV2 = isV2;
      return this;
    }

    public BucketListResult build() {
      return new BucketListResult(
          bucketName,
          prefix,
          delimiter,
          maxKeys,
          continuationToken,
          nextContinuationToken,
          commonPrefixes,
          objects,
          bucketPath,
          isV2);
    }
  }
}
