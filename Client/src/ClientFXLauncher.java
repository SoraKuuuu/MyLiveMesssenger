import javafx.application.Application;

/**
 * Entry point launcher for JavaFX client.
 * Bypasses JavaFX runtime component checks when launching from IDE or non-modular setups.
 */
public class ClientFXLauncher {
    public static void main(String[] args) {
        Application.launch(ClientFX.class, args);
    }
}
