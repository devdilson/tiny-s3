package dev.totis.tinys3.response;

import static dev.totis.tinys3.S3Utils.LAST_MODIFIED_FORMATTER;
import static dev.totis.tinys3.S3Utils.createXMLStreamWriter;

import dev.totis.tinys3.io.FileEntry;
import java.io.StringWriter;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

public record ListAllBucketsResult(List<BucketInfo> buckets) {

  public record BucketInfo(String name, FileTime creationTime, String accessKey) {}

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
        xml.writeCharacters(LAST_MODIFIED_FORMATTER.format(bucket.creationTime().toInstant()));
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

  public static ListAllBucketsResult fromBuckets(FileEntry[] buckets, String accessKey) {
    List<BucketInfo> bucketInfos =
        Arrays.stream(buckets).toList().stream()
            .map(
                bucket ->
                    new BucketInfo(
                        bucket.path(), FileTime.fromMillis(bucket.lastModified()), accessKey))
            .collect(Collectors.toList());
    return new ListAllBucketsResult(bucketInfos);
  }
}
