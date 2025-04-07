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
    @FXML private Label labelStatoPartita; // NUOVA LABEL
    @FXML private Button buttonUniscitiPartita;

    private int gameId;

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    // Metodo setData aggiornato per accettare e visualizzare lo stato
    public void setData(int gameId, String creatorName, String state) {
        this.gameId = gameId;
        Platform.runLater(() -> {
            labelNumeroPartita.setText("Partita " + gameId + "\n(da " + creatorName + ")");
            labelStatoPartita.setText(state != null ? state : "N/A"); // Mostra lo stato

            // Cambia stile in base allo stato (opzionale, ma utile)
            updateStateStyle(state);

            // Disabilita il join se la partita non è in attesa (se mai listassimo altri stati)
            buttonUniscitiPartita.setDisable(!("Waiting".equalsIgnoreCase(state)));

        });
    }

    // Metodo per cambiare stile (opzionale)
    private void updateStateStyle(String state) {
        if (labelStatoPartita == null) return;
        // Rimuovi stili precedenti per evitare conflitti
        labelStatoPartita.getStyleClass().removeAll("state-waiting", "state-inprogress", "state-unknown");

        if ("Waiting".equalsIgnoreCase(state)) {
            labelStatoPartita.getStyleClass().add("state-waiting");
            // Puoi impostare il colore direttamente o usare classi CSS:
            // labelStatoPartita.setStyle("-fx-text-fill: orange;");
        } else if ("In Progress".equalsIgnoreCase(state)) {
            labelStatoPartita.getStyleClass().add("state-inprogress");
            // labelStatoPartita.setStyle("-fx-text-fill: green;");
        } else {
            labelStatoPartita.getStyleClass().add("state-unknown");
            // labelStatoPartita.setStyle("-fx-text-fill: grey;");
        }
    }


    @FXML
    private void handleUniscitiPartita() {
        System.out.println(getCurrentTimestamp() + " - PartitaItemController: Join button clicked for game ID: " + gameId);

        NetworkService service = HomePageController.networkServiceInstance;

        if (service != null && service.isConnected()) {
            buttonUniscitiPartita.setDisable(true);
            System.out.println(getCurrentTimestamp() + " - PartitaItemController: Sending JOIN_REQUEST " + gameId);
            service.sendJoinRequest(gameId);
        } else {
            System.err.println(getCurrentTimestamp() + " - PartitaItemController: Cannot join, NetworkService not available or not connected.");
            showError("Errore di Connessione", "Impossibile unirsi alla partita. Controlla la connessione al server.");
            // Riabilita se fallisce subito
            // buttonUniscitiPartita.setDisable(false); // Considera se riabilitare qui o attendere onError
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