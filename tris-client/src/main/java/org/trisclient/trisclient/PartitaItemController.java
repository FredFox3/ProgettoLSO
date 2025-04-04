package org.trisclient.trisclient;

import javafx.application.Platform; // Importa Platform
import javafx.fxml.FXML;
import javafx.scene.control.Alert; // Importa Alert
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage; // Importa Stage


import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


public class PartitaItemController {

    @FXML private Label labelNumeroPartita;
    @FXML private Button buttonUniscitiPartita; // Assicurati che abbia fx:id="buttonUniscitiPartita" nel FXML

    private int gameId;
    // Rimosso: private NetworkService networkService; // Non serve più come campo

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    // Metodo per impostare i dati dall'HomePageController
    // Rimuovi il parametro NetworkService service
    public void setData(int gameId, String creatorName) {
        this.gameId = gameId;
        // Aggiorna UI nel Platform.runLater per sicurezza
        Platform.runLater(() -> {
            labelNumeroPartita.setText("Partita " + gameId + "\n(da " + creatorName + ")");
        });
    }

    @FXML
    private void handleUniscitiPartita() {
        System.out.println(getCurrentTimestamp() + " - PartitaItemController: Join button clicked for game ID: " + gameId);

        // Accedi direttamente all'istanza statica da HomePageController
        NetworkService service = HomePageController.networkServiceInstance;

        if (service != null && service.isConnected()) {
            buttonUniscitiPartita.setDisable(true); // Disabilita mentre si attende
            System.out.println(getCurrentTimestamp() + " - PartitaItemController: Sending JOIN " + gameId);
            service.sendJoinGame(gameId);
        } else {
            System.err.println(getCurrentTimestamp() + " - PartitaItemController: Cannot join, NetworkService not available or not connected.");
            showError("Errore di Connessione", "Impossibile unirsi alla partita. Controlla la connessione al server.");
            buttonUniscitiPartita.setDisable(false); // Riabilita il bottone se il tentativo fallisce subito
        }
    }

    // Potrebbe servire per disabilitarlo dalla HomePage
    public void disableJoinButton() {
        // Usa Platform.runLater se chiamato da un altro thread
        Platform.runLater(() -> buttonUniscitiPartita.setDisable(true));
    }

    // Metodo helper per mostrare errori
    private void showError(String title, String content) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showError(title, content));
            return;
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        try { // Try-catch per evitare errori se la scena/finestra non è disponibile
            Stage owner = (buttonUniscitiPartita != null && buttonUniscitiPartita.getScene() != null) ? (Stage) buttonUniscitiPartita.getScene().getWindow() : null;
            if (owner != null) alert.initOwner(owner);
        } catch (Exception e) { System.err.println("Error getting owner stage for error alert: "+e.getMessage());}
        alert.showAndWait();
    }
}