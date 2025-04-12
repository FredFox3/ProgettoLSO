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

    private int gameId;
    private String creatorName;

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    public void setData(int gameId, String creatorName, String state, String loggedInPlayerName, boolean isPlayerAlreadyWaiting) {
        this.gameId = gameId;
        this.creatorName = creatorName;

        Platform.runLater(() -> {
            if (labelNumeroPartita != null) {
                labelNumeroPartita.setText("Partita " + gameId + "\n(di " + (creatorName != null ? creatorName : "?") + ")");
            }
            if (labelStatoPartita != null) {
                switch (state) {
                    case "Waiting":
                        labelStatoPartita.setText("In attesa");
                        break;
                    case "In Progress":
                        labelStatoPartita.setText("In corso");
                        break;
                    case "Finished":
                        labelStatoPartita.setText("Terminata");
                        break;
                    default:
                        labelStatoPartita.setText("");
                }
                updateStateStyle(state);
            }
            if (buttonUniscitiPartita != null) {
                boolean isMyOwnGame = loggedInPlayerName != null && loggedInPlayerName.equals(this.creatorName);
                boolean canJoinThisState = "Waiting".equalsIgnoreCase(state);
                boolean shouldBeDisabled = isPlayerAlreadyWaiting || isMyOwnGame || !canJoinThisState;

                buttonUniscitiPartita.setDisable(shouldBeDisabled);
            }
        });
    }

    private void updateStateStyle(String originalState) {
        if (labelStatoPartita == null) return;
        labelStatoPartita.getStyleClass().removeAll("state-waiting", "state-inprogress", "state-unknown", "state-finished");
        if ("Waiting".equalsIgnoreCase(originalState)) { labelStatoPartita.getStyleClass().add("state-waiting"); }
        else if ("In Progress".equalsIgnoreCase(originalState)) { labelStatoPartita.getStyleClass().add("state-inprogress"); }
        else if ("Finished".equalsIgnoreCase(originalState)) { labelStatoPartita.getStyleClass().add("state-finished"); }
        else { labelStatoPartita.getStyleClass().add("state-unknown"); }
    }


    @FXML
    private void handleUniscitiPartita() {
        System.out.println(getCurrentTimestamp() + " - PartitaItemController: Cliccato Unisciti per ID partita: " + gameId + " (creatore: " + this.creatorName + ")");

        if (HomePageController.staticPlayerName != null && HomePageController.staticPlayerName.equals(this.creatorName)) {
            System.err.println("Tentativo di unirsi alla propria partita " + gameId + ". Annullamento.");
            showError("Azione non permessa", "Non puoi unirti alla tua stessa partita.");
            if(buttonUniscitiPartita != null) buttonUniscitiPartita.setDisable(true);
            return;
        }

        NetworkService service = HomePageController.networkServiceInstance;
        if (service != null && service.isConnected()) {
            if (buttonUniscitiPartita != null) buttonUniscitiPartita.setDisable(true);
            System.out.println(getCurrentTimestamp() + " - PartitaItemController: Invio JOIN_REQUEST " + gameId);
            service.sendJoinRequest(gameId);
        } else {
            System.err.println(getCurrentTimestamp() + " - PartitaItemController: Impossibile unirsi, NetworkService non valido.");
            showError("Errore Connessione", "Impossibile unirsi. Controlla connessione.");
        }
    }

    public void disableJoinButton() {
        if (buttonUniscitiPartita != null) {
            Platform.runLater(() -> buttonUniscitiPartita.setDisable(true));
        }
    }

    private void showError(String title, String content) {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> showError(title, content)); return; }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title); alert.setHeaderText(null); alert.setContentText(content);
        try { Stage owner = (buttonUniscitiPartita != null && buttonUniscitiPartita.getScene() != null) ? (Stage) buttonUniscitiPartita.getScene().getWindow() : null; if (owner != null) alert.initOwner(owner); }
        catch (Exception e) { }
        alert.showAndWait();
    }
}