import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages persistent chat history for group and direct messaging conversations.
 * Stores conversations in 'storage/history/<username>/<target>.history'.
 */
public class HistoryManager {

    private static final File BASE_DIR = new File("storage/history");

    public enum EntryType {
        TEXT, IMAGE, FILE, AUDIO, SYSTEM
    }

    public static class HistoryEntry {
        public final EntryType type;
        public final String timestamp;
        public final String sender;
        public final String content;
        public final long size;
        public final String fileId;

        public HistoryEntry(EntryType type, String timestamp, String sender, String content, long size, String fileId) {
            this.type = type;
            this.timestamp = timestamp;
            this.sender = sender;
            this.content = content;
            this.size = size;
            this.fileId = fileId;
        }
    }

    private static File getHistoryFile(String myName, String target) {
        String safeUser = (myName == null || myName.isBlank()) ? "default" : myName.replaceAll("[^a-zA-Z0-9_.-]", "_");
        String safeTarget = (target == null || target.isBlank()) ? "GROUP" : target.replaceAll("[^a-zA-Z0-9_.-]", "_");
        File dir = new File(BASE_DIR, safeUser);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, safeTarget + ".history");
    }

    public static synchronized void append(String myName, String target, HistoryEntry entry) {
        File file = getHistoryFile(myName, target);
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            String encodedContent = entry.content == null ? "" : entry.content
                    .replace("\\", "\\\\")
                    .replace("\n", "\\n")
                    .replace("\r", "")
                    .replace("|", "\\p");
            pw.printf("%s|%s|%s|%s|%d|%s%n",
                    entry.type.name(),
                    entry.timestamp == null ? "" : entry.timestamp,
                    entry.sender == null ? "" : entry.sender,
                    encodedContent,
                    entry.size,
                    entry.fileId == null ? "" : entry.fileId);
        } catch (IOException e) {
            System.err.println("Error saving chat history: " + e.getMessage());
        }
    }

    public static List<HistoryEntry> load(String myName, String target) {
        List<HistoryEntry> list = new ArrayList<>();
        File file = getHistoryFile(myName, target);
        if (!file.exists()) return list;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length >= 6) {
                    try {
                        EntryType type = EntryType.valueOf(parts[0]);
                        String timestamp = parts[1];
                        String sender = parts[2];
                        String content = parts[3]
                                .replace("\\p", "|")
                                .replace("\\n", "\n")
                                .replace("\\\\", "\\");
                        long size = 0;
                        try {
                            size = Long.parseLong(parts[4]);
                        } catch (NumberFormatException ignored) {}
                        String fileId = parts[5];
                        list.add(new HistoryEntry(type, timestamp, sender, content, size, fileId));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading chat history: " + e.getMessage());
        }
        return list;
    }
}
