package dev.totis.tinys3.response;

import static dev.totis.tinys3.S3Utils.calculateETag;
import static dev.totis.tinys3.S3Utils.createXMLStreamWriter;

import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

public record CompleteMultipartUploadResult(
    String bucketName, String key, long size, String eTag, Path finalPath, List<String> eTags) {
  public String toXML() {
    StringWriter writer = new StringWriter();
    XMLStreamWriter xml = createXMLStreamWriter(writer);
    try {
      xml.writeStartDocument();
      xml.writeStartElement("CompleteMultipartUploadResult");
      xml.writeStartElement("Location");
      xml.writeCharacters("/" + bucketName + "/" + key);
      xml.writeEndElement();
      xml.writeStartElement("Bucket");
      xml.writeCharacters(bucketName);
      xml.writeEndElement();
      xml.writeStartElement("Key");
      xml.writeCharacters(key);
      xml.writeEndElement();
      xml.writeStartElement("Size");
      xml.writeCharacters(String.valueOf(size));
      xml.writeEndElement();
      xml.writeStartElement("ETag");
      xml.writeCharacters(calculateETag(finalPath, true, eTags));
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
}
