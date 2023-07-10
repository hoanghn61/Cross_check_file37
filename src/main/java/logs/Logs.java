package logs;

import javax.swing.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class Logs {

    private Logs() {
        throw new IllegalStateException("Utility class");
    }
    private static final String PATH = "src/main/resources/Logs.txt";
    private static final File file = new File(PATH);

    public static void writeLog(String message) {
        try (FileWriter fileWriter = new FileWriter(file, true)) {
            if (!file.exists() && !file.createNewFile()) {
                throw new IOException("Could not create file");
            }
            long time = System.currentTimeMillis();
            java.time.Instant instant = java.time.Instant.ofEpochMilli(time);
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ISO_INSTANT;

            fileWriter.write(formatter.format(instant) + " " + message + "\n");
        } catch (IOException e) {
            JDialog dialog = new JDialog();
            dialog.setAlwaysOnTop(true);
            JOptionPane.showMessageDialog(dialog, "Could not write to log file.");
        }
    }
}
