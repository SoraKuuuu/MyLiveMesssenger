import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/**
 * ============================================================================
 * ChatClient (Direct Messaging & Unlimited Streaming Network Layer)
 * ============================================================================
 *
 * Direct Messaging Routing:
 * -------------------------
 * Outgoing messages and files can specify a `recipient` ("GROUP" or a specific
 * username). The server routes messages privately to the target recipient.
 * Incoming messages report both `sender` and `recipient`, enabling the GUI to
 * organize conversations into separate private and group channels.
 * ============================================================================
 */
public class ChatClient {

    public static final String GROUP_TARGET = "GROUP";

    public static final byte TYPE_TEXT               = 1;
    public static final byte TYPE_IMAGE              = 2;
    public static final byte TYPE_FILE               = 3;
    public static final byte TYPE_AUDIO              = 4;
    public static final byte TYPE_SYSTEM             = 5;
    public static final byte TYPE_USER_LIST          = 6;
    public static final byte TYPE_DOWNLOAD_REQ       = 7;
    public static final byte TYPE_DOWNLOAD_DATA      = 8;
    public static final byte TYPE_FILE_UPLOAD_START  = 9;
    public static final byte TYPE_FILE_CHUNK         = 10;
    public static final byte TYPE_FILE_CANCEL        = 11;
    public static final byte TYPE_CALL_SIGNAL        = 12; // Voice / Video Call signaling
    public static final int MEDIA_PORT               = 5001; // UDP Media Relay Port

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private Thread listenerThread;
    private volatile boolean isRunning = false;
    private volatile boolean cancelUploadRequested = false;

    private String currentUsername;
    private String serverIp;
    private int serverPort;

    private final ExecutorService transferExecutor = Executors.newCachedThreadPool();
    private final List<ChatClientListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, ActiveDownload> pendingDownloads = new ConcurrentHashMap<>();

    private static class ActiveDownload {
        final File destinationFile;
        final ProgressListener callback;

        ActiveDownload(File destinationFile, ProgressListener callback) {
            this.destinationFile = destinationFile;
            this.callback = callback;
        }
    }

    public interface ChatClientListener {
        void onConnected(String serverIp, int port, String username);
        void onConnectionFailed(String errorMessage);
        void onDisconnected(String reason);
        void onTextMessageReceived(String sender, String recipient, String timestamp, String text, boolean isMe);
        void onImageMessageReceived(String sender, String recipient, String timestamp, String fileName, long fileSize, String fileId, byte[] thumbnail, boolean isMe);
        void onFileMessageReceived(String sender, String recipient, String timestamp, String fileName, long fileSize, String fileId, boolean isMe);
        void onAudioMessageReceived(String sender, String recipient, String timestamp, String fileName, long fileSize, String fileId, boolean isMe);
        void onSystemMessage(String timestamp, String message, String recipient);
        void onUserListUpdated(List<String> onlineUsers);
        void onCallSignalReceived(String sender, String recipient, String timestamp, String signalType, String payload);
    }

    public interface ProgressListener {
        void onProgress(int percentage, long bytesTransferred, long totalBytes);
        void onComplete(String fileName);
        void onError(String fileName, Exception ex);
    }

    public void addListener(ChatClientListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(ChatClientListener listener) {
        listeners.remove(listener);
    }

    public void connectAsync(String host, int port, String displayName) {
        new Thread(() -> {
            try {
                this.serverIp = (host == null || host.trim().isEmpty()) ? "127.0.0.1" : host.trim();
                this.serverPort = (port <= 0) ? 5000 : port;
                this.currentUsername = (displayName == null || displayName.trim().isEmpty())
                        ? "User" + (int)(Math.random() * 1000)
                        : displayName.trim();

                socket = new Socket(serverIp, serverPort);
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

                synchronized (out) {
                    out.writeUTF(currentUsername);
                    out.flush();
                }

                isRunning = true;
                startListenerThread();

                for (ChatClientListener listener : listeners) {
                    listener.onConnected(serverIp, serverPort, currentUsername);
                }

            } catch (IOException ex) {
                disconnect();
                for (ChatClientListener listener : listeners) {
                    listener.onConnectionFailed("Could not connect to " + host + ":" + port + " (" + ex.getMessage() + ")");
                }
            }
        }, "ChatClient-ConnectThread").start();
    }

    public boolean isConnected() {
        return isRunning && socket != null && !socket.isClosed() && socket.isConnected();
    }

    public String getCurrentUsername() { return currentUsername; }
    public String getServerIp() { return serverIp; }
    public int getServerPort() { return serverPort; }

    public void disconnect() {
        if (!isRunning) return;
        isRunning = false;

        try {
            if (out != null && socket != null && !socket.isClosed()) {
                synchronized (out) {
                    out.writeByte(TYPE_TEXT);
                    out.writeUTF(GROUP_TARGET);
                    out.writeUTF("/quit");
                    out.flush();
                }
            }
        } catch (IOException ignored) {}

        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}

        socket = null;
        in = null;
        out = null;

        for (ChatClientListener listener : listeners) {
            listener.onDisconnected("Disconnected from server.");
        }
    }

