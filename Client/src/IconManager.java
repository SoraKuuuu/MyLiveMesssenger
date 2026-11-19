import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.io.InputStream;
import java.util.*;

/**
 * ============================================================================
 * IconManager - Crisp Vector & Asset Icon Engine for MyLiveMessenger
 * ============================================================================
 *
 * Why this class exists:
 * ----------------------
 * On Linux (X11 / Wayland), standard system fonts often lack modern color emoji
 * glyphs or render them as invisible/missing tofu boxes (which caused the toolbar
 * icons to appear blank or broken).
 *
 * This class provides 100% reliable, crisp, resolution-independent vector icons
 * using JavaFX Shape primitives (SVGPath, Circles, Lines, Rectangles) as well as
 * cached PNG emoticon images loaded directly from application resources.
 * ============================================================================
 */
public class IconManager {

    // Cache of loaded PNG emoticon images keyed by filename (e.g., "1f600.png")
    private static final Map<String, Image> EMOJI_CACHE = new HashMap<>();

    // Ordered catalog of standard emoticons: emoji code -> classic textual shortcut
    public static final Map<String, String> EMOJI_MAP = new LinkedHashMap<>();

    static {
        // Populate standard emoticons mapped to filenames in /assets/emojis/
        EMOJI_MAP.put("1f600", ":-)");    // Grin
        EMOJI_MAP.put("1f602", ":'D");   // Joy / Tears of laughter
        EMOJI_MAP.put("1f923", "XD");    // ROFL
        EMOJI_MAP.put("1f60d", "*_*");   // Heart eyes
        EMOJI_MAP.put("1f60e", "8-)");   // Cool glasses
        EMOJI_MAP.put("1f60b", ":p");    // Yum / Tongue out
        EMOJI_MAP.put("1f970", "^_^");   // Smiling with hearts
        EMOJI_MAP.put("1f929", "*o*");   // Star-struck
        EMOJI_MAP.put("1f914", ":?");    // Thinking
        EMOJI_MAP.put("1f92b", ":#");    // Shushing face
        EMOJI_MAP.put("1f62d", ":'(");   // Loudly crying
        EMOJI_MAP.put("1f631", ":-O");   // Screaming in fear
        EMOJI_MAP.put("1f621", ">:(");   // Pouting / Angry
        EMOJI_MAP.put("1f97a", ":<");    // Pleading face
        EMOJI_MAP.put("1f44d", "(y)");   // Thumbs up
        EMOJI_MAP.put("1f44e", "(n)");   // Thumbs down
        EMOJI_MAP.put("1f44f", "(c)");   // Clapping hands
        EMOJI_MAP.put("1f64f", "(a)");   // Folded hands / Angel
        EMOJI_MAP.put("2764",  "(l)");   // Red heart / Love
        EMOJI_MAP.put("1f494", "(u)");   // Broken heart
        EMOJI_MAP.put("1f525", "(f)");   // Fire
        EMOJI_MAP.put("2728",  "(s)");   // Sparkles
        EMOJI_MAP.put("1f389", "(p)");   // Party popper
        EMOJI_MAP.put("1f680", "(r)");   // Rocket
        EMOJI_MAP.put("1f4af", "100");   // 100
        EMOJI_MAP.put("1f4a1", "(i)");   // Light bulb / Idea
        EMOJI_MAP.put("2615",  "(o)");   // Hot beverage / Coffee
        EMOJI_MAP.put("1f3ae", "(g)");   // Gamepad
        EMOJI_MAP.put("1f355", "(pi)");  // Pizza
        EMOJI_MAP.put("1f440", "(ee)");  // Eyes
        EMOJI_MAP.put("1f480", "(k)");   // Skull
        EMOJI_MAP.put("1f4a9", "(b)");   // Poop
        EMOJI_MAP.put("1f3a4", "(m)");   // Microphone
        EMOJI_MAP.put("1f4ce", "(at)");  // Paperclip
        EMOJI_MAP.put("1f4c4", "(doc)"); // Document
        EMOJI_MAP.put("1f5bc", "(img)"); // Frame / Image
    }

    /**
     * Loads a PNG emoticon Image from classpath resources with caching.
     *
     * @param code The emoji hex code without extension (e.g. "1f600")
     * @param size The desired square dimension in pixels
     * @return The loaded Image or null if not found
     */
    public static Image getEmojiImage(String code, double size) {
        String key = code + "@" + (int) size;
        if (EMOJI_CACHE.containsKey(key)) {
            return EMOJI_CACHE.get(key);
        }
        String path = "/assets/emojis/" + code + ".png";
        InputStream is = IconManager.class.getResourceAsStream(path);
        if (is != null) {
            Image img = new Image(is, size, size, true, true);
            EMOJI_CACHE.put(key, img);
            return img;
        }
        return null;
    }

    /**
     * Creates an ImageView Node for an emoticon by hex code.
     */
    public static Node createEmojiView(String code, double size) {
        Image img = getEmojiImage(code, size);
        if (img != null) {
            ImageView iv = new ImageView(img);
            iv.setFitWidth(size);
            iv.setFitHeight(size);
            iv.setPreserveRatio(true);
            iv.setSmooth(true);
            return iv;
        }
        // Fallback textual emoticon label
        String shortcut = EMOJI_MAP.getOrDefault(code, ":)");
        Label fallback = new Label(shortcut);
        fallback.setFont(Font.font("Segoe UI", FontWeight.BOLD, size * 0.7));
        fallback.setTextFill(Color.web("#E67E22"));
        return fallback;
    }

