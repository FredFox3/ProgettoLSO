package org.trisclient.trisclient;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;


import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


public class HomePageController implements Initializable, NetworkService.ServerListener {

    @FXML private Button buttonCreaPartita;
    @FXML private FlowPane flowPanePartite;
    @FXML private Label labelStatus; // Aggiungi una label per lo stato

    private NetworkService networkService;
    private String playerName;
    private Stage stage; // Per cambiare scena

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }


    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        networkService = new NetworkService();
        buttonCreaPartita.setDisable(true); // Disabilita finché non connesso e nome inviato
        labelStatus.setText("Connecting...");
        // Chiedi il nome utente
        askForNameAndConnect();
    }

    private void askForNameAndConnect() {
        TextInputDialog dialog = new TextInputDialog("Giocatore");
        dialog.setTitle("Nome Giocatore");
        dialog.setHeaderText("Inserisci il tuo nome:");
        dialog.setContentText("Nome:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresentOrElse(name -> {
            if (name.trim().isEmpty()) {
                showError("Nome non valido", "Il nome non può essere vuoto.");
                Platform.runLater(this::askForNameAndConnect); // Richiedi di nuovo
            } else {
                this.playerName = name.trim();
                labelStatus.setText("Connecting as " + playerName + "...");
                networkService.connect("127.0.0.1", 12345, this);
            }
        }, () -> {
            labelStatus.setText("Connessione annullata.");
            // Potresti chiudere l'app o disabilitare i bottoni
        });
    }

    @FXML
    private void handleCreaPartita() {
        System.out.println(getCurrentTimestamp() + " - Create button clicked.");
        buttonCreaPartita.setDisable(true); // Disabilita mentre attende la risposta
        labelStatus.setText("Creating game...");
        networkService.sendCreateGame();
    }

    // --- Implementazione ServerListener ---

    @Override
    public void onConnected() {
        System.out.println(getCurrentTimestamp() + " - GUI: Connected to server.");
        labelStatus.setText("Connected. Waiting for server name request...");
        // Non fare nulla qui, aspetta CMD:GET_NAME
    }

    @Override
    public void onNameRequested() {
        System.out.println(getCurrentTimestamp() + " - GUI: Server requested name. Sending: " + playerName);
        labelStatus.setText("Server requested name. Sending...");
        networkService.sendName(playerName);
    }

    @Override
    public void onNameAccepted() {
        System.out.println(getCurrentTimestamp() + " - GUI: Name accepted by server.");
        labelStatus.setText("Logged in as " + playerName + ". Requesting game list...");
        buttonCreaPartita.setDisable(false); // Abilita creazione partita
        networkService.sendListRequest(); // Richiedi la lista iniziale
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp() + " - GUI: Disconnected. Reason: " + reason);
        showError("Disconnesso", "Connessione persa: " + reason);
        labelStatus.setText("Disconnected: " + reason);
        buttonCreaPartita.setDisable(true);
        flowPanePartite.getChildren().clear(); // Pulisci lista partite
    }

    @Override
    public void onMessageReceived(String rawMessage) {
        System.out.println(getCurrentTimestamp() + " - GUI: Unhandled message: " + rawMessage);
        // Potresti mostrare messaggi sconosciuti in un'area log
    }

    @Override
    public void onGamesList(List<NetworkService.GameInfo> games) {
        System.out.println(getCurrentTimestamp() + " - GUI: Received game list with " + games.size() + " games.");
        labelStatus.setText("Logged in as " + playerName + ". Games available: " + games.size());
        flowPanePartite.getChildren().clear(); // Pulisci prima di aggiungere

        if (games.isEmpty()) {
            flowPanePartite.getChildren().add(new Label("Nessuna partita disponibile al momento."));
        } else {
            for (NetworkService.GameInfo gameInfo : games) {
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/partita-item-view.fxml"));
                    Node gameItemNode = loader.load();
                    PartitaItemController controller = loader.getController();
                    controller.setData(gameInfo.id, gameInfo.creatorName, networkService); // Passa il service!
                    flowPanePartite.getChildren().add(gameItemNode);
                } catch (IOException e) {
                    System.err.println(getCurrentTimestamp() + " - Error loading PartitaItem.fxml");
                    e.printStackTrace();
                }
            }
        }
        buttonCreaPartita.setDisable(false); // Riabilita se era disabilitato
    }

    @Override
    public void onGameCreated(int gameId) {
        System.out.println(getCurrentTimestamp() + " - GUI: Game created with ID: " + gameId);
        labelStatus.setText("Game " + gameId + " created. Waiting for opponent...");
        // Qui potresti navigare a una schermata di attesa o direttamente al gioco
        // Per ora, rimaniamo qui ma potremmo disabilitare 'Crea' e 'Join'
        buttonCreaPartita.setDisable(true);
        disableJoinButtons(); // Disabilita tutti i bottoni "Unisciti"
        // Potrebbe essere utile aggiungere un item "La mia partita in attesa"
    }

    @Override
    public void onJoinOk(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp() + " - GUI: Successfully joined game " + gameId + " as " + symbol + " vs " + opponentName);
        labelStatus.setText("Joined game " + gameId + ". Waiting for start...");
        // *** NON NAVIGARE QUI ***
        // navigateToGameScreen(gameId, symbol, opponentName); // Rimuovi o commenta questa chiamata
    }

    @Override
    public void onGameStart(int gameId, char symbol, String opponentName) {
        // Questo viene ricevuto da entrambi i giocatori
        System.out.println(getCurrentTimestamp() + " - GUI: Game " + gameId + " starting. You are " + symbol + " vs " + opponentName);
        labelStatus.setText("Game " + gameId + " started!");
        // *** NAVIGA QUI (per entrambi i giocatori) ***
        navigateToGameScreen(gameId, symbol, opponentName);
    }

    // --- Callback per tornare alla Home Page ---
    public void returnToHomePage(String statusMessage) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/home-page-view.fxml"));
            Parent homeRoot = loader.load();
            HomePageController newHomeController = loader.getController(); // Ottieni il NUOVO controller

            // IMPORTANTISSIMO: Devi passare l'istanza di NetworkService al nuovo controller
            // e reimpostare il listener! Questo richiede un meccanismo per passare dati
            // tra scene/controller o un NetworkService Singleton.

            // --- SOLUZIONE TEMPORANEA (SE NetworkService NON è Singleton) ---
            // Passa l'istanza corrente al nuovo controller (richiede un metodo in HomePageController)
            // newHomeController.setNetworkService(this.networkService); // Dovresti creare questo metodo
            // Reimposta il listener sul service esistente
            // if(this.networkService != null) {
            //    this.networkService.setServerListener(newHomeController);
            // }
            // Questa parte è delicata e dipende da come gestisci l'istanza di NetworkService.
            // La cosa più sicura per ORA è assicurarsi che la navigazione AL GIOCO funzioni.
            // Il ritorno alla Home potrebbe richiedere più refactoring.

            System.out.println(getCurrentTimestamp()+" - Returning to HomePage. Listener should be reset MANUALLY if needed.");
            // Per ora, il listener rimane GameController finché non ci si riconnette o si riavvia.

            if (stage == null) {
                stage = (Stage) flowPanePartite.getScene().getWindow();
                if (stage == null) {
                    System.err.println("Stage is null in returnToHomePage!"); return;
                }
            }
            stage.setScene(new Scene(homeRoot));
            stage.setTitle("Tris - Lobby");
            stage.show();

        } catch (IOException e) {
            e.printStackTrace();
            showError("Errore UI", "Impossibile tornare alla Home Page.");
        }
    }



    // Metodi non usati direttamente in HomePage ma necessari per l'interfaccia
    @Override public void onBoardUpdate(String[] board) {}
    @Override public void onYourTurn() {}
    @Override public void onGameOver(String result) {}
    @Override public void onOpponentLeft() {}

    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp() + " - GUI: Received error: " + message);
        showError("Errore dal Server", message);
        labelStatus.setText("Error: " + message);
        // Riabilita i bottoni se erano stati disabilitati da un'azione fallita
        if (!networkService.isConnected() || buttonCreaPartita.isDisabled()){
            // Potrebbe essere necessario richiedere di nuovo la lista se l'errore non è fatale
            // networkService.sendListRequest();
            // buttonCreaPartita.setDisable(false); // Fai attenzione a quando riabilitare
        }
        if (message.contains("full") || message.contains("not found") || message.contains("not waiting")) {
            buttonCreaPartita.setDisable(false); // Permetti di riprovare a creare
            networkService.sendListRequest(); // Aggiorna la lista
        }
    }

    // --- Metodi Ausiliari ---

    private void navigateToGameScreen(int gameId, char symbol, String opponentName) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/game-view.fxml")); // Assicurati che il nome FXML sia corretto
            Parent gameRoot = loader.load();
            GameController gameController = loader.getController();

            // Passa le informazioni necessarie al GameController
            // *** IMPORTANTE: Passa l'istanza di NetworkService! ***
            gameController.setupGame(networkService, gameId, symbol, opponentName, this::returnToHomePage); // Passa anche un callback per tornare indietro

            // Ottieni la finestra corrente e cambia scena
            if (stage == null) { // Ottieni lo stage se non l'abbiamo già
                stage = (Stage) flowPanePartite.getScene().getWindow();
            }
            stage.setScene(new Scene(gameRoot));
            stage.setTitle("Tris - Partita " + gameId);
            stage.show();

        } catch (IOException e) {
            e.printStackTrace();
            showError("Errore UI", "Impossibile caricare la schermata di gioco.");
        }
    }


    private void showError(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void disableJoinButtons() {
        for (Node node : flowPanePartite.getChildren()) {
            if (node.getUserData() instanceof PartitaItemController) { // Potremmo settare il controller in UserData
                PartitaItemController controller = (PartitaItemController) node.getUserData();
                // controller.disableJoinButton(); // Metodo da aggiungere a PartitaItemController
            }
            // Oppure cerca il bottone per ID se possibile
            Button joinButton = (Button) node.lookup("#buttonUniscitiPartita");
            if (joinButton != null) {
                joinButton.setDisable(true);
            }
        }
    }
}