    public void cancelCurrentUpload() {
        this.cancelUploadRequested = true;
    }

    // ========================================================================
    // Receiver Thread
    // ========================================================================
    private void startListenerThread() {
        listenerThread = new Thread(() -> {
            try {
                while (isRunning) {
                    byte type = in.readByte();

                    if (type == TYPE_SYSTEM) {
                        String sender = in.readUTF();
                        String recipient = in.readUTF();
                        String timestamp = in.readUTF();
                        String meta = in.readUTF();
                        for (ChatClientListener listener : listeners) {
                            listener.onSystemMessage(timestamp, meta, recipient);
                        }

                    } else if (type == TYPE_USER_LIST) {
                        String sender = in.readUTF();
                        String recipient = in.readUTF();
                        String timestamp = in.readUTF();
                        String meta = in.readUTF();
                        List<String> users = new ArrayList<>();
                        if (meta != null && !meta.trim().isEmpty()) {
                            for (String u : meta.split(",")) {
                                String c = u.trim();
                                if (!c.isEmpty()) users.add(c);
                            }
                        }
                        for (ChatClientListener listener : listeners) {
                            listener.onUserListUpdated(users);
                        }

                    } else if (type == TYPE_TEXT) {
                        String sender = in.readUTF();
                        String recipient = in.readUTF();
                        String timestamp = in.readUTF();
                        String text = in.readUTF();
                        boolean isMe = sender != null && sender.equalsIgnoreCase(currentUsername);
                        for (ChatClientListener listener : listeners) {
                            listener.onTextMessageReceived(sender, recipient, timestamp, text, isMe);
                        }

                    } else if (type == TYPE_IMAGE || type == TYPE_FILE || type == TYPE_AUDIO) {
                        String sender = in.readUTF();
                        String recipient = in.readUTF();
                        String timestamp = in.readUTF();
                        String fileName = in.readUTF();
                        long fileSize = in.readLong();
                        String fileId = in.readUTF();

                        int thumbLen = in.readInt();
                        byte[] thumb = new byte[thumbLen];
                        if (thumbLen > 0) {
                            in.readFully(thumb);
                        }

                        boolean isMe = sender != null && sender.equalsIgnoreCase(currentUsername);

                        if (type == TYPE_IMAGE) {
                            for (ChatClientListener listener : listeners) {
                                listener.onImageMessageReceived(sender, recipient, timestamp, fileName, fileSize, fileId, thumb, isMe);
                            }
                        } else if (type == TYPE_FILE) {
                            for (ChatClientListener listener : listeners) {
                                listener.onFileMessageReceived(sender, recipient, timestamp, fileName, fileSize, fileId, isMe);
                            }
                        } else {
                            for (ChatClientListener listener : listeners) {
                                listener.onAudioMessageReceived(sender, recipient, timestamp, fileName, fileSize, fileId, isMe);
                            }
                        }

                    } else if (type == TYPE_DOWNLOAD_DATA) {
                        String fileId = in.readUTF();
                        long fileSize = in.readLong();

                        ActiveDownload download = pendingDownloads.remove(fileId);
                        if (download != null) {
                            byte[] buffer = new byte[65536];
                            long remaining = fileSize;
                            long totalReceived = 0;

                            try (FileOutputStream fos = new FileOutputStream(download.destinationFile)) {
                                while (remaining > 0) {
                                    int toRead = (int) Math.min(buffer.length, remaining);
                                    int read = in.read(buffer, 0, toRead);
                                    if (read == -1) throw new EOFException("Connection interrupted during download.");
                                    fos.write(buffer, 0, read);
                                    remaining -= read;
                                    totalReceived += read;

                                    if (download.callback != null) {
                                        int pct = (int) ((totalReceived * 100L) / fileSize);
                                        download.callback.onProgress(pct, totalReceived, fileSize);
                                    }
                                }
                            }

                            if (download.callback != null) {
                                download.callback.onComplete(download.destinationFile.getName());
                            }
                        } else {
                            long remaining = fileSize;
                            byte[] skipBuf = new byte[65536];
                            while (remaining > 0) {
                                int toRead = (int) Math.min(skipBuf.length, remaining);
                                int read = in.read(skipBuf, 0, toRead);
                                if (read == -1) break;
                                remaining -= read;
                            }
                        }

                    } else if (type == TYPE_CALL_SIGNAL) {
                        String sender = in.readUTF();
                        String recipient = in.readUTF();
                        String timestamp = in.readUTF();
                        String signalType = in.readUTF();
                        String payload = in.readUTF();
                        for (ChatClientListener listener : listeners) {
                            listener.onCallSignalReceived(sender, recipient, timestamp, signalType, payload);
                        }
                    }
                }
            } catch (IOException ex) {
                if (isRunning) disconnect();
            }
        }, "ChatClient-ReceiverThread");

        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    // ========================================================================
    // Outgoing Operations (Direct Messaging & Unlimited Streaming)
    // ========================================================================

    public void sendTextMessage(String recipient, String message) throws IOException {
        if (!isConnected()) throw new IOException("Not connected to server.");
        if (message == null || message.trim().isEmpty()) return;
        String target = (recipient == null || recipient.trim().isEmpty()) ? GROUP_TARGET : recipient.trim();

        synchronized (out) {
            out.writeByte(TYPE_TEXT);
            out.writeUTF(target);
            out.writeUTF(message.trim());
            out.flush();
        }
    }

    public void sendTextMessage(String message) throws IOException {
        sendTextMessage(GROUP_TARGET, message);
    }

    /**
     * Transmits a voice or video call signaling message to a target peer.
     *
     * @param recipient the target username
     * @param signalType e.g. INVITE_AUDIO, INVITE_VIDEO, RINGING, ACCEPT, REJECT, BUSY, END
     * @param payload optional payload (e.g. metadata or port info)
     */
    public void sendCallSignal(String recipient, String signalType, String payload) {
        if (!isConnected()) return;
        transferExecutor.submit(() -> {
            try {
                synchronized (out) {
                    out.writeByte(TYPE_CALL_SIGNAL);
                    out.writeUTF(recipient);
                    out.writeUTF(signalType != null ? signalType : "");
                    out.writeUTF(payload != null ? payload : "");
                    out.flush();
                }
            } catch (IOException ex) {
                System.err.println("Failed to transmit call signal [" + signalType + "]: " + ex.getMessage());
            }
        });
    }

    public void sendFileChunked(byte type, String recipient, File file, ProgressListener callback) {
        if (!isConnected()) {
            if (callback != null) callback.onError(file.getName(), new IOException("Not connected."));
            return;
        }

        cancelUploadRequested = false;
        String target = (recipient == null || recipient.trim().isEmpty()) ? GROUP_TARGET : recipient.trim();

        transferExecutor.submit(() -> {
            try {
                long totalSize = file.length();

                byte[] thumb = new byte[0];
                if (type == TYPE_IMAGE && isImageFile(file.getName())) {
                    thumb = generateThumbnailBytes(file);
                }

                synchronized (out) {
                    out.writeByte(TYPE_FILE_UPLOAD_START);
                    out.writeByte(type);
                    out.writeUTF(target);
                    out.writeUTF(file.getName());
                    out.writeLong(totalSize);
                    out.writeInt(thumb.length);
                    if (thumb.length > 0) {
                        out.write(thumb);
                    }
                    out.flush();

                    byte[] buffer = new byte[65536];
                    try (FileInputStream fis = new FileInputStream(file)) {
                        long totalSent = 0;
                        int bytesRead;

                        while ((bytesRead = fis.read(buffer)) != -1) {
                            if (cancelUploadRequested) {
                                out.writeByte(TYPE_FILE_CANCEL);
                                out.flush();
                                if (callback != null) {
                                    callback.onError(file.getName(), new IOException("Upload cancelled by user."));
                                }
                                return;
                            }

                            totalSent += bytesRead;
                            boolean isLast = (totalSent >= totalSize);

                            out.writeByte(TYPE_FILE_CHUNK);
                            out.writeInt(bytesRead);
                            out.write(buffer, 0, bytesRead);
                            out.writeBoolean(isLast);
                            out.flush();

                            if (callback != null) {
                                int pct = (int) ((totalSent * 100L) / totalSize);
                                callback.onProgress(pct, totalSent, totalSize);
                            }
                        }
                    }
                }

                if (callback != null) {
                    callback.onComplete(file.getName());
                }

            } catch (Exception ex) {
                if (callback != null) {
                    callback.onError(file.getName(), ex);
                }
            }
        });
    }

    public void sendFileChunked(byte type, File file, ProgressListener callback) {
        sendFileChunked(type, GROUP_TARGET, file, callback);
    }

    public void sendAudioBytes(String recipient, byte[] wavBytes, ProgressListener callback) {
        if (!isConnected()) {
            if (callback != null) callback.onError("voice_message.wav", new IOException("Not connected."));
            return;
        }

        cancelUploadRequested = false;
        String target = (recipient == null || recipient.trim().isEmpty()) ? GROUP_TARGET : recipient.trim();

        transferExecutor.submit(() -> {
            try {
                long totalSize = wavBytes.length;

                synchronized (out) {
                    out.writeByte(TYPE_FILE_UPLOAD_START);
                    out.writeByte(TYPE_AUDIO);
                    out.writeUTF(target);
                    out.writeUTF("voice_message.wav");
                    out.writeLong(totalSize);
                    out.writeInt(0);
                    out.flush();

                    byte[] buffer = new byte[16384];
                    try (ByteArrayInputStream bais = new ByteArrayInputStream(wavBytes)) {
                        long totalSent = 0;
                        int r;
                        while ((r = bais.read(buffer)) != -1) {
                            if (cancelUploadRequested) {
                                out.writeByte(TYPE_FILE_CANCEL);
                                out.flush();
                                if (callback != null) callback.onError("voice_message.wav", new IOException("Upload cancelled."));
                                return;
                            }

                            totalSent += r;
                            boolean isLast = (totalSent >= totalSize);

                            out.writeByte(TYPE_FILE_CHUNK);
                            out.writeInt(r);
                            out.write(buffer, 0, r);
                            out.writeBoolean(isLast);
                            out.flush();

                            if (callback != null) {
                                int pct = (int) ((totalSent * 100L) / totalSize);
                                callback.onProgress(pct, totalSent, totalSize);
                            }
                        }
                    }
                }

                if (callback != null) {
                    callback.onComplete("voice_message.wav");
                }

            } catch (Exception ex) {
                if (callback != null) {
                    callback.onError("voice_message.wav", ex);
                }
            }
        });
    }

    public void sendAudioBytes(byte[] wavBytes, ProgressListener callback) {
        sendAudioBytes(GROUP_TARGET, wavBytes, callback);
    }

    public void downloadFile(String fileId, File destinationFile, ProgressListener callback) {
        if (!isConnected()) {
            if (callback != null) callback.onError(destinationFile.getName(), new IOException("Not connected."));
            return;
        }

        pendingDownloads.put(fileId, new ActiveDownload(destinationFile, callback));

        transferExecutor.submit(() -> {
            try {
                synchronized (out) {
                    out.writeByte(TYPE_DOWNLOAD_REQ);
                    out.writeUTF(fileId);
                    out.flush();
                }
            } catch (IOException ex) {
                pendingDownloads.remove(fileId);
                if (callback != null) callback.onError(destinationFile.getName(), ex);
            }
        });
    }

    private static boolean isImageFile(String name) {
        String l = name.toLowerCase();
        return l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".gif") || l.endsWith(".webp");
    }

    private static byte[] generateThumbnailBytes(File file) {
        try {
            BufferedImage orig = ImageIO.read(file);
            if (orig == null) return new byte[0];

            int maxDim = 240;
            int w = orig.getWidth();
            int h = orig.getHeight();
            if (w > maxDim || h > maxDim) {
                if (w > h) {
                    h = (h * maxDim) / w;
                    w = maxDim;
                } else {
                    w = (w * maxDim) / h;
                    h = maxDim;
                }
            }

            BufferedImage thumb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            var g2 = thumb.createGraphics();
            g2.drawImage(orig.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null);
            g2.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(thumb, "jpg", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
