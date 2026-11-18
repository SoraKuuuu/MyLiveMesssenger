import javax.sound.sampled.*;
import java.io.*;
import java.util.Locale;

/**
 * ============================================================================
 * AudioService - Universal Audio Playback & Voice Recording Engine
 * ============================================================================
 *
 * Supports playback of:
 * - Direct WAV, AIFF, AU audio formats using Java Sound AudioSystem
 * - Compressed formats (MP3, OGG, FLAC, M4A, AAC, WMA, OPUS) by dynamically
 *   transcoding to linear PCM WAV using the system's ffmpeg decoder into the local cache.
 * ============================================================================
 */
public class AudioService {

    public interface PlaybackListener {
        void onFinished();
    }

    private static Clip activeClip = null;
    private static File currentlyPlayingFile = null;

    /**
     * Thread-safe microphone recorder capturing 16kHz mono audio.
     */
    public static class Recorder {
        private TargetDataLine line;
        private ByteArrayOutputStream outStream;
        private Thread recordThread;
        private volatile boolean recording = false;

        public synchronized boolean start() {
            try {
                AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                if (!AudioSystem.isLineSupported(info)) {
                    System.err.println("Microphone line not supported for format: " + format);
                    return false;
                }
                line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();
                outStream = new ByteArrayOutputStream();
                recording = true;

                recordThread = new Thread(() -> {
                    byte[] buf = new byte[2048];
                    while (recording) {
                        int read = line.read(buf, 0, buf.length);
                        if (read > 0) {
                            outStream.write(buf, 0, read);
                        }
                    }
                }, "AudioRecorderThread");
                recordThread.setDaemon(true);
                recordThread.start();
                return true;
            } catch (Exception e) {
                System.err.println("Failed to start recording: " + e.getMessage());
                return false;
            }
        }

        public synchronized byte[] stop() {
            if (!recording) return null;
            recording = false;
            try {
                if (line != null) {
                    line.stop();
                    line.close();
                }
                if (recordThread != null) {
                    recordThread.join(1000);
                }
                byte[] pcmData = outStream.toByteArray();
                if (pcmData.length == 0) return null;

                AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, false);
                AudioInputStream ais = new AudioInputStream(
                        new ByteArrayInputStream(pcmData),
                        format,
                        pcmData.length / format.getFrameSize()
                );
                ByteArrayOutputStream wavOut = new ByteArrayOutputStream();
                AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavOut);
                return wavOut.toByteArray();
            } catch (Exception e) {
                System.err.println("Failed to stop recording: " + e.getMessage());
                return null;
            }
        }

        public boolean isRecording() {
            return recording;
        }
    }

    /**
     * Stops any currently playing audio clip and frees system audio resources.
     */
    public static synchronized void stopActivePlayback() {
        if (activeClip != null) {
            try {
                activeClip.stop();
                activeClip.close();
            } catch (Exception ignored) {}
            activeClip = null;
            currentlyPlayingFile = null;
        }
    }

    /**
     * Checks if an audio clip is actively playing.
     */
    public static synchronized boolean isPlaying() {
        return activeClip != null && activeClip.isRunning();
    }

    /**
     * Returns true if the specified file is currently playing.
     */
    public static synchronized boolean isPlayingFile(File file) {
        return isPlaying() && currentlyPlayingFile != null && currentlyPlayingFile.equals(file);
    }

    /**
     * Gets the current playback progress of the active clip (between 0.0 and 1.0).
     */
    public static synchronized double getCurrentProgress() {
        if (activeClip != null && activeClip.isOpen()) {
            long total = activeClip.getMicrosecondLength();
            long pos = activeClip.getMicrosecondPosition();
            if (total > 0) {
                return Math.min(1.0, (double) pos / (double) total);
            }
        }
        return 0.0;
    }

    /**
     * Gets the total duration of the active clip formatted as "m:ss".
     */
    public static synchronized String getActiveClipDurationString() {
        if (activeClip != null && activeClip.isOpen()) {
            long totalSec = activeClip.getMicrosecondLength() / 1_000_000L;
            return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
        }
        return "0:00";
    }

    /**
     * Gets the current position of the active clip formatted as "m:ss".
     */
    public static synchronized String getActiveClipPositionString() {
        if (activeClip != null && activeClip.isOpen()) {
            long curSec = activeClip.getMicrosecondPosition() / 1_000_000L;
            return String.format(Locale.US, "%d:%02d", curSec / 60, curSec % 60);
        }
        return "0:00";
    }

    /**
     * Returns true if the filename represents any common audio format.
     */
    public static boolean isAudioFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".mp3")  || lower.endsWith(".wav")  ||
               lower.endsWith(".ogg")  || lower.endsWith(".flac") ||
               lower.endsWith(".m4a")  || lower.endsWith(".aac")  ||
               lower.endsWith(".wma")  || lower.endsWith(".opus") ||
               lower.endsWith(".aiff") || lower.endsWith(".aif")  ||
               lower.endsWith(".au")   || lower.endsWith(".snd");
    }

    /**
     * Prepares an audio file for playback by converting it to WAV if necessary.
     * If the input is already a WAV file, it is returned as is.
     * Otherwise, ffmpeg is invoked to decode the audio into a cached WAV file.
     */
    public static File preparePlayableWavFile(File sourceAudio) {
        if (sourceAudio == null || !sourceAudio.exists()) return null;

        String name = sourceAudio.getName().toLowerCase();
        if (name.endsWith(".wav")) {
            return sourceAudio;
        }

        // Prepare cached destination WAV in storage/cache/audio/
        File cacheDir = new File("storage/cache/audio");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        File cachedWav = new File(cacheDir, sourceAudio.getName() + ".wav");
        if (cachedWav.exists() && cachedWav.length() > 0) {
            return cachedWav;
        }

        // Decode using system ffmpeg: ffmpeg -y -i <source> -ar 44100 -ac 2 <cachedWav>
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-i", sourceAudio.getAbsolutePath(),
                    "-ar", "44100", "-ac", "2", cachedWav.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            int exitCode = proc.waitFor();
            if (exitCode == 0 && cachedWav.exists() && cachedWav.length() > 0) {
                return cachedWav;
            }
        } catch (Exception e) {
            System.err.println("Audio decode via ffmpeg failed: " + e.getMessage());
        }

        return sourceAudio;
    }

    /**
     * Plays any standard audio file (WAV or decoded) with completion notification.
     *
     * @param audioFile The audio file to play.
     * @param listener Callback triggered when playback naturally finishes.
     * @return True if playback successfully started, false otherwise.
     */
    public static synchronized boolean playWavFile(File audioFile, PlaybackListener listener) {
        stopActivePlayback();
        try {
            File playableFile = preparePlayableWavFile(audioFile);
            if (playableFile == null || !playableFile.exists()) return false;

            AudioInputStream ais = AudioSystem.getAudioInputStream(playableFile);
            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    clip.close();
                    synchronized (AudioService.class) {
                        if (activeClip == clip) {
                            activeClip = null;
                            currentlyPlayingFile = null;
                        }
                    }
                    if (listener != null) listener.onFinished();
                }
            });
            activeClip = clip;
            currentlyPlayingFile = audioFile;
            clip.start();
            return true;
        } catch (Exception e) {
            System.err.println("Playback error on " + audioFile.getName() + ": " + e.getMessage());
            return false;
        }
    }
}