    // =========================================================================
    // Vector Shape Icon Factories (Guaranteed to render identically everywhere)
    // =========================================================================

    /**
     * Classic MSN yellow smiley face icon for the Emoticons button.
     */
    public static Node createSmileyIcon(double size) {
        double r = size / 2.0;
        Circle face = new Circle(r, r, r - 1.0);
        face.setFill(Color.web("#FFD43B"));
        face.setStroke(Color.web("#D99B00"));
        face.setStrokeWidth(1.2);

        // Eyes
        double eyeR = Math.max(1.0, size * 0.08);
        Circle leftEye = new Circle(r - size * 0.22, r - size * 0.12, eyeR, Color.web("#4A2E00"));
        Circle rightEye = new Circle(r + size * 0.22, r - size * 0.12, eyeR, Color.web("#4A2E00"));

        // Smile arc
        Arc smile = new Arc(r, r + size * 0.04, size * 0.26, size * 0.20, 195, 150);
        smile.setType(ArcType.OPEN);
        smile.setFill(null);
        smile.setStroke(Color.web("#4A2E00"));
        smile.setStrokeWidth(1.4);
        smile.setStrokeLineCap(StrokeLineCap.ROUND);

        javafx.scene.Group group = new javafx.scene.Group(face, leftEye, rightEye, smile);
        return group;
    }

    /**
     * Winking smiley face icon for the Winks button.
     */
    public static Node createWinkIcon(double size) {
        double r = size / 2.0;
        Circle face = new Circle(r, r, r - 1.0);
        face.setFill(Color.web("#FFD43B"));
        face.setStroke(Color.web("#D99B00"));
        face.setStrokeWidth(1.2);

        // Open left eye
        Circle leftEye = new Circle(r - size * 0.22, r - size * 0.12, Math.max(1.0, size * 0.08), Color.web("#4A2E00"));

        // Winking right eye (curved line)
        Arc wink = new Arc(r + size * 0.22, r - size * 0.08, size * 0.14, size * 0.10, 20, 140);
        wink.setType(ArcType.OPEN);
        wink.setFill(null);
        wink.setStroke(Color.web("#4A2E00"));
        wink.setStrokeWidth(1.4);
        wink.setStrokeLineCap(StrokeLineCap.ROUND);

        // Smile arc
        Arc smile = new Arc(r, r + size * 0.04, size * 0.26, size * 0.20, 195, 150);
        smile.setType(ArcType.OPEN);
        smile.setFill(null);
        smile.setStroke(Color.web("#4A2E00"));
        smile.setStrokeWidth(1.4);
        smile.setStrokeLineCap(StrokeLineCap.ROUND);

        return new javafx.scene.Group(face, leftEye, wink, smile);
    }

    /**
     * Microphone vector icon for the Voice Clip button.
     */
    public static Node createMicIcon(double size, Color color) {
        SVGPath mic = new SVGPath();
        mic.setContent("M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z" +
                       "M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z");
        mic.setFill(color);
        double scale = size / 24.0;
        mic.setScaleX(scale);
        mic.setScaleY(scale);
        return mic;
    }

    /**
     * Vibrating window / alert vector icon for the Wizz button.
     */
    public static Node createWizzIcon(double size, Color color) {
        SVGPath wizz = new SVGPath();
        wizz.setContent("M16 1H8C6.34 1 5 2.34 5 4v16c0 1.66 1.34 3 3 3h8c1.66 0 3-1.34 3-3V4c0-1.66-1.34-3-3-3zm-2 20h-4v-1h4v1zm3.25-3H6.75V4h10.5v14z" +
                       "M1 9h2v6H1zm20 0h2v6h-2zM3.5 5.5l1.41-1.41 1.41 1.41-1.41 1.41zm14.18 11.59l1.41-1.41 1.41 1.41-1.41 1.41z");
        wizz.setFill(color);
        double scale = size / 24.0;
        wizz.setScaleX(scale);
        wizz.setScaleY(scale);
        return wizz;
    }

    /**
     * Backward-compatible alias for createWizzIcon.
     */
    public static Node createNudgeIcon(double size, Color color) {
        return createWizzIcon(size, color);
    }

    /**
     * Clipboard / notebook icon for the Conversation History button.
     */
    public static Node createHistoryIcon(double size, Color color) {
        SVGPath hist = new SVGPath();
        hist.setContent("M19 3h-4.18C14.4 1.84 13.3 1 12 1c-1.3 0-2.4.84-2.82 2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm2 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z");
        hist.setFill(color);
        double scale = size / 24.0;
        hist.setScaleX(scale);
        hist.setScaleY(scale);
        return hist;
    }

    /**
     * Stylized bold "A" letter icon for the text formatting button.
     */
    public static Node createFontIcon(double size, Color color) {
        Label lbl = new Label("A");
        lbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, size));
        lbl.setTextFill(color);
        return lbl;
    }

    /**
     * Crisp vector Play triangle icon for audio playback buttons.
     */
    public static Node createPlayIcon(double size, Color color) {
        Polygon triangle = new Polygon();
        triangle.getPoints().addAll(
                0.0, 0.0,
                size, size / 2.0,
                0.0, size
        );
        triangle.setFill(color);
        return triangle;
    }

    /**
     * Crisp vector Stop square icon for audio playback buttons.
     */
    public static Node createStopIcon(double size, Color color) {
        Rectangle square = new Rectangle(size, size);
        square.setArcWidth(2);
        square.setArcHeight(2);
        square.setFill(color);
        return square;
    }
}
