package dev.totis.tinys3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.totis.tinys3.auth.S3Authenticator;
import dev.totis.tinys3.response.PostUploadResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class BucketUploadPostHandler {
  private final S3Authenticator authenticator;
  private final S3FileOperations fileOperations;

  public BucketUploadPostHandler(S3Authenticator authenticator, S3FileOperations fileOperations) {
    this.authenticator = authenticator;
    this.fileOperations = fileOperations;
  }

  public void handle(S3Context s3Context) throws IOException {
    String contentType = s3Context.getContentType();
    if (contentType == null || !contentType.startsWith("multipart/form-data")) {
      s3Context.sendResponse(400, "Invalid content type", "application/xml");
      return;
    }

    if (authenticator.authenticateRequest(s3Context)) {
      s3Context.sendResponse(403, "Invalid content type", "application/xml");
      return;
    }

    String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
    MultipartFormData formData = MultipartFormData.parse(s3Context.getPayload(), boundary);

    try {
      String date = formData.getField("x-amz-date");
      String signature = formData.getField("x-amz-signature");
      String algorithm = formData.getField("x-amz-algorithm");
      String credential = formData.getField("x-amz-credential").split("/")[0];

      byte[] decodedPolicy = Base64.getDecoder().decode(formData.getField("policy"));
      String policyJson = new String(decodedPolicy, StandardCharsets.UTF_8);
      String bucketName = getBucketNameFromPolicy(policyJson);

      if (!verifyPostPolicy(policyJson, signature, credential)) {
        s3Context.sendResponse(403, "Invalid policy or signature", "application/xml");
        return;
      }

      String etag =
          fileOperations.handlePutObject(bucketName, formData.fileName(), formData.fileData());
      s3Context.sendResponse(
          200,
          new PostUploadResult(bucketName, formData.fileName(), etag).toXML(),
          "application/xml");
    } catch (Exception e) {
      s3Context.sendResponse(500, "Failed to process upload: " + e.getMessage(), "application/xml");
    }
  }

  private record MultipartFormData(
      Map<String, String> formFields, byte[] fileData, String fileName) {

    public String getField(String name) {
      return formFields.get(name);
    }

    public static MultipartFormData parse(byte[] payload, String boundary) throws IOException {
      Map<String, String> formFields = new HashMap<>();
      byte[] fileData = null;
      String fileName = null;
      String endBoundary = "--" + boundary + "--";

      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(new ByteArrayInputStream(payload)))) {
        String line;
        StringBuilder currentValue = new StringBuilder();
        String currentHeader = null;
        boolean isFileContent = false;
        ByteArrayOutputStream fileContent = null;

        while ((line = reader.readLine()) != null) {
          if (line.startsWith("--" + boundary)) {
            // Save previous field if exists
            if (currentHeader != null && !isFileContent) {
              formFields.put(currentHeader, currentValue.toString().trim());
              currentValue = new StringBuilder();
            }
            currentHeader = null;
            isFileContent = false;

            if (line.equals(endBoundary)) {
              break;
            }
            continue;
          }

          if (line.startsWith("Content-Disposition:")) {
            String[] parts = line.split(";");
            for (String part : parts) {
              part = part.trim();
              if (part.startsWith("name=")) {
                currentHeader = part.substring(6, part.length() - 1);
              } else if (part.startsWith("filename=")) {
                fileName = part.substring(10, part.length() - 1);
                isFileContent = true;
                fileContent = new ByteArrayOutputStream();
              }
            }
            continue;
          }

          if (line.contains(":")) {
            continue;
          }

          if (currentHeader != null) {
            if (isFileContent && fileContent != null) {
              fileContent.write(line.getBytes());
            } else {
              currentValue.append(line).append("\n");
            }
          }
        }

        if (fileContent != null) {
          fileData = fileContent.toByteArray();
        }
      }

      return new MultipartFormData(formFields, fileData, fileName);
    }
  }

  private static boolean verifyPostPolicy(String policy, String signature, String accessKey) {
    // TODO: Implement policy verification
    return true;
  }

  private String getBucketNameFromPolicy(String policyStr) {
    try {
      ObjectMapper mapper = new ObjectMapper();
      JsonNode policy = mapper.readTree(policyStr);
      JsonNode conditions = policy.get("conditions");

      if (conditions.isArray()) {
        for (JsonNode condition : conditions) {
          if (condition.isArray()
              && condition.get(0).asText().equals("eq")
              && condition.get(1).asText().equals("$bucket")) {
            return condition.get(2).asText();
          }
        }
      }
    } catch (Exception e) {
      System.err.println("Failed to parse policy: " + e.getMessage());
    }
    return null;
  }
}
