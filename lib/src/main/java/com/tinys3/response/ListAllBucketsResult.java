package com.tinys3.response;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Collectors;

import static com.tinys3.S3Utils.createXMLStreamWriter;

public record ListAllBucketsResult(List<BucketInfo> buckets) {
  public record BucketInfo(String name, FileTime creationTime) {
    public static BucketInfo fromPath(Path bucket) throws IOException {
      return new BucketInfo(
          bucket.getFileName().toString(), (FileTime) Files.getAttribute(bucket, "creationTime"));
    }
  }

  public String toXML() {
    StringWriter writer = new StringWriter();
    XMLStreamWriter xml = createXMLStreamWriter(writer);
    try {
      xml.writeStartDocument();
      xml.writeStartElement("ListAllMyBucketsResult");
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

  public static ListAllBucketsResult fromPaths(List<Path> buckets) throws IOException {
    List<BucketInfo> bucketInfos =
        buckets.stream()
            .map(
                bucket -> {
                  try {
                    return BucketInfo.fromPath(bucket);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                })
            .collect(Collectors.toList());
    return new ListAllBucketsResult(bucketInfos);
  }
}
