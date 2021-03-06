package nl.yogh.aerius.server.util;

import java.io.File;

public class Files {
  private static final int TEMP_DIR_ATTEMPTS = 10000;

  public static File createTempDir() {
    final File baseDir = new File(System.getProperty("java.io.tmpdir"));
    final String baseName = System.currentTimeMillis() + "-";

    for (int counter = 0; counter < TEMP_DIR_ATTEMPTS; counter++) {
      final File tempDir = new File(baseDir, baseName + counter);
      if (tempDir.mkdir()) {
        return tempDir;
      }
    }
    throw new IllegalStateException("Failed to create directory within " + TEMP_DIR_ATTEMPTS + " attempts (tried " + baseName + "0 to " + baseName
        + (TEMP_DIR_ATTEMPTS - 1) + ')');
  }
}
