package org.trisclient.trisclient;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


public class PartitaItemController {

    @FXML private Label labelNumeroPartita;
    @FXML private Button buttonUniscitiPartita;

    private int gameId;

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    public void setData(int gameId, String creatorName) {
        this.gameId = gameId;
        Platform.runLater(() -> {
            labelNumeroPartita.setText("Partita " + gameId + "\n(da " + creatorName + ")");
        });
    }

    @FXML
    private void handleUniscitiPartita() {
        System.out.println(getCurrentTimestamp() + " - PartitaItemController: Join button clicked for game ID: " + gameId);

        NetworkService service = HomePageController.networkServiceInstance;

        if (service != null && service.isConnected()) {
            buttonUniscitiPartita.setDisable(true); // Disable immediately
            System.out.println(getCurrentTimestamp() + " - PartitaItemController: Sending JOIN_REQUEST " + gameId);
            service.sendJoinRequest(gameId);
            // User now waits for HomePageController to handle the response (accepted/rejected)
            // Consider adding feedback like updating a status label in HomePageController
        } else {
            System.err.println(getCurrentTimestamp() + " - PartitaItemController: Cannot join, NetworkService not available or not connected.");
            showError("Errore di Connessione", "Impossibile unirsi alla partita. Controlla la connessione al server.");
            buttonUniscitiPartita.setDisable(false); // Re-enable if connection failed
        }
    }

    public void disableJoinButton() {
        Platform.runLater(() -> buttonUniscitiPartita.setDisable(true));
    }

    private void showError(String title, String content) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showError(title, content));
            return;
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        try {
            Stage owner = (buttonUniscitiPartita != null && buttonUniscitiPartita.getScene() != null) ? (Stage) buttonUniscitiPartita.getScene().getWindow() : null;
            if (owner != null) alert.initOwner(owner);
        } catch (Exception e) { System.err.println("Error getting owner stage for error alert: "+e.getMessage());}
        alert.showAndWait();
    }
}