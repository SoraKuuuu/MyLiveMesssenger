import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.scene.shape.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ============================================================================
 * MyLiveMessenger - ClientFX (Windows Live Messenger 2009 JavaFX Edition)
 * ============================================================================
 *
 * This class implements the complete client-side graphic user interface and
 * event-handling layer for MyLiveMessenger, adhering to the iconic Windows Live
 * Messenger (2009 / Wave 3) Aero aesthetic.
 *
 * Core Features Implemented:
 * --------------------------
 * 1. Branding:
 *    - Rebranded to "MyLiveMessenger" across window titles, headers, and toolbars.
 *
 * 2. Visual Toolbar & Vector Icons:
 *    - Uses dedicated IconManager vector shapes (SVGPath, Arcs, Shapes) rather
 *      than fragile system emoji fonts, preventing blank or missing tofu boxes.
 *    - Mid-toolbar features: Emoticons picker, Voice Clip recording, Nudge,
 *      Winks, Conversation History, and Text Formatting.
 *
 * 3. Rich Inline Emoticon Rendering:
 *    - The emoticon menu displays 36+ high-resolution PNG emoticons loaded
 *      directly from application resources (/assets/emojis/).
 *    - Text messages containing emoticons (like [emoji:1f600], :-), <3, etc.)
 *      are dynamically parsed and rendered using JavaFX TextFlow with inline
 *      ImageViews directly within the chat transcript.
 *
 * 4. Customizable Presence & Profile Picture:
 *    - Users can click their status label to change between:
 *      * Available (Green)
 *      * Busy (Red)
 *      * Away (Orange)
 *      * Appear Offline (Gray)
 *    - Users can click their avatar or personal status message to select a custom
 *      profile image (PNG, JPG, BMP) from disk. Custom avatars are framed in
 *      Aero glossy borders and persist across sessions.
 *
 * 5. Full Multimedia Suite:
 *    - Photos: File selection with thumbnail preview and "Save As..." download.
 *    - Files: Zero-heap chunked streaming with live progress bar and cancel support.
 *    - Voice Notes: Push-to-talk live mic recording to WAV and inline playback.
 *    - Nudge: Shakes the conversation window with decaying physics & alert sound.
 *    - History: Automatically persists conversations to disk per user & channel.
 * ============================================================================
 */
public class ClientFX extends Application implements ChatClient.ChatClientListener {

    // --- Typography & Theme Constants ---
    private static final String FONT_FAMILY = "Segoe UI, Tahoma, sans-serif";
    private static final String LINK_COLOR = "#0066CC";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Regular expression pattern to recognize [emoji:CODE] tokens in chat messages
    private static final Pattern EMOJI_TOKEN_PATTERN = Pattern.compile("\\[emoji:([a-zA-Z0-9_]+)\\]");

    // --- Presence Status Options ---
    public enum PresenceStatus {
        AVAILABLE("Online", "#8AC53E", "#4E841B"),
        BUSY("Busy", "#EF4444", "#991B1B"),
        AWAY("Away", "#F59E0B", "#B45309"),
        APPEAR_OFFLINE("Offline", "#94A3B8", "#475569");

        public final String label;
        public final String hexColor;
        public final String borderHex;

        PresenceStatus(String label, String hexColor, String borderHex) {
            this.label = label;
            this.hexColor = hexColor;
            this.borderHex = borderHex;
        }

        public static PresenceStatus fromString(String str) {
            if (str == null || str.trim().isEmpty()) return AVAILABLE;
            String s = str.trim();
            for (PresenceStatus ps : values()) {
                if (ps.name().equalsIgnoreCase(s) || ps.label.equalsIgnoreCase(s)) return ps;
            }
            if (s.equalsIgnoreCase("ONLINE") || s.equalsIgnoreCase("AVAILABLE")) return AVAILABLE;
            if (s.equalsIgnoreCase("OFFLINE") || s.equalsIgnoreCase("APPEAR_OFFLINE") || s.equalsIgnoreCase("APPEAR OFFLINE")) return APPEAR_OFFLINE;
            return AVAILABLE;
        }
    }

    // --- Network Engine & Conversation State ---
    private final ChatClient chatClient = new ChatClient();
    private final Map<String, ChatWindow> activeChatWindows = new HashMap<>();
    private final List<String> onlineBuddies = new ArrayList<>();
    // Tracks presence status per buddy
    private final Map<String, PresenceStatus> buddyPresences = new java.util.concurrent.ConcurrentHashMap<>();
    // Tracks unread / missed message counts per conversation target
    private final Map<String, Integer> missedMessageCounts = new HashMap<>();

    // Current authenticated user state
    private String myUsername = "";
    private PresenceStatus myPresence = PresenceStatus.AVAILABLE;
    private String myPersonalMessage = "Life is better with friends";
    private File myCustomAvatarFile = null;

    // Primary JavaFX window stages & scenes
    private Stage primaryStage;
    private Scene loginScene;
    private Scene contactListScene;

    // --- Sign-in Scene Components ---
    private TextField loginUsernameField;
    private TextField loginServerField;
    private Button loginSignInButton;
    private Label loginErrorLabel;

    // --- Contact List Scene Components ---
    private Label profileNameLabel;
    private Label profileStatusLabel;
    private Label profilePersonalMessageLabel;
    private StackPane profileAvatarHolder;
    private TreeView<String> contactTreeView;
    private TextField contactSearchField;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        chatClient.addListener(this);

        // Ensure persistent cache and storage directories exist on disk
        new File("storage/cache/thumbs").mkdirs();
        new File("storage/cache/audio").mkdirs();
        new File("storage/cache/avatars").mkdirs();
        new File("storage/history").mkdirs();

        // Build the two primary view scenes
        loginScene = createLoginScene();
        contactListScene = createContactListScene();

        primaryStage.setTitle("MyLiveMessenger");
        primaryStage.setOnCloseRequest(e -> {
            AudioService.stopActivePlayback();
            chatClient.disconnect();
            Platform.exit();
            System.exit(0);
        });

