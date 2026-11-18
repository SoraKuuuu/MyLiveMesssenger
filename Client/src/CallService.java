import javax.sound.sampled.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * ============================================================================
 * MyLiveMessenger - CallService (Real-Time Low-Latency Voice Engine)
 * ============================================================================
 *
 * This class encapsulates the entire voice/audio calling pipeline for 1-to-1
 * private conversations. It manages microphone capture, network UDP packetization,
 * remote audio packet reception, and speaker playback with real-time voice
 * activity metering.
 *
 * Audio Specifications:
 * ---------------------
 * - Sampling Rate : 16,000 Hz (16 kHz Wideband Voice, MSN Messenger standard)
 * - Sample Size   : 16-bit linear PCM
 * - Channels      : 1 (Mono)
 * - Byte Order    : Little-endian, signed
 * - Packet Size   : 640 bytes = 20 milliseconds per UDP datagram
 * - Frame Rate    : 50 packets per second (smooth, jitter-free streaming)
 *
 * Network Architecture:
 * ---------------------
 * Voice packets are relayed through the centralized Server UDP Media Relay
 * (default port 5001), eliminating NAT traversal and firewall issues.
 * Each packet carries a compact metadata header:
 *   [mediaType: 1B] [rLen: 1B] [recipient: rLen B] [sLen: 1B] [sender: sLen B] [PCM data: 640B]
 *
 * ============================================================================
 */
public class CallService {

    public static final byte MEDIA_TYPE_AUDIO = 1;
    public static final int SAMPLE_RATE = 16000;
    public static final int SAMPLE_SIZE_BITS = 16;
    public static final int CHANNELS = 1;
    public static final int CHUNK_SIZE = 640; // 20ms of audio at 16kHz 16-bit mono

    /**
     * Callback interface to notify the UI about voice activity and errors.
     */
    public interface CallListener {
        /**
         * Triggered when local microphone detects sound.
         * @param level normalized volume level from 0.0 (silence) to 1.0 (loud).
         */
        void onLocalVoiceActivity(double level);

        /**
         * Triggered when remote buddy's voice is playing.
         * @param level normalized volume level from 0.0 to 1.0.
         */
        void onRemoteVoiceActivity(double level);

        /**
         * Triggered on hardware or network errors.
         * @param errorMessage human-readable error description.
         */
        void onError(String errorMessage);
    }

    private DatagramSocket udpSocket;
    private TargetDataLine microphoneLine;
    private SourceDataLine speakerLine;

    private Thread captureThread;
    private Thread receiveThread;

    private volatile boolean isCalling = false;
    private volatile boolean isMuted = false;

    private String serverIp;
    private int serverUdpPort;
    private String myUsername;
    private String peerUsername;
    private CallListener callListener;

    /**
     * Initializes and starts a live full-duplex voice call session.
     *
     * @param serverIp Server IP address
     * @param serverUdpPort Server UDP media relay port (e.g. 5001)
     * @param myUsername Authenticated local user's name
     * @param peerUsername Target buddy's name
     * @param listener Callback listener for volume metering and errors
     */
    public synchronized void startCall(String serverIp, int serverUdpPort, String myUsername,
                                       String peerUsername, CallListener listener) {
        if (isCalling) {
            stopCall();
        }

        this.serverIp = (serverIp == null || serverIp.trim().isEmpty()) ? "127.0.0.1" : serverIp.trim();
        this.serverUdpPort = (serverUdpPort > 0) ? serverUdpPort : 5001;
        this.myUsername = myUsername;
        this.peerUsername = peerUsername;
        this.callListener = listener;
        this.isCalling = true;
        this.isMuted = false;

        AudioFormat audioFormat = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true, false);

