import javafx.application.Platform;
import javafx.scene.image.Image;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;

/**
 * ============================================================================
 * MyLiveMessenger - VideoCallService (Real-Time Video Streaming Engine)
 * ============================================================================
 *
 * This class implements real-time video calling for 1-to-1 conversations.
 * It manages video frame capture (physical webcam with automatic pure-Java
 * virtual camera fallback), JPEG compression, UDP streaming via the central
 * Media Relay, and frame delivery to JavaFX ImageViews.
 *
 * Video Specifications:
 * ---------------------
 * - Frame Dimensions : 160 x 160 pixels (optimized for avatar frame replacement)
 * - Compression      : Progressive JPEG (~3-6 KB per frame)
 * - Frame Rate       : 15 frames per second (~50-80 KB/s bandwidth)
 * - Network Protocol : UDP Datagrams over the central Server Media Relay (port 5001)
 *
 * Dual-Mode Camera Pipeline:
 * --------------------------
 * 1. Hardware Webcam Mode:
 *    Probes for Linux V4L2 (/dev/video0) via a lightweight FFmpeg frame grabber.
 * 2. Virtual Camera Generator (Fallback):
 *    If no webcam is present, or when running multiple test clients on a single PC,
 *    generates an animated retro MSN Live webcam stream featuring real-time
 *    timestamp overlays, a pulsing live recording badge, and user avatar visuals.
 * ============================================================================
 */
public class VideoCallService {

    public static final byte MEDIA_TYPE_VIDEO = 2;
    public static final int VIDEO_WIDTH = 160;
    public static final int VIDEO_HEIGHT = 160;
    public static final int TARGET_FPS = 15;
    public static final int FRAME_INTERVAL_MS = 1000 / TARGET_FPS;

    /**
     * Listener callback delivering decoded frames to the JavaFX UI layer.
     */
    public interface VideoFrameListener {
        void onLocalFrame(Image frame);
        void onRemoteFrame(Image frame);
        void onError(String message);
    }

    private DatagramSocket udpSocket;
    private Thread captureThread;
    private Thread receiveThread;

    private volatile boolean isRunning = false;
    private volatile boolean cameraEnabled = true;

    private String serverIp;
    private int serverUdpPort;
    private String myUsername;
    private String peerUsername;
    private File myAvatarFile;
    private VideoFrameListener listener;

    private Process ffmpegProcess;
    private InputStream ffmpegStream;

    /**
     * Initializes and starts a video call streaming session.
     */
    public synchronized void startVideo(String serverIp, int serverUdpPort, String myUsername,
                                        String peerUsername, File myAvatarFile, VideoFrameListener listener) {
        if (isRunning) {
            stopVideo();
        }

        this.serverIp = (serverIp == null || serverIp.trim().isEmpty()) ? "127.0.0.1" : serverIp.trim();
        this.serverUdpPort = (serverUdpPort > 0) ? serverUdpPort : 5001;
        this.myUsername = myUsername;
        this.peerUsername = peerUsername;
        this.myAvatarFile = myAvatarFile;
        this.listener = listener;
        this.isRunning = true;
        this.cameraEnabled = true;

        try {
            // Ephemeral UDP datagram socket for video frames
            udpSocket = new DatagramSocket();

            // Probe hardware webcam capture or initialize virtual camera
            initCameraCapture();

            // 1. Frame capture and transmission worker thread
            captureThread = new Thread(this::captureAndSendLoop, "VideoCall-Capture-" + peerUsername);
            captureThread.setDaemon(true);
            captureThread.start();

            // 2. UDP reception and frame decoding worker thread
            receiveThread = new Thread(this::receiveAndDecodeLoop, "VideoCall-Receiver-" + peerUsername);
            receiveThread.setDaemon(true);
            receiveThread.start();

            System.out.println("[VideoCallService] Video session started with " + peerUsername);

        } catch (Exception ex) {
            System.err.println("[VideoCallService] Failed to start video session: " + ex.getMessage());
            if (listener != null) {
                listener.onError("Video initialization failed: " + ex.getMessage());
            }
            stopVideo();
        }
    }

