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
    @FXML private Label labelStatoPartita;
    @FXML private Button buttonUniscitiPartita;

    // Variabili membro (fields) della classe
    private int gameId;
    private String creatorName; // <--- VARIABILE MEMBRO AGGIUNTA QUI

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    // Metodo setData ora può assegnare a this.creatorName
    public void setData(int gameId, String creatorName, String state, String loggedInPlayerName) {
        this.gameId = gameId;
        this.creatorName = creatorName; // Ora this.creatorName esiste

        Platform.runLater(() -> {
            if (labelNumeroPartita != null) {
                labelNumeroPartita.setText("Game " + gameId + "\n(by " + (creatorName != null ? creatorName : "?") + ")");
            }
            if (labelStatoPartita != null) {
                labelStatoPartita.setText(state != null ? state : "N/A");
                updateStateStyle(state);
            }
            if (buttonUniscitiPartita != null) {
                boolean shouldBeDisabled = true;
                if ("Waiting".equalsIgnoreCase(state)) {
                    if (loggedInPlayerName != null && !loggedInPlayerName.equals(creatorName)) {
                        shouldBeDisabled = false;
                    }
                }
                buttonUniscitiPartita.setDisable(shouldBeDisabled);
                // System.out.println("Setting Join button for game "+gameId+" ("+this.creatorName+"): Disabled="+shouldBeDisabled+" (MyName: "+loggedInPlayerName+", State: "+state+")");
            }
        });
    }

    // Metodo per cambiare stile (INVARIATO)
    private void updateStateStyle(String state) {
        if (labelStatoPartita == null) return;
        labelStatoPartita.getStyleClass().removeAll("state-waiting", "state-inprogress", "state-unknown", "state-finished"); // Aggiunto finished nel remove se usato in css
        if ("Waiting".equalsIgnoreCase(state)) { labelStatoPartita.getStyleClass().add("state-waiting"); }
        else if ("In Progress".equalsIgnoreCase(state)) { labelStatoPartita.getStyleClass().add("state-inprogress"); }
        else if ("Finished".equalsIgnoreCase(state)) { labelStatoPartita.getStyleClass().add("state-finished"); } // Classe per Finished
        else { labelStatoPartita.getStyleClass().add("state-unknown"); }
    }


    // handleUniscitiPartita ora può usare this.creatorName se necessario (anche se usa ancora static)
    @FXML
    private void handleUniscitiPartita() {
        System.out.println(getCurrentTimestamp() + " - PartitaItemController: Join clicked for game ID: " + gameId + " (creator: " + this.creatorName + ")"); // Usa this.creatorName per il log

        // Usa staticPlayerName per il confronto logico
        if (HomePageController.staticPlayerName != null && HomePageController.staticPlayerName.equals(this.creatorName)) {
            System.err.println("Attempted to join own game " + gameId + ". Aborting.");
            showError("Azione non permessa", "Non puoi unirti alla tua stessa partita.");
            if(buttonUniscitiPartita != null) buttonUniscitiPartita.setDisable(true);
            return;
        }

        NetworkService service = HomePageController.networkServiceInstance;
        if (service != null && service.isConnected()) {
            if (buttonUniscitiPartita != null) buttonUniscitiPartita.setDisable(true);
            System.out.println(getCurrentTimestamp() + " - PartitaItemController: Sending JOIN_REQUEST " + gameId);
            service.sendJoinRequest(gameId);
        } else {
            System.err.println(getCurrentTimestamp() + " - PartitaItemController: Cannot join, NetworkService invalid.");
            showError("Errore Connessione", "Impossibile unirsi. Controlla connessione.");
            // Forse riabilitare dopo un timeout? O aspettare onError/lista? Meglio non riabilitare subito.
        }
    }

    // disableJoinButton (INVARIATO)
    public void disableJoinButton() {
        if (buttonUniscitiPartita != null) {
            Platform.runLater(() -> buttonUniscitiPartita.setDisable(true));
        }
    }

    // showError (INVARIATO)
    private void showError(String title, String content) {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> showError(title, content)); return; }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title); alert.setHeaderText(null); alert.setContentText(content);
        try { Stage owner = (buttonUniscitiPartita != null && buttonUniscitiPartita.getScene() != null) ? (Stage) buttonUniscitiPartita.getScene().getWindow() : null; if (owner != null) alert.initOwner(owner); }
        catch (Exception e) { /* Ignora errore owner */ }
        alert.showAndWait();
    }
}