package dev.totis.tinys3;

import dev.totis.tinys3.io.StorageException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class DeleteObjectsPostHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(DeleteObjectsPostHandler.class);

  private final S3FileOperations fileOperations;

  public DeleteObjectsPostHandler(S3FileOperations fileOperations) {
    this.fileOperations = fileOperations;
  }

  public void handleDeleteObjects(S3Context s3Context) throws IOException, StorageException {
    try {
      if (!fileOperations.bucketExists(s3Context.getBucketName())) {
        s3Context.sendError(404, "NoSuchBucket");
        return;
      }

      DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      Document doc = builder.parse(new ByteArrayInputStream(s3Context.getPayload()));
      doc.getDocumentElement().normalize();

      NodeList objectNodes = doc.getElementsByTagName("Object");
      boolean isQuiet = doc.getElementsByTagName("Quiet") != null;

      List<DeleteError> errors = new ArrayList<>();
      List<Deleted> deleted = new ArrayList<>();

      for (int i = 0; i < objectNodes.getLength(); i++) {
        Element objectElement = (Element) objectNodes.item(i);
        String key = objectElement.getElementsByTagName("Key").item(0).getTextContent();

        try {
          if (fileOperations.objectNotExists(s3Context.getBucketName(), key)) {
            if (!isQuiet) {
              deleted.add(new Deleted(key));
            }
            continue;
          }

          fileOperations.handleDeleteObject(s3Context.getBucketName(), key);
          if (!isQuiet) {
            deleted.add(new Deleted(key));
          }
        } catch (Exception e) {
          errors.add(new DeleteError(key, "InternalError", e.getMessage()));
        }
      }

      StringBuilder response = new StringBuilder();
      response.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
      response.append("<DeleteResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");

      if (!isQuiet) {
        for (Deleted obj : deleted) {
          response.append("<Deleted>");
          response.append("<Key>").append(escapeXml(obj.key())).append("</Key>");
          response.append("</Deleted>");
        }
      }

      for (DeleteError error : errors) {
        response.append("<Error>");
        response.append("<Key>").append(escapeXml(error.key())).append("</Key>");
        response.append("<Code>").append(escapeXml(error.code())).append("</Code>");
        response.append("<Message>").append(escapeXml(error.message())).append("</Message>");
        response.append("</Error>");
      }

      response.append("</DeleteResult>");

      s3Context.sendResponse(200, response.toString(), "application/xml");
    } catch (Exception e) {
      LOGGER.error("Error processing DeleteObjects request", e);
      s3Context.sendError(400, "MalformedXML");
    }
  }

  private record Deleted(String key) {}

  private record DeleteError(String key, String code, String message) {}

  private String escapeXml(String input) {
    if (input == null) return "";
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}
