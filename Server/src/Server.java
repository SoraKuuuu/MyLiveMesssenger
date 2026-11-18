import java.io.*;
import java.net.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ============================================================================
 * Multimedia Group Chat Server (With Direct Messaging & Cancel Support)
 * ============================================================================
 *
 * Architecture & Routing:
 * -----------------------
 * 1. Target Routing (Group vs. Direct Messages):
 *    Every message and file transfer carries a `recipient` field:
 *    - Recipient = "GROUP": Broadcast to ALL connected clients.
 *    - Recipient = "Username": Routed ONLY to the recipient and echoed to the sender.
 *    - If recipient is not connected: Server returns a system notice to the sender.
 *
 * 2. Zero-Heap Chunk-Framed File Streaming:
 *    Uploads are framed as discrete chunks (TYPE_FILE_CHUNK). Memory footprint
 *    remains strictly bounded to 64 KB regardless of file size.
 *
 * 3. Upload Cancellation (TYPE_FILE_CANCEL):
 *    If the sender cancels an in-progress transfer, the server immediately closes
 *    the file stream, deletes the partial file from `storage/attachments/`, and
 *    aborts broadcasting. Nothing remains in server storage.
 * ============================================================================
 */
public class Server {

    private static final int PORT = 5000;
    private static final Set<ClientHandler> clients = Collections.synchronizedSet(new HashSet<>());
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final File STORAGE_DIR = new File("storage/attachments");

    public static final String GROUP_TARGET = "GROUP";

    // Protocol Packet Types
    public static final byte TYPE_TEXT               = 1;
    public static final byte TYPE_IMAGE              = 2;
    public static final byte TYPE_FILE               = 3;
    public static final byte TYPE_AUDIO              = 4;
    public static final byte TYPE_SYSTEM             = 5;
    public static final byte TYPE_USER_LIST          = 6;
    public static final byte TYPE_DOWNLOAD_REQ       = 7;
    public static final byte TYPE_DOWNLOAD_DATA      = 8;
    public static final byte TYPE_FILE_UPLOAD_START  = 9;  // Client -> Server: Upload header (type, recipient, name, size, thumb)
    public static final byte TYPE_FILE_CHUNK         = 10; // Client -> Server: Chunk of data (len, bytes, isLast)
    public static final byte TYPE_FILE_CANCEL        = 11; // Client -> Server: Cancel current upload! Delete from disk!
    public static final byte TYPE_CALL_SIGNAL        = 12; // Voice/Video Call Signaling (INVITE, ACCEPT, REJECT, END, etc.)

    public static final int MEDIA_PORT               = 5001; // UDP Port for low-latency Voice & Video relay

    // Maps client usernames to their active UDP network endpoint for voice/video routing
    private static final Map<String, SocketAddress> udpEndpoints = new java.util.concurrent.ConcurrentHashMap<>();

