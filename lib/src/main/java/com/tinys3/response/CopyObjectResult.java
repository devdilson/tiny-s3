package com.tinys3.response;

import static com.tinys3.S3Utils.*;

import com.tinys3.S3FileOperations;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.attribute.FileTime;
import java.util.List;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

public record CopyObjectResult(String bucketName, String bucketKey, S3FileOperations s3Operations) {

  public String toXML() {
    StringWriter writer = new StringWriter();
    XMLStreamWriter xml = createXMLStreamWriter(writer);
    try {
      xml.writeStartDocument();
      xml.writeStartElement("CopyObjectResult");

      xml.writeStartElement("LastModified");
      FileTime lastModifiedTime = s3Operations.getLastModifiedTime(bucketName, bucketKey);
      String lastModified = LAST_MODIFIED_FORMATTER.format(lastModifiedTime.toInstant());
      xml.writeCharacters(lastModified);
      xml.writeEndElement();

      xml.writeStartElement("ETag");
      xml.writeCharacters(
          "\"" + s3Operations.calculateETag(bucketName, bucketKey, false, List.of()) + "\"");
      xml.writeEndElement();

      xml.writeEndElement();
      xml.writeEndDocument();

      return writer.toString();
    } catch (XMLStreamException | IOException e) {
      throw new RuntimeException(e);
    } finally {
      try {
        xml.close();
      } catch (XMLStreamException e) {
        e.printStackTrace();
      }
    }
  }
}
