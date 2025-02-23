package dev.totis.tinys3.logging;

import java.util.logging.Level;
import java.util.logging.Logger;

public class S3Logger {

  private static final Logger LOGGER = Logger.getLogger(S3Logger.class.getName());
  private static S3Logger INSTANCE;

  public S3Logger() {
    LOGGER.setLevel(Level.INFO);
  }

  public static S3Logger getInstance() {
    if (INSTANCE == null) {
      INSTANCE = new S3Logger();
    }
    return INSTANCE;
  }

  public void log(String message) {
    LOGGER.info(message);
  }
}