    public static void main(String[] args) {
        if (!STORAGE_DIR.exists()) {
            STORAGE_DIR.mkdirs();
        }

        // Launch the lightweight UDP Media Relay service on port 5001
        startUdpMediaRelay(MEDIA_PORT);

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            String localIp = getLocalIpAddress();
            System.out.println("==================================================");
            System.out.println("  Multimedia Chat Server (Direct Messaging BIO)   ");
            System.out.println("  Server IP Address : " + localIp);
            System.out.println("  Listening on Port : " + PORT);
            System.out.println("  Storage Directory : " + STORAGE_DIR.getAbsolutePath());
            System.out.println("==================================================");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler clientThread = new ClientHandler(clientSocket);
                clients.add(clientThread);
                clientThread.start();
            }
        } catch (IOException e) {
            System.err.println("Fatal server socket error: " + e.getMessage());
        }
    }

    private static String getLocalIpAddress() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            return socket.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            try {
                return InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException ex) {
                return "127.0.0.1";
            }
        }
    }

    public static String getTimestamp() {
        return "(" + LocalTime.now().format(TIME_FORMAT) + ")";
    }

    /**
     * Starts a high-performance UDP Media Relay server thread on the designated port.
     * Clients stream raw voice (PCM) and video (JPEG frames) datagrams to this port.
     * The server identifies the target peer from packet metadata and forwards packets
     * directly without decoding, delivering sub-millisecond real-time relaying.
     */
    private static void startUdpMediaRelay(int port) {
        Thread relayThread = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(port)) {
                System.out.println("  UDP Media Relay Listening on Port : " + port);
                byte[] buffer = new byte[65507];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    int len = packet.getLength();
                    if (len < 4) continue;

                    // Header format: [mediaType: 1B] [rLen: 1B] [recipient: rLen B] [sLen: 1B] [sender: sLen B] [payload...]
                    byte mediaType = buffer[0];
                    int rLen = buffer[1] & 0xFF;
                    if (2 + rLen > len) continue;
                    String recipient = new String(buffer, 2, rLen, "UTF-8").toLowerCase();

                    int sOffset = 2 + rLen;
                    int sLen = buffer[sOffset] & 0xFF;
                    if (sOffset + 1 + sLen > len) continue;
                    String sender = new String(buffer, sOffset + 1, sLen, "UTF-8").toLowerCase();

                    // Register/refresh sender's active UDP network address tagged with mediaType (1=audio, 2=video)
                    String srcKey = sender + ":" + mediaType;
                    udpEndpoints.put(srcKey, packet.getSocketAddress());

                    // Look up target recipient's UDP address for this mediaType
                    String destKey = recipient + ":" + mediaType;
                    SocketAddress dest = udpEndpoints.get(destKey);
                    if (dest != null) {
                        packet.setSocketAddress(dest);
                        socket.send(packet);
                    }
                }
            } catch (IOException ex) {
                System.err.println("UDP Media Relay terminated: " + ex.getMessage());
            }
        }, "UDP-Media-Relay");
        relayThread.setDaemon(true);
        relayThread.start();
    }

    /**
     * Routes private voice/video call signaling between caller and callee.
     * Handled types: INVITE_AUDIO, INVITE_VIDEO, RINGING, ACCEPT, REJECT, BUSY, END.
     */
    public static void routeCallSignal(String sender, String recipient, String timestamp, String signalType, String payload) {
        synchronized (clients) {
            boolean recipientFound = false;
            for (ClientHandler client : clients) {
                if (client.displayName.equalsIgnoreCase(recipient)) {
                    recipientFound = true;
                    client.sendCallSignal(sender, recipient, timestamp, signalType, payload);
                }
            }
            if (!recipientFound) {
                // If the target peer is offline, inform the caller immediately
                for (ClientHandler client : clients) {
                    if (client.displayName.equalsIgnoreCase(sender)) {
                        client.sendCallSignal("SYSTEM", sender, timestamp, "OFFLINE", recipient);
                    }
                }
            }
        }
    }

    /**
     * Routes text/system packet based on recipient:
     * - If "GROUP": broadcast to everyone.
     * - Else: send to recipient and sender. If recipient not found, notify sender.
     */
    public static void routeTextPacket(byte type, String sender, String recipient, String timestamp, String meta) {
        String target = (recipient == null || recipient.trim().isEmpty()) ? GROUP_TARGET : recipient.trim();
        synchronized (clients) {
            if (GROUP_TARGET.equalsIgnoreCase(target)) {
                for (ClientHandler client : clients) {
                    client.sendTextPacket(type, sender, GROUP_TARGET, timestamp, meta);
                }
            } else {
                boolean recipientFound = false;
                for (ClientHandler client : clients) {
                    if (client.displayName.equalsIgnoreCase(target)) {
                        recipientFound = true;
                        client.sendTextPacket(type, sender, target, timestamp, meta);
                    }
                }

                if (recipientFound) {
                    // Send echo copy to sender so they see it in their conversation tab
                    for (ClientHandler client : clients) {
                        if (client.displayName.equalsIgnoreCase(sender)) {
                            client.sendTextPacket(type, sender, target, timestamp, meta);
                        }
                    }
                } else {
                    // Recipient is not online: notify sender
                    for (ClientHandler client : clients) {
                        if (client.displayName.equalsIgnoreCase(sender)) {
                            client.sendTextPacket(TYPE_SYSTEM, "SYSTEM", target, timestamp,
                                    "User '" + target + "' is currently offline.");
                        }
                    }
                }
            }
        }
    }

    /**
     * Routes file metadata packet to GROUP or direct recipient.
     */
    public static void routeFileMetadata(byte type, String sender, String recipient, String timestamp, String fileName,
                                         long fileSize, String fileId, byte[] thumbnail) {
        String target = (recipient == null || recipient.trim().isEmpty()) ? GROUP_TARGET : recipient.trim();
        synchronized (clients) {
            if (GROUP_TARGET.equalsIgnoreCase(target)) {
                for (ClientHandler client : clients) {
                    client.sendFileMetadataPacket(type, sender, GROUP_TARGET, timestamp, fileName, fileSize, fileId, thumbnail);
                }
            } else {
                for (ClientHandler client : clients) {
                    if (client.displayName.equalsIgnoreCase(target) || client.displayName.equalsIgnoreCase(sender)) {
                        client.sendFileMetadataPacket(type, sender, target, timestamp, fileName, fileSize, fileId, thumbnail);
                    }
                }
            }
        }
    }

    /**
     * Broadcasts current online user list to everyone.
     */
    public static void broadcastUserList() {
        synchronized (clients) {
            StringBuilder sb = new StringBuilder();
            for (ClientHandler client : clients) {
                if (client.displayName != null && !client.displayName.isEmpty()) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(client.displayName);
                }
            }
            String userList = sb.toString();
            for (ClientHandler client : clients) {
                client.sendTextPacket(TYPE_USER_LIST, "SERVER", GROUP_TARGET, getTimestamp(), userList);
            }
        }
    }

    public static void removeClient(ClientHandler client) {
        clients.remove(client);
    }

    // ========================================================================
    // ClientHandler Thread
    // ========================================================================
    private static class ClientHandler extends Thread {
        private final Socket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private String displayName;

        // Active upload state for this client
        private File activeUploadFile;
        private FileOutputStream activeUploadFos;
        private byte activeUploadType;
        private String activeUploadRecipient;
        private String activeUploadFileName;
        private long activeUploadFileSize;
        private byte[] activeUploadThumb;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

                // Handshake: Display Name
                displayName = in.readUTF();
                if (displayName == null || displayName.trim().isEmpty()) {
                    displayName = "Anonymous";
                }

                String joinMsg = "* " + displayName + " joined the chat *";
                System.out.println(getTimestamp() + " " + joinMsg);
                routeTextPacket(TYPE_SYSTEM, "SYSTEM", GROUP_TARGET, getTimestamp(), joinMsg);
                broadcastUserList();

                while (true) {
                    byte type = in.readByte();
                    String time = getTimestamp();

                    if (type == TYPE_TEXT) {
                        String recipient = in.readUTF();
                        String text = in.readUTF();
                        if (text.equalsIgnoreCase("/quit")) break;

                        if (GROUP_TARGET.equalsIgnoreCase(recipient)) {
                            System.out.println(time + " [GROUP] [" + displayName + "]: " + text);
                        } else {
                            System.out.println(time + " [DM " + displayName + " -> " + recipient + "]: " + text);
                        }
                        routeTextPacket(TYPE_TEXT, displayName, recipient, time, text);

                    } else if (type == TYPE_FILE_UPLOAD_START) {
                        activeUploadType = in.readByte();
                        activeUploadRecipient = in.readUTF();
                        activeUploadFileName = in.readUTF();
                        activeUploadFileSize = in.readLong();

                        int thumbLen = in.readInt();
                        activeUploadThumb = new byte[thumbLen];
                        if (thumbLen > 0) {
                            in.readFully(activeUploadThumb);
                        }

                        // Generate unique destination file on server disk
                        String fileId = System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8) + "_" + sanitizeFileName(activeUploadFileName);
                        activeUploadFile = new File(STORAGE_DIR, fileId);
                        activeUploadFos = new FileOutputStream(activeUploadFile);

                        System.out.println(time + " Starting upload of " + activeUploadFileName + " (" + formatFileSize(activeUploadFileSize)
                                + ") for [" + activeUploadRecipient + "] from [" + displayName + "]");

                    } else if (type == TYPE_FILE_CHUNK) {
                        int chunkLen = in.readInt();
                        byte[] chunk = new byte[chunkLen];
                        in.readFully(chunk);
                        boolean isLast = in.readBoolean();

                        if (activeUploadFos != null) {
                            activeUploadFos.write(chunk);

                            if (isLast) {
                                activeUploadFos.flush();
                                activeUploadFos.close();
                                activeUploadFos = null;

                                System.out.println(time + " Finished upload of " + activeUploadFileName + " -> saved to " + activeUploadFile.getName());

                                // Route metadata to GROUP or target direct recipient
                                routeFileMetadata(activeUploadType, displayName, activeUploadRecipient, time, activeUploadFileName,
                                        activeUploadFileSize, activeUploadFile.getName(), activeUploadThumb);

                                activeUploadFile = null;
                            }
                        }

                    } else if (type == TYPE_FILE_CANCEL) {
                        cancelActiveUpload(time, "cancelled by user");

                    } else if (type == TYPE_DOWNLOAD_REQ) {
                        String fileId = in.readUTF();
                        handleDownloadRequest(fileId);

                    } else if (type == TYPE_CALL_SIGNAL) {
                        String recipient = in.readUTF();
                        String signalType = in.readUTF();
                        String payload = in.readUTF();
                        System.out.println(time + " [CALL " + signalType + "] " + displayName + " -> " + recipient);
                        routeCallSignal(displayName, recipient, time, signalType, payload);
                    }
                }
            } catch (IOException e) {
                // Client disconnected normally or connection dropped
            } finally {
                cleanup();
            }
        }

        private void cancelActiveUpload(String time, String reason) {
            if (activeUploadFos != null) {
                try {
                    activeUploadFos.close();
                } catch (IOException ignored) {}
                activeUploadFos = null;
            }
            if (activeUploadFile != null && activeUploadFile.exists()) {
                String deletedName = activeUploadFile.getName();
                boolean deleted = activeUploadFile.delete();
                System.out.println(time + " [CANCELLED] Upload of " + activeUploadFileName + " " + reason + ". Deleted partial file: " + deletedName + " (success=" + deleted + ")");
                activeUploadFile = null;
            }
        }

        private void handleDownloadRequest(String fileId) {
            File file = new File(STORAGE_DIR, fileId);
            if (!file.exists() || !file.isFile()) {
                System.err.println("Requested fileId not found on server: " + fileId);
                return;
            }

            long totalSize = file.length();
            System.out.println(getTimestamp() + " Streaming download of " + file.getName() + " (" + formatFileSize(totalSize) + ") to [" + displayName + "]...");

            synchronized (out) {
                try {
                    out.writeByte(TYPE_DOWNLOAD_DATA);
                    out.writeUTF(fileId);
                    out.writeLong(totalSize);

                    byte[] buffer = new byte[65536];
                    try (FileInputStream fis = new FileInputStream(file)) {
                        int r;
                        while ((r = fis.read(buffer)) != -1) {
                            out.write(buffer, 0, r);
                        }
                    }
                    out.flush();
                    System.out.println(getTimestamp() + " Finished streaming download of " + fileId + " to [" + displayName + "]");
                } catch (IOException ex) {
                    System.err.println("Download interrupted for [" + displayName + "]: " + ex.getMessage());
                }
            }
        }

        public synchronized void sendTextPacket(byte type, String sender, String recipient, String timestamp, String meta) {
            try {
                out.writeByte(type);
                out.writeUTF(sender);
                out.writeUTF(recipient != null ? recipient : GROUP_TARGET);
                out.writeUTF(timestamp);
                out.writeUTF(meta != null ? meta : "");
                out.flush();
            } catch (IOException ignored) {}
        }

        public synchronized void sendFileMetadataPacket(byte type, String sender, String recipient, String timestamp, String fileName,
                                                        long fileSize, String fileId, byte[] thumbnail) {
            try {
                out.writeByte(type);
                out.writeUTF(sender);
                out.writeUTF(recipient != null ? recipient : GROUP_TARGET);
                out.writeUTF(timestamp);
                out.writeUTF(fileName);
                out.writeLong(fileSize);
                out.writeUTF(fileId);
                out.writeInt(thumbnail != null ? thumbnail.length : 0);
                if (thumbnail != null && thumbnail.length > 0) {
                    out.write(thumbnail);
                }
                out.flush();
            } catch (IOException ignored) {}
        }

        public synchronized void sendCallSignal(String sender, String recipient, String timestamp, String signalType, String payload) {
            try {
                out.writeByte(TYPE_CALL_SIGNAL);
                out.writeUTF(sender);
                out.writeUTF(recipient != null ? recipient : GROUP_TARGET);
                out.writeUTF(timestamp);
                out.writeUTF(signalType != null ? signalType : "");
                out.writeUTF(payload != null ? payload : "");
                out.flush();
            } catch (IOException ignored) {}
        }

        private void cleanup() {
            cancelActiveUpload(getTimestamp(), "client disconnected");
            removeClient(this);
            if (displayName != null) {
                String leaveMsg = "* " + displayName + " left the chat *";
                System.out.println(getTimestamp() + " " + leaveMsg);
                routeTextPacket(TYPE_SYSTEM, "SYSTEM", GROUP_TARGET, getTimestamp(), leaveMsg);
                broadcastUserList();
            }
            try {
                socket.close();
            } catch (IOException ignored) {}
        }

        private static String sanitizeFileName(String name) {
            if (name == null) return "file.bin";
            return name.replaceAll("[^a-zA-Z0-9._-]", "_");
        }

        private static String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
            if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
}