package com.tinys3.response;

import static com.tinys3.S3Utils.createXMLStreamWriter;

import java.io.StringWriter;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

public record InitiateMultipartUploadResult(String bucketName, String key, String uploadId) {
  public String toXML() {
    StringWriter writer = new StringWriter();
    XMLStreamWriter xml = createXMLStreamWriter(writer);
    try {
      xml.writeStartDocument();
      xml.writeStartElement("InitiateMultipartUploadResult");
      xml.writeStartElement("Bucket");
      xml.writeCharacters(bucketName);
      xml.writeEndElement();
      xml.writeStartElement("Key");
      xml.writeCharacters(key);
      xml.writeEndElement();
      xml.writeStartElement("UploadId");
      xml.writeCharacters(uploadId);
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