        // Initially show the Sign-In view
        displayLoginView();
        primaryStage.show();
    }

    // =========================================================================
    // Visual Helpers (Avatars, Aero Gloss, Typography)
    // =========================================================================

    /**
     * Builds a glossy rounded-rectangular avatar matching the official WLM 2009 style.
     * Supports either custom user image files or procedurally generated vector silhouettes.
     *
     * @param size The width and height in pixels.
     * @param identifier The username or seed for background gradient colors.
     * @param status The presence status controlling the glossy border color.
     * @param customImageFile Optional custom picture file (if set by user).
     * @return A StackPane node containing the framed, clipped, and shaded avatar.
     */
    private StackPane createAeroAvatar(double size, String identifier, PresenceStatus status, File customImageFile) {
        StackPane innerContent = new StackPane();
        innerContent.setMinSize(size, size);
        innerContent.setPrefSize(size, size);
        innerContent.setMaxSize(size, size);

        if (customImageFile != null && customImageFile.exists()) {
            // Render user-selected custom picture
            try {
                Image customImg = new Image(customImageFile.toURI().toString(), size, size, true, true);
                ImageView iv = new ImageView(customImg);
                iv.setFitWidth(size);
                iv.setFitHeight(size);
                iv.setPreserveRatio(false);
                innerContent.getChildren().add(iv);
            } catch (Exception ex) {
                innerContent.getChildren().add(createProceduralSilhouette(size, identifier));
            }
        } else {
            // Check if there is a cached custom picture on disk for this username
            File cachedFile = new File("storage/cache/avatars", identifier + ".png");
            if (cachedFile.exists()) {
                try {
                    Image customImg = new Image(cachedFile.toURI().toString(), size, size, true, true);
                    ImageView iv = new ImageView(customImg);
                    iv.setFitWidth(size);
                    iv.setFitHeight(size);
                    iv.setPreserveRatio(false);
                    innerContent.getChildren().add(iv);
                } catch (Exception ex) {
                    innerContent.getChildren().add(createProceduralSilhouette(size, identifier));
                }
            } else {
                innerContent.getChildren().add(createProceduralSilhouette(size, identifier));
            }
        }

        // Apply Aero upper-half glossy sheen reflection
        Rectangle glossOverlay = new Rectangle(size, size * 0.44);
        glossOverlay.setArcWidth(8);
        glossOverlay.setArcHeight(8);
        glossOverlay.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(255, 255, 255, 0.55)),
                new Stop(1, Color.rgb(255, 255, 255, 0.05))));
        glossOverlay.setTranslateY(-size * 0.28);
        innerContent.getChildren().add(glossOverlay);

        // Clip the contents with rounded corners
        Rectangle clip = new Rectangle(size, size);
        clip.setArcWidth(8);
        clip.setArcHeight(8);
        innerContent.setClip(clip);

        // Outer glossy frame with status-colored border
        StackPane frame = new StackPane(innerContent);
        frame.setPadding(new Insets(2));
        frame.setMaxSize(size + 6, size + 6);
        String borderColor = (status != null) ? status.hexColor : "#8AC53E";
        frame.setStyle(String.format(
                "-fx-background-color: white; -fx-background-radius: 6; -fx-border-radius: 6; " +
                "-fx-border-color: %s; -fx-border-width: 2.5; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 6, 0, 0, 2);",
                borderColor));
        return frame;
    }

    /**
     * Generates a smooth vector silhouette with deterministic gradient background.
     */
    private StackPane createProceduralSilhouette(double size, String seed) {
        Color base = Color.hsb(Math.abs(seed.hashCode()) % 360, 0.45, 0.85);

        Rectangle bg = new Rectangle(size, size);
        bg.setArcWidth(8);
        bg.setArcHeight(8);
        bg.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, base.brighter()), new Stop(1, base.darker())));

        Circle head = new Circle(size * 0.17, Color.rgb(255, 255, 255, 0.85));
        head.setTranslateY(-size * 0.14);

        Ellipse body = new Ellipse(size * 0.30, size * 0.20);
        body.setFill(Color.rgb(255, 255, 255, 0.85));
        body.setTranslateY(size * 0.30);

        return new StackPane(bg, head, body);
    }

    /**
     * Creates a standard styled text label using the application font.
     */
    private Label createLabel(String text, String cssStyle) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-family: '" + FONT_FAMILY + "'; " + cssStyle);
        return lbl;
    }

    /**
     * Creates a clickable hyperlink label with hover underline.
     */
    private Label createLinkLabel(String text) {
        Label lbl = new Label(text);
        lbl.setStyle("-fx-text-fill: " + LINK_COLOR + "; -fx-cursor: hand; -fx-font-family: '" + FONT_FAMILY + "'; -fx-font-size: 12px;");
        lbl.setOnMouseEntered(e -> lbl.setUnderline(true));
        lbl.setOnMouseExited(e -> lbl.setUnderline(false));
        return lbl;
    }

    /**
     * Renders the classic top blue Aero banner for MyLiveMessenger windows.
     */
    private HBox createMessengerHeader() {
        Circle greenDot = new Circle(7, Color.web("#4FB848"));
        Circle blueDot = new Circle(7, Color.web("#2D8FDD"));
        blueDot.setTranslateX(-5);
        blueDot.setTranslateY(2);
        StackPane logoIcon = new StackPane(greenDot, blueDot);
        logoIcon.setMaxSize(22, 18);

        Label title = new Label("MyLiveMessenger");
        title.setStyle("-fx-text-fill: white; -fx-font-family: '" + FONT_FAMILY + "'; -fx-font-size: 12px; -fx-font-weight: bold;");

        HBox header = new HBox(6, logoIcon, title);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6, 8, 6, 8));
        header.setStyle("-fx-background-color: linear-gradient(to bottom, #5DB2E8, #2F8FD3);");
        return header;
    }

    /**
     * Creates an empty vertical spacer region.
     */
    private Region createVerticalSpacer(double height) {
        Region spacer = new Region();
        spacer.setMinHeight(height);
        spacer.setPrefHeight(height);
        return spacer;
    }

    /**
     * Formats raw byte counts into human-readable strings (KB, MB, GB).
     */
    private static String formatByteSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    // =========================================================================
    // Sign-In View (Login Scene)
    // =========================================================================

    /**
     * Builds the sign-in scene without a password field, matching user preference.
     */
    private Scene createLoginScene() {
        VBox root = new VBox();
        root.setStyle("-fx-background-color: linear-gradient(to bottom, #7FC3EA 0%, #CFE8F7 22%, #EEF6FC 55%, #F7FBFE 100%);");

        HBox header = createMessengerHeader();

        // Large centered avatar display
        StackPane avatarBox = createAeroAvatar(96, "default", PresenceStatus.AVAILABLE, null);

        loginUsernameField = new TextField("User" + (int) (Math.random() * 900 + 100));
        loginUsernameField.setMaxWidth(Double.MAX_VALUE);
        loginServerField = new TextField("127.0.0.1:5000");

        for (TextField f : new TextField[]{loginUsernameField, loginServerField}) {
            f.setStyle("-fx-background-color: white; -fx-border-color: #7F9DB9; -fx-border-width: 1;"
                    + "-fx-background-radius: 0; -fx-border-radius: 0; -fx-font-family: '" + FONT_FAMILY + "';");
        }

        Label statusLbl = createLabel("Status:  Available \u25BE", "-fx-font-size: 12px; -fx-text-fill: #222;");

        CheckBox rememberMeBox = new CheckBox("Remember Me");
        CheckBox autoSignInBox = new CheckBox("Sign me in automatically");
        for (CheckBox c : new CheckBox[]{rememberMeBox, autoSignInBox}) {
            c.setStyle("-fx-font-family: '" + FONT_FAMILY + "'; -fx-font-size: 12px; -fx-text-fill: #222;");
        }

        loginSignInButton = new Button("Sign In");
        loginSignInButton.setPrefWidth(74);
        loginSignInButton.setDefaultButton(true);
        loginSignInButton.setStyle("-fx-background-color: linear-gradient(to bottom, #F6F6F6, #DADADA);"
                + "-fx-border-color: #8E8F8F; -fx-border-radius: 3; -fx-background-radius: 3;"
                + "-fx-text-fill: #0B2A5A; -fx-font-family: '" + FONT_FAMILY + "'; -fx-font-size: 12px; -fx-cursor: hand;");
        loginSignInButton.setOnAction(e -> handleSignInAction());

        loginErrorLabel = createLabel("", "-fx-text-fill: #C00000; -fx-font-size: 12px;");
        loginErrorLabel.setWrapText(true);

        VBox form = new VBox(4);
        form.setPadding(new Insets(0, 40, 0, 40));
        form.getChildren().addAll(
                createLabel("Display Name / E-mail:", "-fx-font-size: 12px; -fx-text-fill: #222;"), loginUsernameField,
                createVerticalSpacer(8),
                createLabel("Server:", "-fx-font-size: 12px; -fx-text-fill: #222;"), loginServerField,
                createVerticalSpacer(8),
                statusLbl,
                createVerticalSpacer(14),
                rememberMeBox,
                createVerticalSpacer(8),
                autoSignInBox,
                createVerticalSpacer(10));

        HBox btnRow = new HBox(loginSignInButton);
        btnRow.setAlignment(Pos.CENTER);

        VBox links = new VBox(4, createLinkLabel("Service Status"), createLinkLabel("Get a new account"));
        links.setPadding(new Insets(0, 0, 0, 14));

        Label brandFootnote = createLabel("MyLiveMessenger ID", "-fx-font-size: 11px; -fx-text-fill: #4A6B8A;");
        HBox footer = new HBox(brandFootnote);
        footer.setPadding(new Insets(0, 0, 8, 12));

        Region flexGrow = new Region();
        VBox.setVgrow(flexGrow, Priority.ALWAYS);

        VBox avatarWrap = new VBox(avatarBox);
        avatarWrap.setAlignment(Pos.CENTER);
        avatarWrap.setPadding(new Insets(30, 0, 24, 0));

        VBox errWrap = new VBox(loginErrorLabel);
        errWrap.setPadding(new Insets(0, 40, 0, 40));
        errWrap.setPrefWidth(340);

        root.getChildren().addAll(header, avatarWrap, form, btnRow, createVerticalSpacer(10),
                new HBox(errWrap), flexGrow, links, createVerticalSpacer(8), footer);
        return new Scene(root, 340, 640);
    }

    /**
     * Executes the asynchronous connection attempt using ChatClient.
     */
    private void handleSignInAction() {
        String user = loginUsernameField.getText().trim();
        if (user.isEmpty()) {
            user = "User" + (int) (Math.random() * 900 + 100);
            loginUsernameField.setText(user);
        }
        String server = loginServerField.getText().trim();
        String host = server;
        int port = 5000;
        int colonIdx = server.lastIndexOf(':');
        if (colonIdx >= 0) {
            host = server.substring(0, colonIdx);
            try {
                port = Integer.parseInt(server.substring(colonIdx + 1));
            } catch (NumberFormatException ex) {
                loginErrorLabel.setText("Invalid port number specified.");
                return;
            }
        }
        loginErrorLabel.setText("");
        loginSignInButton.setDisable(true);
        loginSignInButton.setText("Signing in...");
        chatClient.connectAsync(host, port, user);
    }

    // =========================================================================
    // Contact List View (Main Window Scene)
    // =========================================================================

    /**
     * Builds the main buddy list window matching the right side of the screenshot.
     */
    private Scene createContactListScene() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: white;");

        HBox header = createMessengerHeader();

        // 1. User Profile Strip (Clickable Avatar and Clickable Status)
        profileAvatarHolder = new StackPane(createAeroAvatar(54, "me", myPresence, myCustomAvatarFile));
        profileAvatarHolder.setCursor(javafx.scene.Cursor.HAND);
        profileAvatarHolder.setOnMouseClicked(e -> promptChangeProfilePicture());
        Tooltip.install(profileAvatarHolder, new Tooltip("Click to change display picture..."));

        profileNameLabel = createLabel("", "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1E395B;");

        // Clickable presence status indicator: (Available) ▾
        profileStatusLabel = createLabel("(" + myPresence.label + ") \u25BE",
                "-fx-font-size: 12px; -fx-text-fill: #1E395B; -fx-cursor: hand; -fx-font-weight: bold;");
        profileStatusLabel.setOnMouseClicked(e -> showPresenceContextMenu(profileStatusLabel));
        Tooltip.install(profileStatusLabel, new Tooltip("Click to change presence status"));

        // Clickable personal message subtitle: Life is better with friends ▾
        profilePersonalMessageLabel = createLabel(myPersonalMessage + " \u25BE",
                "-fx-font-size: 11px; -fx-text-fill: #6B7F94; -fx-font-style: italic; -fx-cursor: hand;");
        profilePersonalMessageLabel.setOnMouseClicked(e -> promptChangePersonalMessage());
        Tooltip.install(profilePersonalMessageLabel, new Tooltip("Click to edit personal status message"));

        Label nowPlayingLabel = createLabel("\u266B Listening to MyLiveMessenger...", "-fx-font-size: 10px; -fx-text-fill: #0066CC;");

        HBox nameRow = new HBox(6, profileNameLabel, profileStatusLabel);
        nameRow.setAlignment(Pos.BASELINE_LEFT);
        VBox profileDetails = new VBox(2, nameRow, profilePersonalMessageLabel, nowPlayingLabel);
        profileDetails.setAlignment(Pos.CENTER_LEFT);

        Region spacerProfile = new Region();
        HBox.setHgrow(spacerProfile, Priority.ALWAYS);

        // Mail envelope badge icon
        Label mailBadge = createLabel("\u2709 (1)", "-fx-font-size: 12px; -fx-text-fill: #0284C7; -fx-font-weight: bold; -fx-cursor: hand;");

        HBox profileStrip = new HBox(10, profileAvatarHolder, profileDetails, spacerProfile, mailBadge);
        profileStrip.setAlignment(Pos.CENTER_LEFT);
        profileStrip.setPadding(new Insets(10, 12, 10, 12));
        profileStrip.setStyle("-fx-background-color: linear-gradient(to bottom, #CFE7F8, #EAF4FC);"
                + "-fx-border-color: #B5D3EA; -fx-border-width: 0 0 1 0;");

        // 2. Search Bar
        contactSearchField = new TextField();
        contactSearchField.setPromptText("Search contacts or the web...");
        contactSearchField.setStyle("-fx-background-color: white; -fx-border-color: #A9BCD0; -fx-border-radius: 2;"
                + "-fx-background-radius: 2; -fx-font-family: '" + FONT_FAMILY + "'; -fx-font-size: 12px;");
        contactSearchField.textProperty().addListener((obs, oldVal, newVal) -> refreshContactTree());

        HBox searchBar = new HBox(contactSearchField);
        HBox.setHgrow(contactSearchField, Priority.ALWAYS);
        searchBar.setPadding(new Insets(8, 10, 8, 10));
        searchBar.setStyle("-fx-background-color: #F2F7FC; -fx-border-color: #D4E1EE; -fx-border-width: 0 0 1 0;");

        // 3. Buddy TreeView
        contactTreeView = new TreeView<>();
        contactTreeView.setShowRoot(false);
        contactTreeView.setStyle("-fx-background-color: white; -fx-border-color: transparent; -fx-font-family: '" + FONT_FAMILY + "'; -fx-font-size: 12px;");
        contactTreeView.setCellFactory(tv -> new TreeCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                    return;
                }
                TreeItem<String> ti = getTreeItem();
                if (ti.getParent() == tv.getRoot()) {
                    // Category header row: Groups (1), Friends (N)
                    setGraphic(null);
                    setText(item);
                    setStyle("-fx-font-weight: bold; -fx-text-fill: #1E395B; -fx-background-color: transparent;");
                } else {
                    // Contact entry row
                    boolean isLobby = ChatClient.GROUP_TARGET.equalsIgnoreCase(item);
                    PresenceStatus ps = isLobby ? PresenceStatus.AVAILABLE : buddyPresences.getOrDefault(item, PresenceStatus.AVAILABLE);
                    Circle statusDot = new Circle(5, Color.web(isLobby ? "#2D8FDD" : ps.hexColor));
                    statusDot.setStroke(Color.web(isLobby ? "#1A649E" : ps.borderHex));

                    // Retrieve missed / unread message count for this chat target
                    int missed = missedMessageCounts.getOrDefault(item, 0);

                    Label contactLabel = new Label(isLobby ? "Public Lobby" : item);
                    // Emphasize contacts with unread messages in bold
                    if (missed > 0) {
                        contactLabel.setStyle("-fx-text-fill: #111; -fx-font-weight: bold;");
                    } else {
                        contactLabel.setStyle("-fx-text-fill: #222;");
                    }

                    // Left-side info: avatar, presence dot, contact display name
                    HBox leftInfo = new HBox(7, createAeroAvatar(24, item, isLobby ? PresenceStatus.AVAILABLE : ps, null), statusDot, contactLabel);
                    leftInfo.setAlignment(Pos.CENTER_LEFT);

                    // Flexible horizontal spacer pushing the counter badge to the far right
                    Region rowSpacer = new Region();
                    HBox.setHgrow(rowSpacer, Priority.ALWAYS);

                    HBox row;
                    if (missed > 0) {
                        // Polished Windows Live Messenger style unread message pill badge
                        Label badge = new Label(missed > 99 ? "99+" : String.valueOf(missed));
                        badge.setStyle("-fx-background-color: #0284C7; -fx-text-fill: white; -fx-font-weight: bold; "
                                + "-fx-font-size: 10px; -fx-background-radius: 9; -fx-padding: 1 6 1 6;");
                        row = new HBox(leftInfo, rowSpacer, badge);
                    } else {
                        row = new HBox(leftInfo, rowSpacer);
                    }
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setPadding(new Insets(0, 4, 0, 0));
                    // Dynamically bind width so spacer cleanly fills row without overflowing scrollbar
                    row.prefWidthProperty().bind(Bindings.createDoubleBinding(
                            () -> Math.max(100.0, tv.getWidth() - 48.0),
                            tv.widthProperty()
                    ));

                    setText(null);
                    setGraphic(row);
                    setStyle("-fx-background-color: " + (isSelected() ? "#D9EAF8" : "transparent") + ";");
                }
            }
        });
        contactTreeView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                TreeItem<String> selected = contactTreeView.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getParent() != contactTreeView.getRoot()) {
                    openChatWindow(selected.getValue());
                }
            }
        });
        contactTreeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> contactTreeView.refresh());

        // 4. Footer Strip with Sign Out action
        Label signOutLink = createLinkLabel("Sign out");
        signOutLink.setOnMouseClicked(e -> {
            AudioService.stopActivePlayback();
            chatClient.disconnect();
            closeAllChatWindows();
            displayLoginView();
        });
        HBox footer = new HBox(signOutLink);
        footer.setPadding(new Insets(6, 12, 6, 12));
        footer.setStyle("-fx-background-color: #EAF2FA; -fx-border-color: #C7D9EA; -fx-border-width: 1 0 0 0;");

        VBox topRegion = new VBox(header, profileStrip, searchBar);
        root.setTop(topRegion);
        root.setCenter(contactTreeView);
        root.setBottom(footer);
        return new Scene(root, 380, 640);
    }

    /**
     * Prompts the user to pick a new profile image file and caches it.
     */
    private void promptChangeProfilePicture() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Display Picture");
        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Image Files (*.png, *.jpg, *.jpeg, *.bmp)", "*.png", "*.jpg", "*.jpeg", "*.bmp")
        );
        File chosen = fc.showOpenDialog(primaryStage);
        if (chosen != null && chosen.exists()) {
            try {
                File cached = new File("storage/cache/avatars", myUsername + ".png");
                Files.copy(chosen.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING);
                myCustomAvatarFile = cached;
                updateProfileHeader();
                // Update all currently open conversation windows
                for (ChatWindow cw : activeChatWindows.values()) {
                    cw.refreshMyAvatar();
                }
            } catch (IOException ex) {
                System.err.println("Failed to save display picture: " + ex.getMessage());
            }
        }
    }

    /**
     * Displays a context menu allowing the user to switch their presence status.
     */
    private void showPresenceContextMenu(Node anchor) {
        ContextMenu menu = new ContextMenu();
        menu.setStyle("-fx-background-color: white; -fx-border-color: #A0BCD6; -fx-font-family: '" + FONT_FAMILY + "'; -fx-font-size: 12px;");

        for (PresenceStatus ps : PresenceStatus.values()) {
            Circle dot = new Circle(5, Color.web(ps.hexColor));
            dot.setStroke(Color.web(ps.borderHex));
            MenuItem item = new MenuItem(ps.label, dot);
            item.setOnAction(e -> {
                myPresence = ps;
                updateProfileHeader();
                for (ChatWindow cw : activeChatWindows.values()) {
                    cw.refreshMyAvatar();
                }
                chatClient.sendPresence(myPresence.name());
            });
            menu.getItems().add(item);
        }
        menu.show(anchor, Side.BOTTOM, 0, 4);
    }

    /**
     * Prompts the user with a dialog to edit their personal status message.
     */
    private void promptChangePersonalMessage() {
        TextInputDialog dialog = new TextInputDialog(myPersonalMessage);
        dialog.setTitle("Personal Message");
        dialog.setHeaderText("Enter your personal status message:");
        dialog.setContentText("Message:");
        dialog.showAndWait().ifPresent(res -> {
            String trimmed = res.trim();
            if (!trimmed.isEmpty()) {
                myPersonalMessage = trimmed;
                profilePersonalMessageLabel.setText(myPersonalMessage + " \u25BE");
            }
        });
    }

    /**
     * Synchronizes the user profile strip components with current values.
     */
    private void updateProfileHeader() {
        profileNameLabel.setText(myUsername);
        profileStatusLabel.setText("(" + myPresence.label + ") \u25BE");
        profilePersonalMessageLabel.setText(myPersonalMessage + " \u25BE");
        profileAvatarHolder.getChildren().setAll(createAeroAvatar(54, myUsername, myPresence, myCustomAvatarFile));
    }

    /**
     * Rebuilds the contact list tree according to search queries and online buddies.
     */
    private void refreshContactTree() {
        String query = (contactSearchField == null) ? "" : contactSearchField.getText().trim().toLowerCase();
        TreeItem<String> root = new TreeItem<>("");

        // Groups category
        TreeItem<String> groupsCategory = new TreeItem<>("Groups (1)");
        if (query.isEmpty() || "public lobby".contains(query) || "group".contains(query)) {
            groupsCategory.getChildren().add(new TreeItem<>(ChatClient.GROUP_TARGET));
        }

        // Friends category
        List<String> matchingBuddies = new ArrayList<>();
        for (String buddy : onlineBuddies) {
            if (query.isEmpty() || buddy.toLowerCase().contains(query)) {
                matchingBuddies.add(buddy);
            }
        }
        TreeItem<String> friendsCategory = new TreeItem<>("Friends (" + matchingBuddies.size() + " Online)");
        for (String buddy : matchingBuddies) {
            friendsCategory.getChildren().add(new TreeItem<>(buddy));
        }

        groupsCategory.setExpanded(true);
        friendsCategory.setExpanded(true);
        root.getChildren().addAll(groupsCategory, friendsCategory);
        contactTreeView.setRoot(root);
    }

    private void displayLoginView() {
        loginSignInButton.setDisable(false);
        loginSignInButton.setText("Sign In");
        primaryStage.setScene(loginScene);
        primaryStage.setWidth(356);
        primaryStage.setHeight(680);
    }

    private void displayMainView() {
        updateProfileHeader();
        refreshContactTree();
        primaryStage.setScene(contactListScene);
        primaryStage.setWidth(396);
        primaryStage.setHeight(680);
    }

    /**
     * Computes the normalized conversation key for group vs. direct messages.
     */
    private String resolveConversationKey(String sender, String recipient, boolean isMe) {
        if (recipient == null || ChatClient.GROUP_TARGET.equalsIgnoreCase(recipient)) {
            return ChatClient.GROUP_TARGET;
        }
        return isMe ? recipient : sender;
    }

    /**
     * Opens or brings to focus an existing conversation window.
     * Clears any pending missed message count for this chat target.
     */
    private ChatWindow openChatWindow(String target) {
        ChatWindow window = activeChatWindows.get(target);
        if (window == null) {
            window = new ChatWindow(target);
            activeChatWindows.put(target, window);
        }
        window.stage.show();
        window.stage.toFront();
        clearMissedMessages(target);
        return window;
    }

    /**
     * Increments the missed / unread message counter for a conversation target
     * and refreshes the contact tree view so the badge appears on the right.
     *
     * @param target the buddy username or group target identifier
     */
    private void incrementMissedMessages(String target) {
        missedMessageCounts.put(target, missedMessageCounts.getOrDefault(target, 0) + 1);
        if (contactTreeView != null) {
            contactTreeView.refresh();
        }
    }

    /**
     * Clears any missed / unread message count for a conversation target
     * and refreshes the contact tree view to remove the counter badge.
     *
     * @param target the buddy username or group target identifier
     */
    private void clearMissedMessages(String target) {
        if (missedMessageCounts.remove(target) != null) {
            if (contactTreeView != null) {
                contactTreeView.refresh();
            }
        }
    }

    /**
     * Closes and cleans up all active chat window instances and resets unread counts.
     */
    private void closeAllChatWindows() {
        for (ChatWindow cw : new ArrayList<>(activeChatWindows.values())) {
            cw.destroy();
            cw.stage.close();
        }
        activeChatWindows.clear();
        missedMessageCounts.clear();
        if (contactTreeView != null) {
            contactTreeView.refresh();
        }
    }

    // =========================================================================
    // Conversation Window Class (Matching Official WLM 2009 Layout)
    // =========================================================================

    /**
     * Represents an independent conversation window for either the Public Lobby
     * or a direct 1-to-1 buddy chat.
     */
    private class ChatWindow {
        final String target;
        final Stage stage = new Stage();
        final VBox messagesContainer = new VBox(4);
        final TextArea messageInputField = new TextArea();

        // Top Action Bar Buttons
        final Button topPhotosBtn = new Button("Photos");
        final Button topFilesBtn = new Button("Files");
        final Button topVideoBtn = new Button("Video");
        final Button topCallBtn = new Button("Call");

        // Mid-Toolbar Action Buttons (Equipped with crisp vector shapes)
        final Button emojiToolButton = new Button();
        final Button voiceClipToolButton = new Button();
        final Button nudgeToolButton = new Button();

        // Upload and download status progress bar
        final HBox uploadStatusBox = new HBox(8);
        final ProgressBar uploadProgressBar = new ProgressBar(0);
        final Label uploadStatusText = new Label("");
        final Button cancelUploadBtn = new Button("\u2715 Cancel");

        final Label contactStatusLbl;
        private Popup emojiPopup = null;

        // Visual Containers for Profile Pictures and Live Video Feeds
        final StackPane contactAvatarContainer = new StackPane();
        final StackPane selfAvatarContainer = new StackPane();
        final ImageView remoteVideoView = new ImageView();
        final ImageView localVideoView = new ImageView();

        // Real-Time Voice & Video Calling Engines (Dedicated modules)
        final CallService callService = new CallService();
        final VideoCallService videoCallService = new VideoCallService();

        // In-Call state tracking
        boolean inCall = false;
        boolean isVideoCall = false;
        Timeline callDurationTimer;
        int callDurationSeconds = 0;

        // In-call control strip displayed between enlarged avatar/video squares
        final VBox callControlBox = new VBox(4);
        final Label callDurationLabel = new Label("00:00");
        final Button callMuteBtn = new Button("Mute");
        final Button callCameraBtn = new Button("Cam");
        final Button callEndBtn = new Button("End Call");

        // Call notification banner displayed at top of conversation panel
        final HBox callBanner = new HBox(8);
        final Label callBannerLabel = new Label();
        final Button callBannerAcceptBtn = new Button("Accept");
        final Button callBannerDeclineBtn = new Button("Decline");
        final Button callBannerCancelBtn = new Button("Cancel");

        // Voice recording state
        final AudioService.Recorder voiceRecorder = new AudioService.Recorder();
        Timeline recordingTimer;
        int recordingElapsedSeconds = 0;

        String lastMessageSender = null;

        ChatWindow(String target) {
            this.target = target;
            boolean isLobby = ChatClient.GROUP_TARGET.equalsIgnoreCase(target);
            String displayName = isLobby ? "Public Lobby" : target;

            // 1. TOP ACTION BAR (Call and Video buttons only present in 1-to-1 private chats)
            HBox topActionBar = buildTopActionBar(isLobby);

            // 2. CONTACT HEADER BANNER
            PresenceStatus initialStatus = isLobby ? PresenceStatus.AVAILABLE : buddyPresences.getOrDefault(target, PresenceStatus.AVAILABLE);
            Label contactNameLbl = createLabel(displayName, "-fx-font-size: 15px; -fx-text-fill: #1E395B; -fx-font-weight: bold;");
            contactStatusLbl = createLabel(isLobby ? "" : "(" + initialStatus.label + ")", "-fx-font-size: 12px; -fx-text-fill: #4A6B8A;");
            HBox nameRow = new HBox(5, contactNameLbl, contactStatusLbl);
            nameRow.setAlignment(Pos.BASELINE_LEFT);

            Label statusMessage = createLabel(isLobby ? "Everyone in the public lobby" : "Did anyone see movie last night?!",
                    "-fx-font-size: 11px; -fx-text-fill: #4A6B8A; -fx-font-style: italic;");
            Label photosSubNotice = createLabel(isLobby ? "" : "8 new photos added", "-fx-font-size: 10px; -fx-text-fill: #0066CC; -fx-cursor: hand;");

            VBox headerBox = new VBox(1, nameRow, statusMessage);
            if (!isLobby) headerBox.getChildren().add(photosSubNotice);
            headerBox.setPadding(new Insets(6, 10, 6, 10));

            // Set up Call Notification Banner (Incoming / Outgoing Ringing)
            callBanner.setAlignment(Pos.CENTER_LEFT);
            callBanner.setPadding(new Insets(6, 10, 6, 10));
            callBanner.setStyle("-fx-background-color: #EFF6FF; -fx-border-color: #3B82F6; -fx-border-width: 1; -fx-border-radius: 4; -fx-background-radius: 4;");
            callBannerLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #1E40AF;");
            callBannerAcceptBtn.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 3 10 3 10;");
            callBannerDeclineBtn.setStyle("-fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 3 10 3 10;");
            callBannerCancelBtn.setStyle("-fx-background-color: #64748B; -fx-text-fill: white; -fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 3 10 3 10;");

            callBannerAcceptBtn.setOnAction(e -> {
                chatClient.sendCallSignal(target, "ACCEPT", isVideoCall ? "VIDEO" : "AUDIO");
                enterCallMode(isVideoCall);
            });
            callBannerDeclineBtn.setOnAction(e -> {
                chatClient.sendCallSignal(target, "REJECT", "");
                exitCallMode("Call declined.");
            });
            callBannerCancelBtn.setOnAction(e -> {
                chatClient.sendCallSignal(target, "END", "");
                exitCallMode("Call cancelled.");
            });

            callBanner.getChildren().addAll(callBannerLabel, callBannerAcceptBtn, callBannerDeclineBtn, callBannerCancelBtn);
            callBanner.setVisible(false);
            callBanner.setManaged(false);

            // 3. CENTRAL CHAT TRANSCRIPT AREA
            messagesContainer.setPadding(new Insets(10));
            ScrollPane scrollPane = new ScrollPane(messagesContainer);
            scrollPane.setFitToWidth(true);
            scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scrollPane.setStyle("-fx-background: white; -fx-background-color: white; -fx-border-color: #A0BCD6; "
                    + "-fx-background-radius: 4; -fx-border-radius: 4;");
            messagesContainer.heightProperty().addListener((obs, oldVal, newVal) -> scrollPane.setVvalue(1.0));

            // 4. MID-TOOLBAR (Between messages and input)
            HBox midToolbar = buildMidToolbar();

            // 5. MESSAGE INPUT AREA
            messageInputField.setPrefRowCount(3);
            messageInputField.setWrapText(true);
            messageInputField.setPromptText("Type your message here... (Enter to send, Shift+Enter for newline)");
            messageInputField.setStyle("-fx-font-family: '" + FONT_FAMILY + "'; -fx-font-size: 13px; -fx-background-color: white;"
                    + "-fx-border-color: #A0BCD6; -fx-background-radius: 4; -fx-border-radius: 4;");
            messageInputField.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                    e.consume();
                    performSendTextMessage();
                }
            });

            // 6. UPLOAD / DOWNLOAD STATUS STRIP
            uploadProgressBar.setPrefWidth(160);
            uploadProgressBar.setStyle("-fx-accent: #0284C7;");
            uploadStatusText.setStyle("-fx-font-size: 11px; -fx-text-fill: #0F172A;");
            cancelUploadBtn.setStyle("-fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-size: 10px; -fx-cursor: hand;");
            cancelUploadBtn.setOnAction(e -> chatClient.cancelCurrentUpload());
            uploadStatusBox.setAlignment(Pos.CENTER_LEFT);
            uploadStatusBox.setPadding(new Insets(3, 8, 3, 8));
            uploadStatusBox.setStyle("-fx-background-color: #F0F9FF; -fx-border-color: #BAE6FD; -fx-border-width: 1; -fx-border-radius: 3;");
            uploadStatusBox.getChildren().addAll(uploadProgressBar, uploadStatusText, cancelUploadBtn);
            uploadStatusBox.setVisible(false);
            uploadStatusBox.setManaged(false);

            // Right column layout (Chat panel stays perfectly preserved to the side)
            VBox rightColumn = new VBox(4, headerBox, callBanner, scrollPane, midToolbar, messageInputField, uploadStatusBox);
            rightColumn.setPadding(new Insets(4, 10, 8, 4));
            VBox.setVgrow(scrollPane, Priority.ALWAYS);

            // 7. LEFT AVATAR & VIDEO COLUMN (Enlarges by 75% during call from 76px to 134px)
            refreshContactAvatar();
            refreshMyAvatar();

            // Set up In-Call Controls panel
            callDurationLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #1E395B;");
            callMuteBtn.setStyle("-fx-background-color: #E2EFF9; -fx-border-color: #B5D2EC; -fx-border-radius: 3; -fx-font-size: 10px; -fx-cursor: hand; -fx-padding: 2 6 2 6;");
            callCameraBtn.setStyle("-fx-background-color: #E2EFF9; -fx-border-color: #B5D2EC; -fx-border-radius: 3; -fx-font-size: 10px; -fx-cursor: hand; -fx-padding: 2 6 2 6;");
            callEndBtn.setStyle("-fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 10px; -fx-background-radius: 3; -fx-cursor: hand; -fx-padding: 3 8 3 8;");

            callMuteBtn.setOnAction(e -> {
                boolean muted = !callService.isMuted();
                callService.setMuted(muted);
                callMuteBtn.setText(muted ? "Unmute" : "Mute");
                callMuteBtn.setStyle(muted
                        ? "-fx-background-color: #FEE2E2; -fx-border-color: #F87171; -fx-text-fill: #991B1B; -fx-font-size: 10px; -fx-cursor: hand; -fx-padding: 2 6 2 6;"
                        : "-fx-background-color: #E2EFF9; -fx-border-color: #B5D2EC; -fx-font-size: 10px; -fx-cursor: hand; -fx-padding: 2 6 2 6;");
            });

            callCameraBtn.setOnAction(e -> {
                boolean cam = !videoCallService.isCameraEnabled();
                videoCallService.setCameraEnabled(cam);
                callCameraBtn.setText(cam ? "Cam" : "Cam Off");
            });

            callEndBtn.setOnAction(e -> {
                chatClient.sendCallSignal(target, "END", "");
                exitCallMode("Call ended.");
            });

            callControlBox.setAlignment(Pos.CENTER);
            callControlBox.setPadding(new Insets(4));
            callControlBox.setStyle("-fx-background-color: rgba(255,255,255,0.75); -fx-background-radius: 6; "
                    + "-fx-border-color: #A0BCD6; -fx-border-radius: 6; -fx-border-width: 1;");
            callControlBox.getChildren().addAll(callDurationLabel, callMuteBtn, callCameraBtn, callEndBtn);
            callControlBox.setVisible(false);
            callControlBox.setManaged(false);

            Region avatarSpacer = new Region();
            VBox.setVgrow(avatarSpacer, Priority.ALWAYS);

            VBox leftAvatarsColumn = new VBox(6,
                    contactAvatarContainer,
                    callControlBox,
                    avatarSpacer,
                    selfAvatarContainer);
            leftAvatarsColumn.setPadding(new Insets(10, 10, 12, 10));

            // Main Aero background gradient matching the screenshot
            HBox mainBody = new HBox(leftAvatarsColumn, rightColumn);
            HBox.setHgrow(rightColumn, Priority.ALWAYS);
            mainBody.setStyle("-fx-background-color: linear-gradient(to bottom, #8CCBEF 0%, #CDEAF8 25%, #E6F3D6 70%, #D5ECBE 100%);");

            BorderPane windowRoot = new BorderPane();
            windowRoot.setTop(topActionBar);
            windowRoot.setCenter(mainBody);

            stage.setTitle(displayName + " - Conversation");
            stage.setScene(new Scene(windowRoot, 640, 530));
            stage.setOnHidden(e -> {
                destroy();
                activeChatWindows.remove(target);
            });
            // Automatically clear missed message counter when the user focuses this conversation window
            stage.focusedProperty().addListener((obs, oldVal, isFocused) -> {
                if (isFocused) {
                    clearMissedMessages(target);
                }
            });

            // Automatically load persistent conversation history
            loadConversationHistory();

            Platform.runLater(messageInputField::requestFocus);
        }

        /**
         * Re-renders the remote contact avatar in the top-left corner (76px normal, 228px in-call [200% bigger]).
         */
        void refreshContactAvatar() {
            int size = inCall ? 228 : 76;
            boolean isLobby = ChatClient.GROUP_TARGET.equalsIgnoreCase(target);
            PresenceStatus ps = isLobby ? PresenceStatus.AVAILABLE : buddyPresences.getOrDefault(target, PresenceStatus.AVAILABLE);
            contactAvatarContainer.getChildren().setAll(createAeroAvatar(size, target, ps, null));
        }

        void updateContactPresence(PresenceStatus ps) {
            boolean isLobby = ChatClient.GROUP_TARGET.equalsIgnoreCase(target);
            if (isLobby) return;
            contactStatusLbl.setText("(" + ps.label + ")");
            refreshContactAvatar();
        }

        /**
         * Re-renders the self avatar in the bottom-left corner with current presence and picture (76px normal, 228px in-call).
         */
        void refreshMyAvatar() {
            int size = inCall ? 228 : 76;
            selfAvatarContainer.getChildren().setAll(createAeroAvatar(size, myUsername, myPresence, myCustomAvatarFile));
        }

        /**
         * Prepares and docks live video stream displays inside the 200% enlarged video call squares (228x228).
         */
        private void setupVideoViews() {
            int videoSize = 228;

            remoteVideoView.setFitWidth(videoSize);
            remoteVideoView.setFitHeight(videoSize);
            remoteVideoView.setPreserveRatio(false);
            Rectangle remoteClip = new Rectangle(videoSize, videoSize);
            remoteClip.setArcWidth(10);
            remoteClip.setArcHeight(10);
            remoteVideoView.setClip(remoteClip);

            StackPane remoteHolder = new StackPane(remoteVideoView);
            remoteHolder.setPrefSize(videoSize, videoSize);
            remoteHolder.setStyle("-fx-background-color: black; -fx-background-radius: 6; "
                    + "-fx-border-color: #8CC474; -fx-border-radius: 6; -fx-border-width: 2.5; "
                    + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 6, 0, 0, 2);");

            localVideoView.setFitWidth(videoSize);
            localVideoView.setFitHeight(videoSize);
            localVideoView.setPreserveRatio(false);
            Rectangle localClip = new Rectangle(videoSize, videoSize);
            localClip.setArcWidth(10);
            localClip.setArcHeight(10);
            localVideoView.setClip(localClip);

            StackPane localHolder = new StackPane(localVideoView);
            localHolder.setPrefSize(videoSize, videoSize);
            localHolder.setStyle("-fx-background-color: black; -fx-background-radius: 6; "
                    + "-fx-border-color: #8CC474; -fx-border-radius: 6; -fx-border-width: 2.5; "
                    + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 6, 0, 0, 2);");

            contactAvatarContainer.getChildren().setAll(remoteHolder);
            selfAvatarContainer.getChildren().setAll(localHolder);
        }

        /**
         * Initiates an outgoing voice or video call to this buddy.
         */
        void startOutgoingCall(boolean video) {
            if (inCall) {
                appendSystemNotice("A call is already active with " + target + ".");
                return;
            }
            isVideoCall = video;
            chatClient.sendCallSignal(target, video ? "INVITE_VIDEO" : "INVITE_AUDIO", "");

            callBannerLabel.setText("Calling " + target + (video ? " (Video)..." : " (Voice)..."));
            callBannerAcceptBtn.setVisible(false);
            callBannerAcceptBtn.setManaged(false);
            callBannerDeclineBtn.setVisible(false);
            callBannerDeclineBtn.setManaged(false);
            callBannerCancelBtn.setVisible(true);
            callBannerCancelBtn.setManaged(true);

            callBanner.setVisible(true);
            callBanner.setManaged(true);
            appendSystemNotice("Calling " + target + (video ? " (Video)..." : " (Voice)..."));
        }

        /**
         * Displays the incoming call invitation prompt in the conversation window.
         */
        void showIncomingCallBanner(boolean video) {
            if (inCall) {
                chatClient.sendCallSignal(target, "BUSY", "");
                return;
            }
            isVideoCall = video;
            stage.show();
            stage.toFront();

            callBannerLabel.setText("Incoming " + (video ? "Video" : "Voice") + " Call from " + target);
            callBannerAcceptBtn.setVisible(true);
            callBannerAcceptBtn.setManaged(true);
            callBannerDeclineBtn.setVisible(true);
            callBannerDeclineBtn.setManaged(true);
            callBannerCancelBtn.setVisible(false);
            callBannerCancelBtn.setManaged(false);

            callBanner.setVisible(true);
            callBanner.setManaged(true);
        }

        /**
         * Transitions the conversation window into active in-call mode.
         * Expands the 2 avatar squares by 200% (76px -> 228px) while leaving the
         * chat column to the side untouched, and starts the media streaming services.
         */
        void enterCallMode(boolean video) {
            if (inCall) return;
            inCall = true;
            isVideoCall = video;
            callDurationSeconds = 0;
            callDurationLabel.setText("00:00");

            // Expand stage width by 152px (difference between 228px and 76px) so the chat area retains its exact size
            stage.setWidth(stage.getWidth() + 152);

            // Hide calling banner
            callBanner.setVisible(false);
            callBanner.setManaged(false);

            // Configure 200% enlarged avatar/video views (228x228)
            if (video) {
                setupVideoViews();
                callCameraBtn.setVisible(true);
                callCameraBtn.setManaged(true);
            } else {
                refreshContactAvatar();
                refreshMyAvatar();
                callCameraBtn.setVisible(false);
                callCameraBtn.setManaged(false);
            }

            // Show in-call control strip
            callMuteBtn.setText("Mute");
            callControlBox.setVisible(true);
            callControlBox.setManaged(true);

            // Start call duration tracker
            callDurationTimer = new Timeline(new KeyFrame(Duration.seconds(1), ev -> {
                callDurationSeconds++;
                int m = callDurationSeconds / 60;
                int s = callDurationSeconds % 60;
                callDurationLabel.setText(String.format("%02d:%02d", m, s));
            }));
            callDurationTimer.setCycleCount(Timeline.INDEFINITE);
            callDurationTimer.play();

            // Start dedicated low-latency Voice Engine
            callService.startCall(chatClient.getServerIp(), ChatClient.MEDIA_PORT, myUsername, target, new CallService.CallListener() {
                @Override
                public void onLocalVoiceActivity(double level) {}
                @Override
                public void onRemoteVoiceActivity(double level) {}
                @Override
                public void onError(String errorMessage) {
                    Platform.runLater(() -> appendSystemNotice("Voice Call Notice: " + errorMessage));
                }
            });

            // Start dedicated Video Engine if this is a video call
            if (video) {
                videoCallService.startVideo(chatClient.getServerIp(), ChatClient.MEDIA_PORT, myUsername, target, myCustomAvatarFile, new VideoCallService.VideoFrameListener() {
                    @Override
                    public void onLocalFrame(Image frame) {
                        localVideoView.setImage(frame);
                    }
                    @Override
                    public void onRemoteFrame(Image frame) {
                        remoteVideoView.setImage(frame);
                    }
                    @Override
                    public void onError(String errorMessage) {
                        Platform.runLater(() -> appendSystemNotice("Video Call Notice: " + errorMessage));
                    }
                });
            }

            appendSystemNotice(video ? "Video call connected." : "Voice call connected.");
        }

        /**
         * Cleanly terminates the active call session, stops audio/video services,
         * reverts avatar squares back to normal 76px, and restores window width.
         */
        void exitCallMode(String reason) {
            callBanner.setVisible(false);
            callBanner.setManaged(false);

            if (!inCall) return;
            inCall = false;

            if (callDurationTimer != null) {
                callDurationTimer.stop();
                callDurationTimer = null;
            }

            callService.stopCall();
            videoCallService.stopVideo();

            // Revert avatar square sizes to standard 76x76
            refreshContactAvatar();
            refreshMyAvatar();

            // Hide in-call control strip
            callControlBox.setVisible(false);
            callControlBox.setManaged(false);

            // Restore window width
            stage.setWidth(stage.getWidth() - 152);

            appendSystemNotice(reason != null ? reason : "Call ended.");
        }

        /**
         * Builds the top action bar: Photos, Files, Video, Call.
         * Group chats (Public Lobby) omit Call and Video buttons.
         */
        private HBox buildTopActionBar(boolean isLobby) {
            HBox bar = new HBox(2);
            bar.setPadding(new Insets(3, 8, 3, 8));
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setStyle("-fx-background-color: linear-gradient(to bottom, #F4F9FD, #DCEBF7);"
                    + "-fx-border-color: #B9D2E6; -fx-border-width: 0 0 1 0;");

            styleTopBarButton(topPhotosBtn);
            styleTopBarButton(topFilesBtn);

            topPhotosBtn.setOnAction(e -> handlePhotoSend());
            topFilesBtn.setOnAction(e -> handleFileSend());

            Region flexSpacer = new Region();
            HBox.setHgrow(flexSpacer, Priority.ALWAYS);

            Label themeIcon = createLabel("\uD83C\uDFA8", "-fx-font-size: 13px; -fx-cursor: hand; -fx-padding: 2 6 2 6;");
            Label chevronIcon = createLabel("\u25BE", "-fx-font-size: 13px; -fx-cursor: hand; -fx-padding: 2 6 2 6;");

            if (!isLobby) {
                // Video & Voice call buttons only available for private 1-to-1 conversations
                styleTopBarButton(topVideoBtn);
                styleTopBarButton(topCallBtn);
                topVideoBtn.setOnAction(e -> startOutgoingCall(true));
                topCallBtn.setOnAction(e -> startOutgoingCall(false));

                bar.getChildren().addAll(topPhotosBtn, topFilesBtn, topVideoBtn, topCallBtn,
                        flexSpacer, themeIcon, chevronIcon);
            } else {
                // Group chat: Call and Video buttons are omitted
                bar.getChildren().addAll(topPhotosBtn, topFilesBtn,
                        flexSpacer, themeIcon, chevronIcon);
            }
            return bar;
        }

        /**
         * Builds the mid-toolbar with dedicated vector shapes and rich popups.
         */
        private HBox buildMidToolbar() {
            HBox bar = new HBox(5);
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setPadding(new Insets(2, 4, 2, 4));

            // Assign vector icons
            emojiToolButton.setGraphic(IconManager.createSmileyIcon(18));
            voiceClipToolButton.setGraphic(IconManager.createMicIcon(16, Color.web("#4A6B8A")));
            nudgeToolButton.setGraphic(IconManager.createWizzIcon(16, Color.web("#4A6B8A")));

            styleToolButton(emojiToolButton, "Emoticons");
            styleToolButton(voiceClipToolButton, "Send a Voice Clip");
            styleToolButton(nudgeToolButton, "Send a Wizz!");

            emojiToolButton.setOnAction(e -> displayEmoticonPalette(emojiToolButton));
            voiceClipToolButton.setOnAction(e -> toggleVoiceRecordingSession());
            nudgeToolButton.setOnAction(e -> performSendWizz());

            bar.getChildren().addAll(emojiToolButton, voiceClipToolButton, nudgeToolButton);
            return bar;
        }

        private void styleTopBarButton(Button btn) {
            btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #1E395B; -fx-font-family: '" + FONT_FAMILY
                    + "'; -fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 3 8 3 8;");
            btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: #E2EFF9; -fx-border-color: #B5D2EC; -fx-border-radius: 2;"
                    + "-fx-text-fill: #0055AA; -fx-font-family: '" + FONT_FAMILY + "'; -fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 3 8 3 8;"));
            btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #1E395B; -fx-font-family: '" + FONT_FAMILY
                    + "'; -fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 3 8 3 8;"));
        }

        private void styleToolButton(Button btn, String tooltip) {
            btn.setTooltip(new Tooltip(tooltip));
            btn.setStyle("-fx-background-color: transparent; -fx-cursor: hand; -fx-padding: 2 4 2 4;");
            btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: #E2EFF9; -fx-border-color: #B5D2EC; -fx-border-radius: 2; -fx-cursor: hand; -fx-padding: 2 4 2 4;"));
            btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: transparent; -fx-cursor: hand; -fx-padding: 2 4 2 4;"));
        }

        private void styleAttachmentButton(Button btn) {
            btn.setStyle("-fx-background-color: linear-gradient(to bottom, #F8FAFC, #E2E8F0);"
                    + "-fx-border-color: #94A3B8; -fx-border-radius: 3; -fx-background-radius: 3;"
                    + "-fx-text-fill: #0F172A; -fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 3 8 3 8;");
            btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: linear-gradient(to bottom, #EFF6FF, #DBEAFE);"
                    + "-fx-border-color: #3B82F6; -fx-border-radius: 3; -fx-background-radius: 3;"
                    + "-fx-text-fill: #0055AA; -fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 3 8 3 8;"));
            btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: linear-gradient(to bottom, #F8FAFC, #E2E8F0);"
                    + "-fx-border-color: #94A3B8; -fx-border-radius: 3; -fx-background-radius: 3;"
                    + "-fx-text-fill: #0F172A; -fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 3 8 3 8;"));
        }

        void destroy() {
            if (emojiPopup != null && emojiPopup.isShowing()) {
                emojiPopup.hide();
            }
            if (inCall) {
                chatClient.sendCallSignal(target, "END", "");
                exitCallMode("Call ended.");
            }
            if (voiceRecorder.isRecording()) {
                voiceRecorder.stop();
            }
            if (recordingTimer != null) {
                recordingTimer.stop();
            }
        }

        // =====================================================================
        // Actions: Text, Emoticon Palette, Photos, Files, Voice, Wizz
        // =====================================================================

        void performSendTextMessage() {
            String rawText = messageInputField.getText().trim();
            if (rawText.isEmpty() || !chatClient.isConnected()) return;

            try {
                chatClient.sendTextMessage(target, rawText);
                messageInputField.clear();
            } catch (IOException ex) {
                appendSystemNotice("Failed to send message: " + ex.getMessage());
            }
        }

        /**
         * Displays the popup grid of high-res PNG emoticons from /assets/emojis/.
         * Uses a dedicated Popup instead of ContextMenu to prevent whole-palette blue
         * highlighting, with fixed-dimension buttons and constant border width to eliminate hover jitter.
         */
        void displayEmoticonPalette(Node anchor) {
            if (emojiPopup != null && emojiPopup.isShowing()) {
                emojiPopup.hide();
                return;
            }

            emojiPopup = new Popup();
            emojiPopup.setAutoHide(true);

            VBox container = new VBox();
            container.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #A0BCD6; -fx-border-width: 1; "
                    + "-fx-background-radius: 4; -fx-border-radius: 4; -fx-padding: 6; "
                    + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 8, 0, 0, 2);");

            GridPane grid = new GridPane();
            grid.setHgap(3);
            grid.setVgap(3);

            int col = 0;
            int row = 0;
            for (Map.Entry<String, String> entry : IconManager.EMOJI_MAP.entrySet()) {
                String code = entry.getKey();
                String shortcut = entry.getValue();

                Node emojiGraphic = IconManager.createEmojiView(code, 22);
                Button cellBtn = new Button("", emojiGraphic);
                cellBtn.setTooltip(new Tooltip(shortcut));
                cellBtn.setMinSize(30, 30);
                cellBtn.setPrefSize(30, 30);
                cellBtn.setMaxSize(30, 30);
                cellBtn.setCursor(Cursor.HAND);

                // Constant 1px border ensures layout dimensions never shift on hover
                String normalStyle = "-fx-background-color: transparent; -fx-border-color: transparent; "
                        + "-fx-border-width: 1; -fx-border-radius: 3; -fx-background-radius: 3; -fx-padding: 0; -fx-alignment: center;";
                String hoverStyle = "-fx-background-color: #D9EAF8; -fx-border-color: #7FA6C9; "
                        + "-fx-border-width: 1; -fx-border-radius: 3; -fx-background-radius: 3; -fx-padding: 0; -fx-alignment: center;";

                cellBtn.setStyle(normalStyle);
                cellBtn.setOnMouseEntered(ev -> cellBtn.setStyle(hoverStyle));
                cellBtn.setOnMouseExited(ev -> cellBtn.setStyle(normalStyle));

                cellBtn.setOnAction(ev -> {
                    // Append structured emoji token to composer
                    messageInputField.appendText(" [emoji:" + code + "] ");
                    if (emojiPopup != null) emojiPopup.hide();
                    messageInputField.requestFocus();
                });

                grid.add(cellBtn, col, row);
                col++;
                if (col >= 8) {
                    col = 0;
                    row++;
                }
            }

            container.getChildren().add(grid);
            emojiPopup.getContent().setAll(container);

            Point2D screenCoords = anchor.localToScreen(0, 0);
            if (screenCoords != null) {
                container.applyCss();
                container.layout();
                double popupHeight = container.prefHeight(-1);
                if (popupHeight <= 0) popupHeight = 185;
                emojiPopup.show(anchor, screenCoords.getX(), screenCoords.getY() - popupHeight - 4);
            }
        }

        void handlePhotoSend() {
            if (!chatClient.isConnected()) return;
            FileChooser fc = new FileChooser();
            fc.setTitle("Select Photo to Share");
            fc.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp")
            );
            File chosenFile = fc.showOpenDialog(stage);
            if (chosenFile != null) {
                updateProgressDisplay(true, "Sending photo: " + chosenFile.getName() + "...", 0);
                chatClient.sendFileChunked(ChatClient.TYPE_IMAGE, target, chosenFile, new ChatClient.ProgressListener() {
                    @Override
                    public void onProgress(int pct, long transferred, long total) {
                        Platform.runLater(() -> updateProgressDisplay(true, "Sending photo " + pct + "%", pct / 100.0));
                    }

                    @Override
                    public void onComplete(String fileName) {
                        Platform.runLater(() -> {
                            updateProgressDisplay(false, "", 0);
                            appendSystemNotice("Photo sent: " + fileName);
                        });
                    }

                    @Override
                    public void onError(String fileName, Exception ex) {
                        Platform.runLater(() -> {
                            updateProgressDisplay(false, "", 0);
                            appendSystemNotice("Failed to send photo: " + ex.getMessage());
                        });
                    }
                });
            }
        }

        void handleFileSend() {
            if (!chatClient.isConnected()) return;
            FileChooser fc = new FileChooser();
            fc.setTitle("Select File to Share");
            File chosenFile = fc.showOpenDialog(stage);
            if (chosenFile != null) {
                updateProgressDisplay(true, "Sending file: " + chosenFile.getName() + "...", 0);
                chatClient.sendFileChunked(ChatClient.TYPE_FILE, target, chosenFile, new ChatClient.ProgressListener() {
                    @Override
                    public void onProgress(int pct, long transferred, long total) {
                        Platform.runLater(() -> updateProgressDisplay(true, "Sending file " + pct + "%", pct / 100.0));
                    }

                    @Override
                    public void onComplete(String fileName) {
                        Platform.runLater(() -> {
                            updateProgressDisplay(false, "", 0);
                            appendSystemNotice("File sent: " + fileName);
                        });
                    }

                    @Override
                    public void onError(String fileName, Exception ex) {
                        Platform.runLater(() -> {
                            updateProgressDisplay(false, "", 0);
                            appendSystemNotice("Failed to send file: " + ex.getMessage());
                        });
                    }
                });
            }
        }

        void toggleVoiceRecordingSession() {
            if (!chatClient.isConnected()) return;

            if (!voiceRecorder.isRecording()) {
                boolean ok = voiceRecorder.start();
                if (!ok) {
                    appendSystemNotice("Could not access microphone.");
                    return;
                }
                recordingElapsedSeconds = 0;
                voiceClipToolButton.setGraphic(null);
                voiceClipToolButton.setText("\uD83D\uDD34 (0s)");
                voiceClipToolButton.setStyle("-fx-background-color: #EF4444; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-font-size: 11px;");

                recordingTimer = new Timeline(new KeyFrame(Duration.seconds(1), ev -> {
                    recordingElapsedSeconds++;
                    voiceClipToolButton.setText(String.format("\uD83D\uDD34 (%ds)", recordingElapsedSeconds));
                }));
                recordingTimer.setCycleCount(Timeline.INDEFINITE);
                recordingTimer.play();

            } else {
                if (recordingTimer != null) recordingTimer.stop();
                voiceClipToolButton.setText("");
                voiceClipToolButton.setGraphic(IconManager.createMicIcon(16, Color.web("#4A6B8A")));
                styleToolButton(voiceClipToolButton, "Send a Voice Clip");

                byte[] wavAudioBytes = voiceRecorder.stop();
                if (wavAudioBytes != null && wavAudioBytes.length > 100) {
                    updateProgressDisplay(true, "Sending voice clip...", 0);
                    chatClient.sendAudioBytes(target, wavAudioBytes, new ChatClient.ProgressListener() {
                        @Override
                        public void onProgress(int pct, long transferred, long total) {
                            Platform.runLater(() -> updateProgressDisplay(true, "Sending voice clip " + pct + "%", pct / 100.0));
                        }

                        @Override
                        public void onComplete(String fileName) {
                            Platform.runLater(() -> {
                                updateProgressDisplay(false, "", 0);
                                appendSystemNotice("Voice clip sent successfully.");
                            });
                        }

                        @Override
                        public void onError(String fileName, Exception ex) {
                            Platform.runLater(() -> {
                                updateProgressDisplay(false, "", 0);
                                appendSystemNotice("Voice clip failed: " + ex.getMessage());
                            });
                        }
                    });
                }
            }
        }

        void performSendWizz() {
            if (!chatClient.isConnected()) return;
            try {
                chatClient.sendTextMessage(target, "[WIZZ]");
                executeWindowShake();
                AudioService.playWizzSound();
                appendSystemNotice("---------- You just sent a wizz. ----------");
            } catch (IOException ex) {
                appendSystemNotice("Failed to send wizz: " + ex.getMessage());
            }
        }

        void performSendNudge() {
            performSendWizz();
        }

        void handleIncomingWizz(String sender) {
            Platform.runLater(() -> {
                stage.setIconified(false);
                stage.toFront();
                stage.requestFocus();
                executeWindowShake();
                appendSystemNotice("---------- " + sender + " just sent you a wizz! ----------");
            });
        }

        void handleIncomingNudge(String sender) {
            handleIncomingWizz(sender);
        }

        private void executeWindowShake() {
            try {
                Toolkit.getDefaultToolkit().beep();
            } catch (Exception ignored) {}

            Timeline shakeTimeline = new Timeline();
            double originalX = stage.getX();
            double originalY = stage.getY();
            int[] deltaX = {8, -8, 6, -6, 4, -4, 2, -2, 0};
            int[] deltaY = {4, -4, 3, -3, 2, -2, 1, -1, 0};

            for (int i = 0; i < deltaX.length; i++) {
                final int offX = deltaX[i];
                final int offY = deltaY[i];
                shakeTimeline.getKeyFrames().add(new KeyFrame(Duration.millis(i * 40), ev -> {
                    stage.setX(originalX + offX);
                    stage.setY(originalY + offY);
                }));
            }
            shakeTimeline.play();
        }

        void openConversationHistoryFolder() {
            File histDir = new File("storage/history/" + myUsername);
            if (!histDir.exists()) histDir.mkdirs();
            try {
                Desktop.getDesktop().open(histDir);
            } catch (Exception ex) {
                appendSystemNotice("History location: " + histDir.getAbsolutePath());
            }
        }

        void updateProgressDisplay(boolean visible, String statusText, double progressFraction) {
            uploadStatusBox.setVisible(visible);
            uploadStatusBox.setManaged(visible);
            uploadStatusText.setText(statusText);
            uploadProgressBar.setProgress(progressFraction);
        }

        // =====================================================================
        // Message Transcript Rendering (Rich TextFlow Emoticons & Cards)
        // =====================================================================

        /**
         * Renders an incoming or outgoing text message. Parses [emoji:CODE] tokens
         * and embeds actual PNG ImageView graphics directly into a JavaFX TextFlow.
         */
        void appendTextMessage(String sender, String textContent) {
            if (!sender.equals(lastMessageSender)) {
                Label senderHeader = createLabel(sender + " says:", "-fx-font-size: 12px; -fx-text-fill: #777; -fx-font-weight: bold;");
                senderHeader.setPadding(new Insets(5, 0, 0, 0));
                messagesContainer.getChildren().add(senderHeader);
                lastMessageSender = sender;
            }

            TextFlow flow = buildRichTextFlow(textContent);
            flow.setPadding(new Insets(0, 0, 0, 14));
            messagesContainer.getChildren().add(flow);
        }

        /**
         * Parses text content and returns a TextFlow with embedded ImageView icons.
         */
        private TextFlow buildRichTextFlow(String raw) {
            TextFlow flow = new TextFlow();
            Matcher matcher = EMOJI_TOKEN_PATTERN.matcher(raw);
            int lastIndex = 0;

            while (matcher.find()) {
                if (matcher.start() > lastIndex) {
                    Text segment = new Text(raw.substring(lastIndex, matcher.start()));
                    segment.setFont(Font.font("Segoe UI", 13));
                    flow.getChildren().add(segment);
                }
                String emojiCode = matcher.group(1);
                Node emojiView = IconManager.createEmojiView(emojiCode, 18);
                flow.getChildren().add(emojiView);
                lastIndex = matcher.end();
            }

            if (lastIndex < raw.length()) {
                Text trailingSegment = new Text(raw.substring(lastIndex));
                trailingSegment.setFont(Font.font("Segoe UI", 13));
                flow.getChildren().add(trailingSegment);
            }
            return flow;
        }

        void appendImageCard(String sender, String fileName, long fileSize, String fileId, byte[] thumbnail) {
            lastMessageSender = null;
            VBox card = new VBox(5);
            card.setPadding(new Insets(6, 10, 6, 10));
            card.setStyle("-fx-background-color: #F8FAFC; -fx-border-color: #CBD5E1; -fx-border-radius: 4;"
                    + "-fx-background-radius: 4; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 3, 0, 0, 1);");

            Label title = createLabel(sender + " shared a photo:", "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #1E395B;");

            File cachedThumb = new File("storage/cache/thumbs", fileId + ".jpg");
            if (thumbnail != null && thumbnail.length > 0) {
                try (FileOutputStream fos = new FileOutputStream(cachedThumb)) {
                    fos.write(thumbnail);
                } catch (IOException ignored) {}
            } else if (cachedThumb.exists()) {
                try {
                    thumbnail = Files.readAllBytes(cachedThumb.toPath());
                } catch (IOException ignored) {}
            }

            ImageView iv = null;
            if (thumbnail != null && thumbnail.length > 0) {
                Image img = new Image(new ByteArrayInputStream(thumbnail));
                iv = new ImageView(img);
                iv.setFitWidth(180);
                iv.setPreserveRatio(true);
                iv.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 4, 0, 0, 1);");
            }

            Label desc = createLabel(fileName + " (" + formatByteSize(fileSize) + ")", "-fx-font-size: 11px; -fx-text-fill: #64748B;");

            Button saveBtn = new Button("\uD83D\uDCBE Save As...");
            styleAttachmentButton(saveBtn);
            saveBtn.setOnAction(e -> promptSaveFileAttachment(fileId, fileName));

            HBox actions = new HBox(8, saveBtn);
            if (iv != null) {
                card.getChildren().addAll(title, iv, desc, actions);
            } else {
                card.getChildren().addAll(title, desc, actions);
            }
            messagesContainer.getChildren().add(card);
        }

        void appendFileCard(String sender, String fileName, long fileSize, String fileId) {
            lastMessageSender = null;
            VBox card = new VBox(6);
            card.setPadding(new Insets(6, 10, 6, 10));
            card.setStyle("-fx-background-color: #F8FAFC; -fx-border-color: #CBD5E1; -fx-border-radius: 4;"
                    + "-fx-background-radius: 4; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 3, 0, 0, 1);");

            Label title = createLabel(sender + " shared a file:", "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #1E395B;");
            Label desc = createLabel("\uD83D\uDCC4 " + fileName + " (" + formatByteSize(fileSize) + ")", "-fx-font-size: 12px; -fx-text-fill: #0F172A;");

            Button saveBtn = new Button("Save As...");
            styleAttachmentButton(saveBtn);
            saveBtn.setOnAction(e -> promptSaveFileAttachment(fileId, fileName));

            HBox actionRow = new HBox(8, saveBtn);
            actionRow.setAlignment(Pos.CENTER_LEFT);

            // If the shared file is an audio format (WAV, AIFF, AU), provide inline playback
            if (AudioService.isAudioFile(fileName)) {
                setupInlineAudioPlayer(card, actionRow, fileId, fileName);
            } else {
                card.getChildren().addAll(title, desc, actionRow);
            }
            messagesContainer.getChildren().add(card);
        }

        void appendAudioCard(String sender, long fileSize, String fileId) {
            lastMessageSender = null;
            VBox card = new VBox(6);
            card.setPadding(new Insets(6, 10, 6, 10));
            card.setStyle("-fx-background-color: #F8FAFC; -fx-border-color: #CBD5E1; -fx-border-radius: 4;"
                    + "-fx-background-radius: 4; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.06), 3, 0, 0, 1);");

            Label title = createLabel(sender + " sent a voice clip:", "-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: #1E395B;");
            Label desc = createLabel("Voice Message (" + formatByteSize(fileSize) + ")", "-fx-font-size: 11px; -fx-text-fill: #64748B;");

            HBox actionRow = new HBox(8);
            actionRow.setAlignment(Pos.CENTER_LEFT);

            setupInlineAudioPlayer(card, actionRow, fileId, "voice_clip.wav");
            messagesContainer.getChildren().add(card);
        }

        /**
         * Sets up a playback player inside an attachment card with Play/Stop vector icons,
         * live animated progress bar, and duration timestamp.
         */
        private void setupInlineAudioPlayer(VBox card, HBox actionRow, String fileId, String displayFileName) {
            Button playBtn = new Button("Play");
            playBtn.setGraphic(IconManager.createPlayIcon(10, Color.web("#0284C7")));
            styleAttachmentButton(playBtn);

            ProgressBar audioProgress = new ProgressBar(0);
            audioProgress.setPrefWidth(140);
            audioProgress.setPrefHeight(12);
            audioProgress.setStyle("-fx-accent: #0284C7;");

            Label timeLbl = createLabel("0:00", "-fx-font-size: 10px; -fx-text-fill: #64748B;");

            HBox progressRow = new HBox(6, audioProgress, timeLbl);
            progressRow.setAlignment(Pos.CENTER_LEFT);
            progressRow.setVisible(false);
            progressRow.setManaged(false);

            Timeline progressTracker = new Timeline(new KeyFrame(Duration.millis(80), ev -> {
                if (AudioService.isPlaying()) {
                    double progress = AudioService.getCurrentProgress();
                    audioProgress.setProgress(progress);
                    timeLbl.setText(AudioService.getActiveClipPositionString() + " / " + AudioService.getActiveClipDurationString());
                }
            }));
            progressTracker.setCycleCount(Timeline.INDEFINITE);

            playBtn.setOnAction(e -> {
                // Determine file extension from display name to preserve formats like .mp3, .ogg, .wav
                String ext = ".wav";
                int dotIdx = displayFileName.lastIndexOf('.');
                if (dotIdx > 0) {
                    ext = displayFileName.substring(dotIdx);
                }
                File localCachedAudio = new File("storage/cache/audio", fileId + ext);
                if (AudioService.isPlayingFile(localCachedAudio)) {
                    AudioService.stopActivePlayback();
                    progressTracker.stop();
                    playBtn.setText("Play");
                    playBtn.setGraphic(IconManager.createPlayIcon(10, Color.web("#0284C7")));
                    audioProgress.setProgress(0);
                    return;
                }

                if (localCachedAudio.exists()) {
                    startAudioPlayback(localCachedAudio, playBtn, audioProgress, timeLbl, progressRow, progressTracker);
                } else {
                    playBtn.setText("Loading...");
                    playBtn.setDisable(true);
                    chatClient.downloadFile(fileId, localCachedAudio, new ChatClient.ProgressListener() {
                        @Override
                        public void onProgress(int pct, long transferred, long total) {}

                        @Override
                        public void onComplete(String downloadedName) {
                            Platform.runLater(() -> {
                                playBtn.setDisable(false);
                                startAudioPlayback(localCachedAudio, playBtn, audioProgress, timeLbl, progressRow, progressTracker);
                            });
                        }

                        @Override
                        public void onError(String errorName, Exception ex) {
                            Platform.runLater(() -> {
                                playBtn.setDisable(false);
                                playBtn.setText("Play");
                                playBtn.setGraphic(IconManager.createPlayIcon(10, Color.web("#0284C7")));
                                appendSystemNotice("Failed to stream audio file: " + ex.getMessage());
                            });
                        }
                    });
                }
            });

            actionRow.getChildren().add(0, playBtn);
            card.getChildren().addAll(actionRow, progressRow);
        }

        private void startAudioPlayback(File audioFile, Button playBtn, ProgressBar progress, Label timeLbl,
                                        HBox progressRow, Timeline progressTracker) {
            playBtn.setText("Stop");
            playBtn.setGraphic(IconManager.createStopIcon(10, Color.web("#DC2626")));
            progressRow.setVisible(true);
            progressRow.setManaged(true);
            progress.setProgress(0);
            progressTracker.play();

            boolean started = AudioService.playWavFile(audioFile, () -> Platform.runLater(() -> {
                progressTracker.stop();
                playBtn.setText("Play");
                playBtn.setGraphic(IconManager.createPlayIcon(10, Color.web("#0284C7")));
                progress.setProgress(1.0);
            }));

            if (!started) {
                progressTracker.stop();
                playBtn.setText("Play");
                playBtn.setGraphic(IconManager.createPlayIcon(10, Color.web("#0284C7")));
                progressRow.setVisible(false);
                progressRow.setManaged(false);
                appendSystemNotice("Unable to play audio (unsupported format or audio device busy).");
            }
        }

        private void promptSaveFileAttachment(String fileId, String defaultName) {
            FileChooser fc = new FileChooser();
            fc.setTitle("Save Attachment As...");
            fc.setInitialFileName(defaultName);
            File dest = fc.showSaveDialog(stage);
            if (dest != null) {
                updateProgressDisplay(true, "Downloading " + defaultName + "...", 0);
                chatClient.downloadFile(fileId, dest, new ChatClient.ProgressListener() {
                    @Override
                    public void onProgress(int pct, long transferred, long total) {
                        Platform.runLater(() -> updateProgressDisplay(true, "Downloading " + pct + "%", pct / 100.0));
                    }

                    @Override
                    public void onComplete(String fileName) {
                        Platform.runLater(() -> {
                            updateProgressDisplay(false, "", 0);
                            appendSystemNotice("Downloaded " + fileName + " successfully.");
                        });
                    }

                    @Override
                    public void onError(String fileName, Exception ex) {
                        Platform.runLater(() -> {
                            updateProgressDisplay(false, "", 0);
                            appendSystemNotice("Download failed: " + ex.getMessage());
                        });
                    }
                });
            }
        }

        void appendSystemNotice(String text) {
            Label noticeLbl = createLabel(text, "-fx-font-size: 11px; -fx-text-fill: #777; -fx-font-style: italic;");
            noticeLbl.setWrapText(true);
            noticeLbl.setPadding(new Insets(4, 0, 4, 0));
            messagesContainer.getChildren().add(noticeLbl);
            lastMessageSender = null;
        }

        void loadConversationHistory() {
            List<HistoryManager.HistoryEntry> entries = HistoryManager.load(myUsername, target);
            if (entries.isEmpty()) return;

            Label historyHeader = createLabel("--- Previous Conversation History (" + entries.size() + " messages) ---",
                    "-fx-font-size: 11px; -fx-text-fill: #64748B; -fx-font-style: italic; -fx-padding: 4 0 6 0;");
            historyHeader.setMaxWidth(Double.MAX_VALUE);
            historyHeader.setAlignment(Pos.CENTER);
            messagesContainer.getChildren().add(historyHeader);

            for (HistoryManager.HistoryEntry e : entries) {
                switch (e.type) {
                    case TEXT:
                        appendTextMessage(e.sender, e.content);
                        break;
                    case IMAGE:
                        appendImageCard(e.sender, e.content, e.size, e.fileId, null);
                        break;
                    case FILE:
                        appendFileCard(e.sender, e.content, e.size, e.fileId);
                        break;
                    case AUDIO:
                        appendAudioCard(e.sender, e.size, e.fileId);
                        break;
                    case SYSTEM:
                        appendSystemNotice(e.content);
                        break;
                }
            }

            Label liveBanner = createLabel("--- Live Conversation ---",
                    "-fx-font-size: 11px; -fx-text-fill: #0284C7; -fx-font-weight: bold; -fx-padding: 6 0 4 0;");
            liveBanner.setMaxWidth(Double.MAX_VALUE);
            liveBanner.setAlignment(Pos.CENTER);
            messagesContainer.getChildren().add(liveBanner);
        }
    }

    // =========================================================================
    // ChatClient Listener Callback Implementations
    // =========================================================================

    @Override
    public void onConnected(String serverIp, int port, String username) {
        Platform.runLater(() -> {
            this.myUsername = username;
            displayMainView();
            chatClient.sendPresence(myPresence.name());
        });
    }

    @Override
    public void onConnectionFailed(String errorMessage) {
        Platform.runLater(() -> {
            displayLoginView();
            loginErrorLabel.setText("Sign-in failed: " + errorMessage);
        });
    }

    @Override
    public void onDisconnected(String reason) {
        Platform.runLater(() -> {
            closeAllChatWindows();
            displayLoginView();
            if (reason != null && !reason.isEmpty()) {
                loginErrorLabel.setText("Signed out: " + reason);
            }
        });
    }

    @Override
    public void onTextMessageReceived(String sender, String recipient, String timestamp, String text, boolean isMe) {
        Platform.runLater(() -> {
            String key = resolveConversationKey(sender, recipient, isMe);
            String trimmed = text.trim();
            if ("[WIZZ]".equalsIgnoreCase(trimmed) || "[NUDGE]".equalsIgnoreCase(trimmed)) {
                if (!isMe) {
                    ChatWindow w = openChatWindow(sender);
                    if (w != null) {
                        w.handleIncomingWizz(sender);
                    }
                    showWizzNotification(sender);
                }
                return;
            }
            // Always persist the incoming message in conversation history
            HistoryManager.append(myUsername, key, new HistoryManager.HistoryEntry(
                    HistoryManager.EntryType.TEXT, timestamp, sender, text, 0, null));

            // Append to existing conversation window if open without forcing window pop-up
            ChatWindow w = activeChatWindows.get(key);
            if (w != null) {
                w.appendTextMessage(sender, text);
            }

            // Increment missed counter if this message came from another party and window is unopened or unfocused
            if (!isMe) {
                if (w == null || !w.stage.isShowing() || !w.stage.isFocused()) {
                    incrementMissedMessages(key);
                }
            }
        });
    }

    @Override
    public void onImageMessageReceived(String sender, String recipient, String timestamp, String fileName,
                                       long fileSize, String fileId, byte[] thumbnail, boolean isMe) {
        Platform.runLater(() -> {
            String key = resolveConversationKey(sender, recipient, isMe);
            HistoryManager.append(myUsername, key, new HistoryManager.HistoryEntry(
                    HistoryManager.EntryType.IMAGE, timestamp, sender, fileName, fileSize, fileId));
            ChatWindow w = activeChatWindows.get(key);
            if (w != null) {
                w.appendImageCard(sender, fileName, fileSize, fileId, thumbnail);
            }
            if (!isMe) {
                if (w == null || !w.stage.isShowing() || !w.stage.isFocused()) {
                    incrementMissedMessages(key);
                }
            }
        });
    }

    @Override
    public void onFileMessageReceived(String sender, String recipient, String timestamp, String fileName,
                                      long fileSize, String fileId, boolean isMe) {
        Platform.runLater(() -> {
            String key = resolveConversationKey(sender, recipient, isMe);
            HistoryManager.append(myUsername, key, new HistoryManager.HistoryEntry(
                    HistoryManager.EntryType.FILE, timestamp, sender, fileName, fileSize, fileId));
            ChatWindow w = activeChatWindows.get(key);
            if (w != null) {
                w.appendFileCard(sender, fileName, fileSize, fileId);
            }
            if (!isMe) {
                if (w == null || !w.stage.isShowing() || !w.stage.isFocused()) {
                    incrementMissedMessages(key);
                }
            }
        });
    }

    @Override
    public void onAudioMessageReceived(String sender, String recipient, String timestamp, String fileName,
                                       long fileSize, String fileId, boolean isMe) {
        Platform.runLater(() -> {
            String key = resolveConversationKey(sender, recipient, isMe);
            HistoryManager.append(myUsername, key, new HistoryManager.HistoryEntry(
                    HistoryManager.EntryType.AUDIO, timestamp, sender, fileName, fileSize, fileId));
            ChatWindow w = activeChatWindows.get(key);
            if (w != null) {
                w.appendAudioCard(sender, fileSize, fileId);
            }
            if (!isMe) {
                if (w == null || !w.stage.isShowing() || !w.stage.isFocused()) {
                    incrementMissedMessages(key);
                }
            }
        });
    }

    @Override
    public void onSystemMessage(String timestamp, String message, String recipient) {
        Platform.runLater(() -> {
            String key = (recipient == null || recipient.isEmpty()) ? ChatClient.GROUP_TARGET : resolveConversationKey(null, recipient, true);
            HistoryManager.append(myUsername, key, new HistoryManager.HistoryEntry(
                    HistoryManager.EntryType.SYSTEM, timestamp, "System", message, 0, null));
            ChatWindow w = activeChatWindows.get(key);
            if (w != null) {
                w.appendSystemNotice(message);
            }
        });
    }

    @Override
    public void onUserListUpdated(List<String> users) {
        Platform.runLater(() -> {
            onlineBuddies.clear();
            for (String u : users) {
                if (!u.equalsIgnoreCase(myUsername)) {
                    onlineBuddies.add(u);
                }
            }
            buddyPresences.keySet().removeIf(k -> !onlineBuddies.contains(k));
            if (contactTreeView != null) {
                refreshContactTree();
            }
            for (Map.Entry<String, ChatWindow> entry : activeChatWindows.entrySet()) {
                String target = entry.getKey();
                if (!ChatClient.GROUP_TARGET.equalsIgnoreCase(target)) {
                    PresenceStatus ps = buddyPresences.getOrDefault(target,
                            onlineBuddies.contains(target) ? PresenceStatus.AVAILABLE : PresenceStatus.APPEAR_OFFLINE);
                    entry.getValue().updateContactPresence(ps);
                }
            }
        });
    }

    @Override
    public void onUserPresenceChanged(String username, String presenceStr) {
        Platform.runLater(() -> {
            if (username == null || username.equalsIgnoreCase(myUsername)) return;
            PresenceStatus ps = PresenceStatus.fromString(presenceStr);
            buddyPresences.put(username, ps);
            if (contactTreeView != null) {
                refreshContactTree();
            }
            ChatWindow cw = activeChatWindows.get(username);
            if (cw != null) {
                cw.updateContactPresence(ps);
            }
        });
    }

    /**
     * Displays a desktop notification toast popup in the bottom right corner of the screen
     * when a Wizz is received, playing alert sound and focusing conversation upon click.
     */
    private void showWizzNotification(String sender) {
        Platform.runLater(() -> {
            try {
                Stage toast = new Stage(StageStyle.TRANSPARENT);
                toast.setAlwaysOnTop(true);

                HBox root = new HBox(12);
                root.setAlignment(Pos.CENTER_LEFT);
                root.setPadding(new Insets(12, 16, 12, 16));
                root.setStyle("-fx-background-color: linear-gradient(to bottom, #FFFFFF, #E8F2FE); "
                        + "-fx-border-color: #3B82F6; -fx-border-width: 1.5; -fx-border-radius: 8; "
                        + "-fx-background-radius: 8; "
                        + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 12, 0, 0, 3); "
                        + "-fx-cursor: hand;");

                Node icon = IconManager.createWizzIcon(28, Color.web("#D97706"));
                VBox textVBox = new VBox(2);
                Label titleLabel = new Label("Wizz!");
                titleLabel.setStyle("-fx-font-family: '" + FONT_FAMILY + "'; -fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #1E3A8A;");
                Label msgLabel = new Label(sender + " just sent you a wizz!");
                msgLabel.setStyle("-fx-font-family: '" + FONT_FAMILY + "'; -fx-font-size: 12px; -fx-text-fill: #334155;");
                textVBox.getChildren().addAll(titleLabel, msgLabel);

                Button closeBtn = new Button("✕");
                closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #94A3B8; -fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 0 4 0 4;");
                closeBtn.setOnAction(e -> toast.close());

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                root.getChildren().addAll(icon, textVBox, spacer, closeBtn);

                root.setOnMouseClicked(e -> {
                    if (e.getTarget() != closeBtn) {
                        ChatWindow cw = openChatWindow(sender);
                        if (cw != null) {
                            cw.stage.setIconified(false);
                            cw.stage.toFront();
                            cw.stage.requestFocus();
                        }
                        toast.close();
                    }
                });

                Scene scene = new Scene(root);
                scene.setFill(Color.TRANSPARENT);
                toast.setScene(scene);

                Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
                toast.setX(bounds.getMaxX() - 320);
                toast.setY(bounds.getMaxY() - 85);
                toast.show();

                AudioService.playWizzSound();

                PauseTransition pause = new PauseTransition(Duration.seconds(4.5));
                pause.setOnFinished(ev -> {
                    FadeTransition fade = new FadeTransition(Duration.millis(500), root);
                    fade.setFromValue(1.0);
                    fade.setToValue(0.0);
                    fade.setOnFinished(fev -> toast.close());
                    fade.play();
                });
                pause.play();

            } catch (Exception ex) {
                System.err.println("Could not display wizz toast: " + ex.getMessage());
            }

            try {
                if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
                    new ProcessBuilder("notify-send", "-a", "Messenger", "Wizz!", sender + " just sent you a wizz!").start();
                }
            } catch (Exception ignored) {}
        });
    }

    /**
     * Handles real-time Voice and Video call signaling events.
     * Routes incoming call rings, acceptances, rejections, and terminations
     * to the concerned conversation window.
     */
    @Override
    public void onCallSignalReceived(String sender, String recipient, String timestamp, String signalType, String payload) {
        Platform.runLater(() -> {
            String peer = sender.equalsIgnoreCase("SYSTEM") ? payload : sender;
            if (peer == null || peer.trim().isEmpty()) return;

            ChatWindow w = openChatWindow(peer);

            switch (signalType) {
                case "INVITE_AUDIO":
                    w.showIncomingCallBanner(false);
                    break;
                case "INVITE_VIDEO":
                    w.showIncomingCallBanner(true);
                    break;
                case "ACCEPT":
                    boolean isVideo = "VIDEO".equalsIgnoreCase(payload);
                    w.enterCallMode(isVideo);
                    break;
                case "REJECT":
                    w.exitCallMode(sender + " declined the call.");
                    break;
                case "BUSY":
                    w.exitCallMode(sender + " is currently busy in another call.");
                    break;
                case "OFFLINE":
                    w.exitCallMode(payload + " is currently offline.");
                    break;
                case "END":
                    w.exitCallMode("Call ended by " + sender + ".");
                    break;
            }
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}