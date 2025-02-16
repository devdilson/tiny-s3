package com.tinys3.response;

import static com.tinys3.S3Utils.createXMLStreamWriter;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Collectors;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

public record ListAllBucketsResult(List<BucketInfo> buckets) {
  public record BucketInfo(String name, FileTime creationTime, String accessKey) {
    public static BucketInfo fromPath(Path bucket, String accessKey) throws IOException {
      return new BucketInfo(
          bucket.getFileName().toString(),
          (FileTime) Files.getAttribute(bucket, "creationTime"),
          accessKey);
    }
  }

  public String toXML() {
    StringWriter writer = new StringWriter();
    XMLStreamWriter xml = createXMLStreamWriter(writer);
    try {
      xml.writeStartDocument();
      xml.writeStartElement("ListAllMyBucketsResult");

      // Add Owner section
      xml.writeStartElement("Owner");
      xml.writeStartElement("ID");
      xml.writeCharacters("12345");
      xml.writeEndElement(); // ID
      xml.writeEndElement(); // Owner

      xml.writeStartElement("Buckets");

      for (BucketInfo bucket : buckets) {
        xml.writeStartElement("Bucket");
        xml.writeStartElement("Name");
        xml.writeCharacters(bucket.name());
        xml.writeEndElement();
        xml.writeStartElement("CreationDate");
        xml.writeCharacters(bucket.creationTime().toString());
        xml.writeEndElement();
        xml.writeEndElement();
      }

      xml.writeEndElement();
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

  public static ListAllBucketsResult fromPaths(List<Path> buckets, String accessKey) {
    List<BucketInfo> bucketInfos =
        buckets.stream()
            .map(
                bucket -> {
                  try {
                    return BucketInfo.fromPath(bucket, accessKey);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                })
            .collect(Collectors.toList());
    return new ListAllBucketsResult(bucketInfos);
  }
}