        try {
            // 1. Initialize local UDP datagram socket on an ephemeral port
            udpSocket = new DatagramSocket();

            // 2. Open hardware microphone capture line
            DataLine.Info micInfo = new DataLine.Info(TargetDataLine.class, audioFormat);
            if (!AudioSystem.isLineSupported(micInfo)) {
                throw new LineUnavailableException("Microphone input format not supported by system audio.");
            }
            microphoneLine = (TargetDataLine) AudioSystem.getLine(micInfo);
            microphoneLine.open(audioFormat, CHUNK_SIZE * 4);
            microphoneLine.start();

            // 3. Open hardware speaker playback line
            DataLine.Info speakerInfo = new DataLine.Info(SourceDataLine.class, audioFormat);
            if (!AudioSystem.isLineSupported(speakerInfo)) {
                throw new LineUnavailableException("Speaker output format not supported by system audio.");
            }
            speakerLine = (SourceDataLine) AudioSystem.getLine(speakerInfo);
            speakerLine.open(audioFormat, CHUNK_SIZE * 4);
            speakerLine.start();

            // 4. Launch microphone capture & transmission thread
            captureThread = new Thread(this::captureAndSendLoop, "VoiceCall-Capture-" + peerUsername);
            captureThread.setDaemon(true);
            captureThread.start();

            // 5. Launch UDP reception & speaker playback thread
            receiveThread = new Thread(this::receiveAndPlayLoop, "VoiceCall-Receiver-" + peerUsername);
            receiveThread.setDaemon(true);
            receiveThread.start();

            System.out.println("[CallService] Voice call session started with " + peerUsername);

        } catch (Exception ex) {
            System.err.println("[CallService] Failed to start voice call: " + ex.getMessage());
            if (callListener != null) {
                callListener.onError("Audio initialization failed: " + ex.getMessage());
            }
            stopCall();
        }
    }

    /**
     * Microphone capture and UDP datagram transmission worker loop.
     */
    private void captureAndSendLoop() {
        byte[] audioBuffer = new byte[CHUNK_SIZE];
        byte[] recipientBytes = peerUsername.getBytes(StandardCharsets.UTF_8);
        byte[] senderBytes = myUsername.getBytes(StandardCharsets.UTF_8);

        // Pre-allocate header buffer
        int headerLen = 1 + 1 + recipientBytes.length + 1 + senderBytes.length;
        byte[] packetBuffer = new byte[headerLen + CHUNK_SIZE];

        packetBuffer[0] = MEDIA_TYPE_AUDIO;
        packetBuffer[1] = (byte) recipientBytes.length;
        System.arraycopy(recipientBytes, 0, packetBuffer, 2, recipientBytes.length);

        int sOffset = 2 + recipientBytes.length;
        packetBuffer[sOffset] = (byte) senderBytes.length;
        System.arraycopy(senderBytes, 0, packetBuffer, sOffset + 1, senderBytes.length);

        int payloadOffset = sOffset + 1 + senderBytes.length;

        try {
            InetAddress serverAddr = InetAddress.getByName(serverIp);

            // Send a warm-up handshake packet to register our endpoint with the UDP relay
            Arrays.fill(audioBuffer, (byte) 0);
            System.arraycopy(audioBuffer, 0, packetBuffer, payloadOffset, CHUNK_SIZE);
            DatagramPacket initPacket = new DatagramPacket(packetBuffer, packetBuffer.length, serverAddr, serverUdpPort);
            udpSocket.send(initPacket);

            while (isCalling && !Thread.currentThread().isInterrupted()) {
                int bytesRead = microphoneLine.read(audioBuffer, 0, CHUNK_SIZE);
                if (bytesRead <= 0) continue;

                // Calculate RMS volume level for voice activity indicator
                double rms = calculateRmsVolume(audioBuffer, bytesRead);
                if (callListener != null) {
                    callListener.onLocalVoiceActivity(isMuted ? 0.0 : rms);
                }

                if (isMuted) {
                    // Send silence frames while muted to maintain UDP NAT hole-punching
                    Arrays.fill(audioBuffer, (byte) 0);
                }

                System.arraycopy(audioBuffer, 0, packetBuffer, payloadOffset, bytesRead);
                DatagramPacket datagram = new DatagramPacket(packetBuffer, payloadOffset + bytesRead, serverAddr, serverUdpPort);
                udpSocket.send(datagram);
            }
        } catch (Exception ex) {
            if (isCalling) {
                System.err.println("[CallService] Capture loop error: " + ex.getMessage());
            }
        }
    }

    /**
     * UDP datagram reception and speaker playback worker loop.
     */
    private void receiveAndPlayLoop() {
        byte[] receiveBuffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);

        try {
            while (isCalling && !Thread.currentThread().isInterrupted()) {
                udpSocket.receive(packet);
                int length = packet.getLength();
                if (length < 4) continue;

                byte mediaType = receiveBuffer[0];
                if (mediaType != MEDIA_TYPE_AUDIO) continue;

                int rLen = receiveBuffer[1] & 0xFF;
                int sOffset = 2 + rLen;
                if (sOffset >= length) continue;

                int sLen = receiveBuffer[sOffset] & 0xFF;
                int payloadOffset = sOffset + 1 + sLen;
                if (payloadOffset >= length) continue;

                int audioBytesCount = length - payloadOffset;

                // Write received PCM voice samples directly to speaker output
                speakerLine.write(receiveBuffer, payloadOffset, audioBytesCount);

                // Calculate remote RMS volume level for UI voice activity wave
                double rms = calculateRmsVolume(receiveBuffer, payloadOffset, audioBytesCount);
                if (callListener != null) {
                    callListener.onRemoteVoiceActivity(rms);
                }
            }
        } catch (Exception ex) {
            if (isCalling) {
                System.err.println("[CallService] Receive loop error: " + ex.getMessage());
            }
        }
    }

    /**
     * Calculates the Root-Mean-Square (RMS) amplitude from 16-bit PCM little-endian samples.
     * Normalized between 0.0 and 1.0.
     */
    private double calculateRmsVolume(byte[] buffer, int length) {
        return calculateRmsVolume(buffer, 0, length);
    }

    private double calculateRmsVolume(byte[] buffer, int offset, int length) {
        long sumOfSquares = 0;
        int sampleCount = length / 2;
        if (sampleCount <= 0) return 0.0;

        for (int i = 0; i < sampleCount; i++) {
            int low = buffer[offset + 2 * i] & 0xFF;
            int high = buffer[offset + 2 * i + 1];
            short sample = (short) ((high << 8) | low);
            sumOfSquares += (long) sample * sample;
        }

        double rms = Math.sqrt((double) sumOfSquares / sampleCount);
        // Normalize against max 16-bit signed amplitude (32768)
        double normalized = rms / 32768.0 * 5.0; // scale factor for UI responsiveness
        return Math.min(1.0, Math.max(0.0, normalized));
    }

    /**
     * Toggles microphone mute state during an ongoing call.
     */
    public synchronized void setMuted(boolean muted) {
        this.isMuted = muted;
    }

    public boolean isMuted() {
        return isMuted;
    }

    public boolean isCallActive() {
        return isCalling;
    }

    /**
     * Cleanly terminates the active voice call session and releases all hardware lines.
     */
    public synchronized void stopCall() {
        if (!isCalling) return;
        isCalling = false;

        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }

        if (microphoneLine != null) {
            try {
                microphoneLine.stop();
                microphoneLine.close();
            } catch (Exception ignored) {}
            microphoneLine = null;
        }

        if (speakerLine != null) {
            try {
                speakerLine.stop();
                speakerLine.close();
            } catch (Exception ignored) {}
            speakerLine = null;
        }

        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            udpSocket = null;
        }

        System.out.println("[CallService] Voice call session terminated.");
    }
}