    /**
     * Attempts to open the physical hardware webcam (/dev/video0) via FFmpeg MJPEG pipe.
     */
    private void initCameraCapture() {
        File v4l2Device = new File("/dev/video0");
        if (v4l2Device.exists() && v4l2Device.canRead()) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg", "-f", "v4l2", "-video_size", VIDEO_WIDTH + "x" + VIDEO_HEIGHT,
                        "-framerate", String.valueOf(TARGET_FPS), "-i", "/dev/video0",
                        "-f", "image2pipe", "-vcodec", "mjpeg", "-q:v", "6", "-"
                );
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                ffmpegProcess = pb.start();
                ffmpegStream = new BufferedInputStream(ffmpegProcess.getInputStream(), 16384);
                System.out.println("[VideoCallService] Hardware webcam initialized on /dev/video0");
                return;
            } catch (Exception ex) {
                System.out.println("[VideoCallService] Hardware webcam grabber unavailable: " + ex.getMessage());
            }
        }
        System.out.println("[VideoCallService] Using Virtual Camera Generator for " + myUsername);
    }

    /**
     * Video capture loop running at ~15 FPS.
     */
    private void captureAndSendLoop() {
        byte[] recipientBytes = peerUsername.getBytes(StandardCharsets.UTF_8);
        byte[] senderBytes = myUsername.getBytes(StandardCharsets.UTF_8);

        int headerLen = 1 + 1 + recipientBytes.length + 1 + senderBytes.length;
        byte[] packetBuffer = new byte[headerLen + 65000];

        packetBuffer[0] = MEDIA_TYPE_VIDEO;
        packetBuffer[1] = (byte) recipientBytes.length;
        System.arraycopy(recipientBytes, 0, packetBuffer, 2, recipientBytes.length);

        int sOffset = 2 + recipientBytes.length;
        packetBuffer[sOffset] = (byte) senderBytes.length;
        System.arraycopy(senderBytes, 0, packetBuffer, sOffset + 1, senderBytes.length);

        int payloadOffset = sOffset + 1 + senderBytes.length;

        long frameCount = 0;
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss");

        try {
            InetAddress serverAddr = InetAddress.getByName(serverIp);

            while (isRunning && !Thread.currentThread().isInterrupted()) {
                long startTime = System.currentTimeMillis();
                frameCount++;

                byte[] jpegBytes = null;

                if (cameraEnabled) {
                    if (ffmpegStream != null) {
                        jpegBytes = readNextJpegFrame(ffmpegStream);
                    }
                    if (jpegBytes == null) {
                        // Generate rich virtual camera frame with animated scanlines and live timer
                        BufferedImage virtualFrame = generateVirtualFrame(frameCount, timeFmt.format(LocalTime.now()));
                        jpegBytes = compressToJpeg(virtualFrame, 0.75f);
                    }
                } else {
                    // Camera disabled placeholder
                    BufferedImage pausedFrame = generateCameraOffFrame();
                    jpegBytes = compressToJpeg(pausedFrame, 0.70f);
                }

                if (jpegBytes != null && jpegBytes.length > 0 && jpegBytes.length < 60000) {
                    // 1. Deliver local preview frame to JavaFX UI
                    byte[] localCopy = jpegBytes;
                    Platform.runLater(() -> {
                        try {
                            Image fxImg = new Image(new ByteArrayInputStream(localCopy));
                            if (listener != null) listener.onLocalFrame(fxImg);
                        } catch (Exception ignored) {}
                    });

                    // 2. Package and stream frame to Server UDP Relay
                    System.arraycopy(jpegBytes, 0, packetBuffer, payloadOffset, jpegBytes.length);
                    DatagramPacket packet = new DatagramPacket(packetBuffer, payloadOffset + jpegBytes.length, serverAddr, serverUdpPort);
                    udpSocket.send(packet);
                }

                // Throttle frame rate precisely to TARGET_FPS
                long elapsed = System.currentTimeMillis() - startTime;
                long sleepTime = FRAME_INTERVAL_MS - elapsed;
                if (sleepTime > 5) {
                    Thread.sleep(sleepTime);
                }
            }
        } catch (Exception ex) {
            if (isRunning) {
                System.err.println("[VideoCallService] Capture loop error: " + ex.getMessage());
            }
        }
    }

    /**
     * Reads a single discrete JPEG frame from an MJPEG byte stream.
     */
    private byte[] readNextJpegFrame(InputStream is) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            int prev = -1;
            int curr;
            boolean started = false;

            while ((curr = is.read()) != -1) {
                if (!started) {
                    if (prev == 0xFF && curr == 0xD8) { // JPEG SOI marker
                        baos.write(0xFF);
                        baos.write(0xD8);
                        started = true;
                    }
                } else {
                    baos.write(curr);
                    if (prev == 0xFF && curr == 0xD9) { // JPEG EOI marker
                        return baos.toByteArray();
                    }
                }
                prev = curr;
                if (baos.size() > 65536) break; // Frame safety limit
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * UDP datagram reception and frame decoding loop.
     */
    private void receiveAndDecodeLoop() {
        byte[] receiveBuffer = new byte[65507];
        DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);

        try {
            while (isRunning && !Thread.currentThread().isInterrupted()) {
                udpSocket.receive(packet);
                int length = packet.getLength();
                if (length < 4) continue;

                byte mediaType = receiveBuffer[0];
                if (mediaType != MEDIA_TYPE_VIDEO) continue;

                int rLen = receiveBuffer[1] & 0xFF;
                int sOffset = 2 + rLen;
                if (sOffset >= length) continue;

                int sLen = receiveBuffer[sOffset] & 0xFF;
                int payloadOffset = sOffset + 1 + sLen;
                if (payloadOffset >= length) continue;

                int jpegLen = length - payloadOffset;
                byte[] jpegData = new byte[jpegLen];
                System.arraycopy(receiveBuffer, payloadOffset, jpegData, 0, jpegLen);

                // Decode into JavaFX Image and push to UI callback
                Platform.runLater(() -> {
                    try {
                        Image fxImg = new Image(new ByteArrayInputStream(jpegData));
                        if (listener != null) listener.onRemoteFrame(fxImg);
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception ex) {
            if (isRunning) {
                System.err.println("[VideoCallService] Receive loop error: " + ex.getMessage());
            }
        }
    }

    /**
     * Generates an animated, retro MSN Live webcam stream frame.
     */
    private BufferedImage generateVirtualFrame(long frameCount, String timeStr) {
        BufferedImage img = new BufferedImage(VIDEO_WIDTH, VIDEO_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Gradient Aero background
        int shift = (int) (frameCount % 60);
        GradientPaint gp = new GradientPaint(0, 0, new Color(18, 48, 86), VIDEO_WIDTH, VIDEO_HEIGHT, new Color(42 + shift / 4, 110, 168));
        g.setPaint(gp);
        g.fillRect(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT);

        // Avatar silhouette / picture
        int avatarSize = 74;
        int avX = (VIDEO_WIDTH - avatarSize) / 2;
        int avY = (VIDEO_HEIGHT - avatarSize) / 2 - 6;

        boolean avatarDrawn = false;
        if (myAvatarFile != null && myAvatarFile.exists()) {
            try {
                BufferedImage customImg = ImageIO.read(myAvatarFile);
                if (customImg != null) {
                    g.drawImage(customImg, avX, avY, avatarSize, avatarSize, null);
                    avatarDrawn = true;
                }
            } catch (Exception ignored) {}
        }
        if (!avatarDrawn) {
            // Draw default vector avatar icon
            g.setColor(new Color(255, 255, 255, 220));
            g.fill(new Ellipse2D.Double(avX + 22, avY + 10, 30, 30));
            g.fillArc(avX + 12, avY + 44, 50, 40, 0, 180);
        }

        // Live recording indicator: red pulsing dot + "LIVE"
        boolean blink = (frameCount % 20) < 12;
        if (blink) {
            g.setColor(new Color(239, 68, 68));
            g.fillOval(8, 8, 8, 8);
        }
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 10));
        g.drawString("LIVE", 20, 16);

        // Username and digital clock overlay
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g.drawString(myUsername, 8, VIDEO_HEIGHT - 8);
        g.drawString(timeStr, VIDEO_WIDTH - 50, 16);

        // Animated video scanlines
        g.setColor(new Color(255, 255, 255, 12));
        for (int y = 0; y < VIDEO_HEIGHT; y += 4) {
            g.drawLine(0, y, VIDEO_WIDTH, y);
        }

        g.dispose();
        return img;
    }

    /**
     * Generates a "Camera Paused" placeholder frame.
     */
    private BufferedImage generateCameraOffFrame() {
        BufferedImage img = new BufferedImage(VIDEO_WIDTH, VIDEO_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(24, 32, 44));
        g.fillRect(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT);

        g.setColor(new Color(148, 163, 184));
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        FontMetrics fm = g.getFontMetrics();
        String text = "Camera Paused";
        int tx = (VIDEO_WIDTH - fm.stringWidth(text)) / 2;
        int ty = (VIDEO_HEIGHT / 2) + 4;
        g.drawString(text, tx, ty);

        g.dispose();
        return img;
    }

    /**
     * Compresses a BufferedImage to JPEG byte array.
     */
    private byte[] compressToJpeg(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ImageIO.write(image, "jpg", baos);
            return baos.toByteArray();
        }

        ImageWriter writer = writers.next();
        try (MemoryCacheImageOutputStream mcios = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(mcios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    public synchronized void setCameraEnabled(boolean enabled) {
        this.cameraEnabled = enabled;
    }

    public boolean isCameraEnabled() {
        return cameraEnabled;
    }

    public boolean isVideoActive() {
        return isRunning;
    }

    /**
     * Cleanly terminates video streaming and releases hardware grabber.
     */
    public synchronized void stopVideo() {
        if (!isRunning) return;
        isRunning = false;

        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }

        if (ffmpegProcess != null) {
            try {
                ffmpegProcess.destroyForcibly();
            } catch (Exception ignored) {}
            ffmpegProcess = null;
        }

        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            udpSocket = null;
        }

        System.out.println("[VideoCallService] Video session terminated.");
    }
}
