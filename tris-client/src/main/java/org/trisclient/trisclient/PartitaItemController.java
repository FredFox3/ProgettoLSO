package org.trisclient.trisclient;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


public class PartitaItemController {

    @FXML private Label labelNumeroPartita;
    @FXML private Button buttonUniscitiPartita;

    private int gameId;
    private NetworkService networkService; // Riferimento al service

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    // Metodo per impostare i dati dall'HomePageController
    public void setData(int gameId, String creatorName, NetworkService service) {
        this.gameId = gameId;
        this.networkService = service;
        labelNumeroPartita.setText("Partita " + gameId + "\n(da " + creatorName + ")");
    }

    @FXML
    private void handleUniscitiPartita() {
        System.out.println(getCurrentTimestamp() + " - Join button clicked for game ID: " + gameId);
        if (networkService != null && networkService.isConnected()) {
            buttonUniscitiPartita.setDisable(true); // Disabilita mentre si attende
            networkService.sendJoinGame(gameId);
        } else {
            System.err.println(getCurrentTimestamp() + " - Cannot join, NetworkService not available or not connected.");
            // Mostra un errore all'utente?
            labelNumeroPartita.getScene().lookup("#labelStatus"); // Trova la label di stato globale (brutto modo)
            // Aggiorna labelStatus? Serve un modo migliore per comunicare errori globali.
        }
    }

    // Potrebbe servire per disabilitarlo dalla HomePage
    public void disableJoinButton() {
        buttonUniscitiPartita.setDisable(true);
    }
}