package com.tinys3.response;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.tinys3.S3Utils.calculateETag;
import static com.tinys3.S3Utils.createXMLStreamWriter;

public record CopyObjectResult(Path destObjectPath) {
  private static final DateTimeFormatter LAST_MODIFIED_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  public String toXML() {
    StringWriter writer = new StringWriter();
    XMLStreamWriter xml = createXMLStreamWriter(writer);
    try {
      xml.writeStartDocument();
      xml.writeStartElement("CopyObjectResult");

      xml.writeStartElement("LastModified");
      String lastModified =
          LAST_MODIFIED_FORMATTER.format(Files.getLastModifiedTime(destObjectPath).toInstant());
      xml.writeCharacters(lastModified);
      xml.writeEndElement();

      xml.writeStartElement("ETag");
      xml.writeCharacters("\"" + calculateETag(destObjectPath, false, List.of()) + "\"");
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
