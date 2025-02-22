package dev.totis.tinys3.response;

import static dev.totis.tinys3.S3Utils.createXMLStreamWriter;

import java.io.StringWriter;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

public record PostUploadResult(String bucketName, String key, String eTag) {
  public String toXML() {
    StringWriter writer = new StringWriter();
    XMLStreamWriter xml = createXMLStreamWriter(writer);
    try {
      xml.writeStartDocument();
      xml.writeStartElement("PostResponse");

      xml.writeStartElement("Location");
      xml.writeCharacters("https://" + bucketName + ".s3.amazonaws.com/" + key);
      xml.writeEndElement();

      xml.writeStartElement("Bucket");
      xml.writeCharacters(bucketName);
      xml.writeEndElement();

      xml.writeStartElement("Key");
      xml.writeCharacters(key);
      xml.writeEndElement();

      xml.writeStartElement("ETag");
      xml.writeCharacters("\"" + eTag + "\"");
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
