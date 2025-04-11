package org.trisclient.trisclient;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

public class HomePageController implements Initializable, NetworkService.ServerListener {

    @FXML private Button buttonCreaPartita;
    @FXML private Button buttonRefresh;
    @FXML private FlowPane flowPanePartite;
    @FXML private Label labelStatus;

    // NetworkService e playerName resi statici per mantenerli tra cambi scena
    public static NetworkService networkServiceInstance;
    public static String staticPlayerName;

    private Stage currentStage; // Riferimento allo stage corrente

    // Variabili per caching dati durante la navigazione (stato interno del controller)
    private volatile String[] cachedBoardDuringNavigation = null;
    private final AtomicBoolean cachedTurnDuringNavigation = new AtomicBoolean(false);
    private final AtomicBoolean isNavigatingToGame = new AtomicBoolean(false);

    // Variabile statica volatile per passare la ragione del ritorno alla nuova istanza
    private static volatile String lastReturnReason = null;

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize CALLED. LastReturnReason: " + lastReturnReason);
        cachedBoardDuringNavigation = null;
        cachedTurnDuringNavigation.set(false);
        isNavigatingToGame.set(false);

        String returnReason = lastReturnReason;
        lastReturnReason = null;

        Platform.runLater(() -> {
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize (runLater) START. Consumed ReturnReason: " + returnReason);
            if (currentStage == null && (flowPanePartite != null && flowPanePartite.getScene() != null)) {
                currentStage = (Stage) flowPanePartite.getScene().getWindow();
            } else if(currentStage == null) {
                currentStage = getCurrentStageFallback();
            }

            if (networkServiceInstance != null && networkServiceInstance.isConnected()) {
                System.out.println(getCurrentTimestamp() + " - Initializing: Reusing existing connected NetworkService instance.");
                prepareForReturn(returnReason);
            } else {
                System.out.println(getCurrentTimestamp() + " - Initializing: NetworkService is null or disconnected. Needs new connection.");
                if (networkServiceInstance != null) {
                    System.out.println(getCurrentTimestamp()+" - Initializing: Cleaning up disconnected NetworkService.");
                    networkServiceInstance.cleanupExecutor();
                }
                networkServiceInstance = null;

                labelStatus.setText("Enter name to connect.");
                setButtonsDisabled(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();

                askForNameAndConnect(); // MODIFICATO: Non passa il listener qui
            }
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize (runLater) END");
        });
    }

    private void prepareForReturn(String statusMessage) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): prepareForReturn CALLED with status: " + statusMessage);
        if (networkServiceInstance == null || !networkServiceInstance.isConnected()) {
            System.err.println(getCurrentTimestamp()+" - ERROR in prepareForReturn: NetworkService invalid!");
            Platform.runLater(() -> {
                labelStatus.setText("Error: Connection lost.");
                setButtonsDisabled(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();
            });
            return;
        }

        isNavigatingToGame.set(false);
        cachedBoardDuringNavigation = null;
        cachedTurnDuringNavigation.set(false);

        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Setting listener to THIS instance.");
        networkServiceInstance.setServerListener(this);

        final boolean rematchAccepted = (statusMessage != null && statusMessage.contains("Rematch accepted"));

        Platform.runLater(() -> {
            String initialStatus;
            if (rematchAccepted) {
                initialStatus = "Waiting for new opponent...";
            } else if (statusMessage != null && !statusMessage.trim().isEmpty()) {
                if (statusMessage.contains("Game Over") || statusMessage.contains("Opponent left") ||
                        statusMessage.contains("Rematch declined") || statusMessage.contains("Opponent decided") ||
                        statusMessage.contains("Name already taken") || statusMessage.contains("Lost game")) { // Aggiunti casi ritorno
                    initialStatus = "Last attempt finished. " + (staticPlayerName != null ? " Welcome back, " + staticPlayerName + "!" : "");
                } else if ("VOLUNTARY_LEAVE".equals(statusMessage) || statusMessage.contains("LEFT_")) {
                    initialStatus = "Returned to Lobby." + (staticPlayerName != null ? " Welcome back, " + staticPlayerName + "!" : "");
                } else {
                    initialStatus = statusMessage + (staticPlayerName != null ? " Welcome back, " + staticPlayerName + "!" : "");
                }
            } else {
                initialStatus = "Returned to Lobby."+ (staticPlayerName != null ? " Welcome back, " + staticPlayerName + "!" : "");
            }
            labelStatus.setText(initialStatus);


            if (rematchAccepted) {
                System.out.println(getCurrentTimestamp()+" - HomePageController: Rematch accepted case in prepareForReturn.");
                setButtonsDisabled(true);
                if(buttonCreaPartita != null) buttonCreaPartita.setDisable(true);
                if(buttonRefresh != null) buttonRefresh.setDisable(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();
                Label waitingLabel = new Label("You are waiting for an opponent in your game.");
                if (flowPanePartite != null) flowPanePartite.getChildren().add(waitingLabel);
                System.out.println(getCurrentTimestamp()+" - HomePageController: Skipping LIST request because player is WAITING.");

            } else {
                System.out.println(getCurrentTimestamp()+" - HomePageController: Normal return case in prepareForReturn.");
                setButtonsDisabled(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): prepareForReturn: Requesting game list.");
                networkServiceInstance.sendListRequest();
            }
        });
    }

    // --- askForNameAndConnect AGGIORNATO ---
    /**
     * Chiede il nome all'utente (la prima volta o dopo errore) e avvia la connessione.
     * Gestisce la logica del dialogo iniziale.
     */
    private void askForNameAndConnect() {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): askForNameAndConnect CALLED");

        Platform.runLater(() -> {
            // Assicura che networkService esista
            if (networkServiceInstance == null) {
                System.out.println(getCurrentTimestamp()+" - askForNameAndConnect: Creating new NetworkService instance (was null).");
                networkServiceInstance = new NetworkService();
            } else if (networkServiceInstance.isConnected()){
                System.out.println(getCurrentTimestamp()+" - askForNameAndConnect: Already connected, skipping connection. Name required only.");
                // Se già connesso ma serve un nome (dopo errore), chiama direttamente showNameDialog
                showNameDialogAndSend(null); // Passa null per usare il nome attuale o prompt vuoto
                return; // Esce per non riconnettere
            }

            // Prepara UI per nuova connessione o attesa nome
            labelStatus.setText("Enter name to connect:");
            setButtonsDisabled(true);
            if (flowPanePartite != null) flowPanePartite.getChildren().clear();

            // Avvia la connessione SE NON GIÀ CONNESSO
            // La connessione userà 'this' come listener TEMPORANEO fino al cambio scena
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Connecting using THIS as INITIAL listener.");
            networkServiceInstance.connect("127.0.0.1", 12345, this); // Connessione vera e propria
            // La richiesta del nome verrà dal server tramite onNameRequested
            // Il dialogo iniziale per il nome è gestito ora da onNameRequested e onNameRejected
        });
    }

    // --- NUOVO: showNameDialogAndSend ---
    /**
     * Mostra il dialogo per inserire il nome, con header e precompilazione opzionali.
     * Invia il comando NAME al server se l'utente inserisce un nome valido.
     * @param headerText Testo opzionale per l'header (es. messaggio di errore).
     */
    private void showNameDialogAndSend(String headerText) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): showNameDialogAndSend CALLED. Header: " + headerText);
        Platform.runLater(() -> {
            // Usa networkServiceInstance per la logica interna
            if (networkServiceInstance == null) {
                System.err.println("showNameDialogAndSend: Cannot show dialog, networkService is null.");
                askForNameAndConnect(); // Tenta recupero
                return;
            }

            TextInputDialog dialog = new TextInputDialog(staticPlayerName != null ? staticPlayerName : ""); // Usa nome statico o vuoto
            dialog.setTitle("Inserisci Nome Giocatore");
            dialog.setHeaderText(headerText != null ? headerText : "Inserisci il tuo nome per iniziare:"); // Header personalizzato
            dialog.setContentText("Nome:");
            Stage owner = getCurrentStage();
            if (owner != null) dialog.initOwner(owner);

            Optional<String> result = dialog.showAndWait();
            result.ifPresentOrElse(name -> {
                String trimmedName = name.trim();
                if (trimmedName.isEmpty()) {
                    // Nome vuoto: mostra di nuovo il dialogo con un messaggio di errore
                    showNameDialogAndSend("Attenzione, il nome non può essere vuoto. Inseriscine uno valido:");
                } else {
                    // Nome valido inserito: aggiorna staticPlayerName e invia
                    staticPlayerName = trimmedName;
                    labelStatus.setText("Sending name '" + staticPlayerName + "'...");
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Sending name: " + staticPlayerName);
                    networkServiceInstance.sendName(staticPlayerName);
                }
            }, () -> {
                // L'utente ha annullato il dialogo
                labelStatus.setText("Name entry cancelled. Disconnecting.");
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): User cancelled name dialog. Disconnecting.");
                if(networkServiceInstance != null && networkServiceInstance.isConnected()) {
                    networkServiceInstance.disconnect(); // Disconnetti se annulla
                }
                setButtonsDisabled(true); // Blocca UI
            });
        });
    }


    // --- Gestori Eventi UI ---

    @FXML
    private void handleCreaPartita() {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): handleCreaPartita CALLED");
        if (networkServiceInstance == null || !networkServiceInstance.isConnected()) {
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Not connected. Triggering connection process...");
            askForNameAndConnect(); // Tenta di riconnettere
            return;
        }
        setButtonsDisabled(true);
        labelStatus.setText("Creating game...");
        disableJoinButtons();
        networkServiceInstance.sendCreateGame();
    }

    @FXML
    private void handleRefresh() {
        System.out.println(getCurrentTimestamp() + " - HomePageController (" + this.hashCode() + "): handleRefresh CALLED");
        if (networkServiceInstance != null && networkServiceInstance.isConnected()) {
            setButtonsDisabled(true);
            disableJoinButtons();
            labelStatus.setText("Refreshing game list...");
            networkServiceInstance.sendListRequest();
        } else {
            System.err.println(getCurrentTimestamp() + " - HomePageController (" + this.hashCode() + "): Cannot refresh, not connected.");
            labelStatus.setText("Not connected. Cannot refresh.");
            setButtonsDisabled(true);
            // Potremmo provare a riconnettere? O mostrare bottone 'Connetti'?
            // askForNameAndConnect(); // Forse troppo aggressivo
        }
    }

    // --- Implementazione Metodi ServerListener ---

    @Override
    public void onConnected() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onConnected");
        Platform.runLater(() -> {
            labelStatus.setText("Connected. Waiting for server name request...");
            setButtonsDisabled(true);
        });
    }

    @Override
    public void onNameRequested() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onNameRequested");
        // Adesso, invece di inviare subito il nome (che potrebbe non esserci), mostriamo il dialogo.
        Platform.runLater(() -> {
            showNameDialogAndSend(null); // Mostra il dialogo iniziale per il nome
        });
    }


    // --- NUOVO: onNameRejected ---
    @Override
    public void onNameRejected(String reason) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onNameRejected. Reason: " + reason);
        Platform.runLater(() -> {
            // Mostra di nuovo il dialogo per chiedere un nome diverso
            showNameDialogAndSend("Attenzione, nome già esistente inseriscine un altro");
        });
    }
    // --- FINE NUOVO ---


    @Override
    public void onNameAccepted() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onNameAccepted");
        Platform.runLater(() -> {
            labelStatus.setText("Logged in as " + staticPlayerName + ". Requesting game list...");
            setButtonsDisabled(true); // Attende lista giochi
        });
        // Solo DOPO che il nome è accettato, chiediamo la lista
        networkServiceInstance.sendListRequest();
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onDisconnected. Reason: " + reason);
        lastReturnReason = reason;

        Platform.runLater(() -> {
            labelStatus.setText("Disconnected: " + reason);
            setButtonsDisabled(true);
            if (flowPanePartite != null) flowPanePartite.getChildren().clear();
            System.out.println(getCurrentTimestamp() + " - GUI: Disconnected state UI updated.");

            boolean voluntaryDisconnect = "VOLUNTARY_LEAVE".equals(reason)
                    || "Disconnected by client".equals(reason)
                    || reason.contains("Rematch")
                    || reason.contains("Opponent decided")
                    || reason.contains("Name entry cancelled"); // Aggiunto motivo per cui disconnettiamo
            if (!voluntaryDisconnect && !reason.contains("Server is shutting down")) {
                showError("Disconnected", "Unexpected connection loss: " + reason);
            }

            // POTREMMO AGGIUNGERE QUI UN BOTTONE "RICONNETTI"
            // if(buttonReconnect != null) buttonReconnect.setVisible(true);

        });
    }


    @Override
    public void onGamesList(List<NetworkService.GameInfo> games) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onGamesList with " + games.size() + " games.");

        Platform.runLater(() -> {
            if (flowPanePartite == null) {
                System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): FATAL - flowPanePartite is NULL!");
                return;
            }

            boolean amIWaiting = false;
            int myWaitingGameId = -1;

            flowPanePartite.getChildren().clear();
            int displayedGamesCount = 0;

            if (games != null) {
                for (NetworkService.GameInfo gameInfo : games) {
                    if (gameInfo == null) continue;

                    boolean isMyWaitingGame = staticPlayerName != null &&
                            staticPlayerName.equals(gameInfo.creatorName) &&
                            "Waiting".equalsIgnoreCase(gameInfo.state);

                    if (isMyWaitingGame) {
                        amIWaiting = true;
                        myWaitingGameId = gameInfo.id;
                        System.out.println("onGamesList: Player '" + staticPlayerName + "' is WAITING in game " + gameInfo.id + ". Skipping display.");
                        continue;
                    }

                    try {
                        FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/partita-item-view.fxml"));
                        Node gameItemNode = loader.load();
                        PartitaItemController controller = loader.getController();
                        controller.setData(gameInfo.id, gameInfo.creatorName, gameInfo.state, staticPlayerName);
                        flowPanePartite.getChildren().add(gameItemNode);
                        displayedGamesCount++;
                    } catch (Exception e) {
                        System.err.println(getCurrentTimestamp() + " - Error loading/setting PartitaItem: "+e.getMessage());
                        e.printStackTrace();
                    }
                }
            }

            if (displayedGamesCount == 0 && !amIWaiting) {
                flowPanePartite.getChildren().add(new Label("No other games available."));
            }

            boolean isConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());
            if(isConnected){
                if(buttonRefresh != null) buttonRefresh.setDisable(false);

                if (amIWaiting) {
                    if(buttonCreaPartita != null) buttonCreaPartita.setDisable(true);
                    labelStatus.setText("Waiting for opponent in your game " + (myWaitingGameId > 0 ? myWaitingGameId : "") + "...");
                } else {
                    if(buttonCreaPartita != null) buttonCreaPartita.setDisable(false);
                    // Aggiorna status solo se rilevante per la lista
                    if (labelStatus.getText() == null || !labelStatus.getText().startsWith("Logged in")) {
                        labelStatus.setText("Logged in as " + staticPlayerName + ". Available games: " + displayedGamesCount);
                    }
                }
            } else {
                if(buttonCreaPartita != null) buttonCreaPartita.setDisable(true);
                if(buttonRefresh != null) buttonRefresh.setDisable(true);
                if(labelStatus!=null && !labelStatus.getText().startsWith("Disconnected")) labelStatus.setText("Disconnected.");
                disableJoinButtons();
            }
        });
    }

    @Override
    public void onGameCreated(int gameId) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onGameCreated for game " + gameId);
        Platform.runLater(() -> {
            labelStatus.setText("Game " + gameId + " created. Waiting for opponent...");
            // Qui potremmo evitare di disabilitare tutto e solo aggiornare
            // la lista giochi (magari con un messaggio specifico)
            setButtonsDisabled(true); // Per ora manteniamo il comportamento vecchio
            disableJoinButtons();
            // Il server dovrebbe inviare un LIST aggiornato? O aspettiamo refresh?
            // Chiediamo un refresh per mostrare subito lo stato di attesa
            handleRefresh();
        });
    }

    @Override
    public void onJoinRequestSent(int gameId) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onJoinRequestSent for game " + gameId);
        Platform.runLater(() -> {
            labelStatus.setText("Request sent for game " + gameId + ". Waiting for approval...");
            setButtonsDisabled(true);
            disableJoinButtons();
        });
    }

    @Override
    public void onJoinRequestReceived(String requesterName) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onJoinRequestReceived from " + requesterName);
        Platform.runLater(() -> {
            if (networkServiceInstance == null || !networkServiceInstance.isConnected()) return;

            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Join Request");
            alert.setHeaderText("Player '" + requesterName + "' wants to join your game.");
            alert.setContentText("Do you want to accept?");
            ButtonType buttonTypeAccept = new ButtonType("Accept");
            ButtonType buttonTypeReject = new ButtonType("Reject");
            alert.getButtonTypes().setAll(buttonTypeAccept, buttonTypeReject);
            Stage owner = getCurrentStage();
            if (owner != null) alert.initOwner(owner);

            Optional<ButtonType> result = alert.showAndWait();
            String decision = "Reject";
            if (result.isPresent() && result.get() == buttonTypeAccept) {
                decision = "Accept";
            }
            labelStatus.setText(decision+"ing " + requesterName + "...");
            if("Accept".equals(decision)) networkServiceInstance.sendAcceptRequest(requesterName);
            else networkServiceInstance.sendRejectRequest(requesterName);
        });
    }

    @Override
    public void onJoinAccepted(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onJoinAccepted for game " + gameId + ".");
        Platform.runLater(() -> {
            labelStatus.setText("Join request ACCEPTED! Starting game " + gameId + "...");
        });
        // Navigazione spostata in onGameStart
    }

    @Override
    public void onJoinRejected(int gameId, String creatorName) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onJoinRejected for game " + gameId);
        Platform.runLater(() -> {
            showError("Join Rejected", "Request to join game " + gameId + " rejected by " + creatorName + ".");
            labelStatus.setText("Join request for game " + gameId + " rejected.");
            boolean isConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());
            setButtonsDisabled(!isConnected);
            handleRefresh();
        });
    }

    @Override
    public void onActionConfirmed(String message) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onActionConfirmed: " + message);
        Platform.runLater(() -> {
            if (message.startsWith("Rejected request from")) {
                labelStatus.setText(message + ". Still waiting...");
                setButtonsDisabled(true); // Rimane disabilitato se in attesa
                handleRefresh(); // Aggiorna per pulire eventuale stato vecchio
                System.out.println("onActionConfirmed: User rejected player, remaining in WAITING state.");
            }
            // Altri casi 'RESP:' ?
        });
    }


    @Override
    public void onGameStart(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onGameStart received for game " + gameId);
        lastReturnReason = null; // Pulisci motivo ritorno prima di navigare
        Platform.runLater(() -> labelStatus.setText("Game " + gameId + " starting..."));
        isNavigatingToGame.set(true); // <<< IMPOSTA FLAG DI NAVIGAZIONE
        cachedBoardDuringNavigation = null; // Reset cache
        cachedTurnDuringNavigation.set(false); // Reset cache
        navigateToGameScreen(gameId, symbol, opponentName); // <<< NAVIGA
    }


    // --- Listener non chiamati qui ---
    // Loggano se chiamati inaspettatamente o se messaggi arrivano durante la navigazione
    @Override public void onBoardUpdate(String[] board) { if (isNavigatingToGame.get()) cachedBoardDuringNavigation = board; else System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onBoardUpdate !!! Board: "+Arrays.toString(board)); }
    @Override public void onYourTurn() { if (isNavigatingToGame.get()) cachedTurnDuringNavigation.set(true); else System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onYourTurn !!!"); }
    @Override public void onGameOver(String result) { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onGameOver("+result+") !!!"); }
    @Override public void onOpponentLeft() { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onOpponentLeft !!!"); }
    @Override public void onRematchOffer() { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onRematchOffer !!!");}
    @Override public void onRematchAccepted(int gameId) { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onRematchAccepted("+gameId+") !!!");}
    @Override public void onRematchDeclined() { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onRematchDeclined !!!");}
    @Override public void onOpponentRematchDecision(boolean opponentAccepted) { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onOpponentRematchDecision("+opponentAccepted+") !!!");}
    @Override public void onMessageReceived(String rawMessage) { System.err.println(getCurrentTimestamp() + " - HomePage: !!! Unexpected raw message: " + rawMessage + " !!!"); }


    // --- Gestione Errori Generici ---
    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onError: " + message + " | isNavigating="+isNavigatingToGame.get());
        Platform.runLater(() -> {
            // Mostra sempre l'errore all'utente
            showError("Server Error", message);

            // Se stavamo navigando, abortisci e ripristina
            if (isNavigatingToGame.compareAndSet(true, false)) {
                System.err.println(getCurrentTimestamp() + " - HomePage: Error during game navigation. Aborting.");
                if(labelStatus!=null) labelStatus.setText("Error starting game: " + message);
                boolean stillConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());
                setButtonsDisabled(!stillConnected);
                if(stillConnected) handleRefresh();
                return; // Esci, abbiamo gestito l'errore durante navigazione
            }

            // Altrimenti, errore generico in Lobby
            if(labelStatus != null) labelStatus.setText("Error: " + message);

            // Se l'errore indica un problema grave (es. "full", "generic", "invalid state")
            // potrebbe essere opportuno resettare/aggiornare lo stato.
            boolean stillConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());
            setButtonsDisabled(!stillConnected);
            // Prova a fare refresh SE connesso E l'errore non è di disconnessione imminente
            if(stillConnected && !message.contains("Server is shutting down") && !message.contains("full")){
                handleRefresh(); // Tenta refresh
            }
        });
    }

    // --- Metodo per tornare alla Home (chiamato da GameController) ---
    public void returnToHomePage(String statusMessage) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage CALLED with message: " + statusMessage);
        lastReturnReason = statusMessage; // Salva per la NUOVA istanza

        Platform.runLater(() -> {
            try {
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage (runLater) START for reason: " + lastReturnReason);
                Stage stageToUse = getCurrentStage();
                if (stageToUse == null) throw new IOException("Stage is NULL, cannot return to home!");

                FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/home-page-view.fxml"));
                Parent homeRoot = loader.load();
                // HomePageController newController = loader.getController(); // NON serve il controller qui
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Loaded new home-page-view.fxml. New controller hash: "+loader.getController().hashCode());

                Scene scene = stageToUse.getScene();
                if (scene == null) { scene = new Scene(homeRoot); stageToUse.setScene(scene); }
                else { scene.setRoot(homeRoot); }

                stageToUse.setTitle("Tris - Lobby");
                stageToUse.show();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage (runLater) END. Stage is showing Home View.");

            } catch (Exception e) {
                System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! CRITICAL EXCEPTION during returnToHomePage !!!");
                e.printStackTrace();
                showError("Critical UI Error", "Failed to return to Home Page.\n" + e.getMessage());
                Platform.exit();
            }
        });
    }

    // --- Metodi Helper UI (invariati) ---

    private void navigateToGameScreen(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): navigateToGameScreen CALLED for game " + gameId);
        Platform.runLater(() -> {
            final String[] boardToPass = cachedBoardDuringNavigation;
            final boolean turnToPass = cachedTurnDuringNavigation.getAndSet(false);
            cachedBoardDuringNavigation = null;

            try {
                Stage stageToUse = getCurrentStage();
                if (stageToUse == null) throw new IOException("Cannot navigate: Stage not found!");

                FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/game-view.fxml"));
                Parent gameRoot = loader.load();
                GameController gameController = loader.getController();
                System.out.println(getCurrentTimestamp()+" - HomePage Nav: game-view loaded. Controller: "+gameController.hashCode());

                gameController.setupGame(networkServiceInstance, gameId, symbol, opponentName, this::returnToHomePage, boardToPass, turnToPass);

                Scene scene = stageToUse.getScene();
                if (scene == null) { scene = new Scene(gameRoot); stageToUse.setScene(scene); }
                else { scene.setRoot(gameRoot); }

                stageToUse.setTitle("Tris - Game " + gameId + " vs " + opponentName);
                stageToUse.show();
                System.out.println(getCurrentTimestamp()+" - HomePage Nav: Stage showing Game View.");
                isNavigatingToGame.set(false);

            } catch (Exception e) {
                System.err.println(getCurrentTimestamp()+" - HomePage Nav: !!! EXCEPTION navigating to game screen !!!");
                e.printStackTrace();
                showError("Critical UI Error", "Cannot load game screen.\n" + e.getMessage());
                isNavigatingToGame.set(false);
                setButtonsDisabled(!(networkServiceInstance != null && networkServiceInstance.isConnected()));
                handleRefresh();
                if(labelStatus != null) labelStatus.setText("Error loading game.");
            }
        });
    }

    private void showError(String title, String content) {
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> showError(title, content)); return; }
        try {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title); alert.setHeaderText(null); alert.setContentText(content);
            Stage owner = getCurrentStage(); if(owner != null) alert.initOwner(owner);
            alert.showAndWait();
        } catch (Exception e) { System.err.println("Error showing ERROR alert: "+e.getMessage()); }
    }

    private void disableJoinButtons() {
        if(flowPanePartite == null) return;
        Platform.runLater(() -> {
            flowPanePartite.getChildren().forEach(node -> {
                Button joinButton = (Button) node.lookup("#buttonUniscitiPartita");
                if (joinButton != null) joinButton.setDisable(true);
            });
        });
    }

    private void setButtonsDisabled(boolean disabled) {
        Platform.runLater(() -> {
            if (buttonCreaPartita != null) buttonCreaPartita.setDisable(disabled);
            if (buttonRefresh != null) buttonRefresh.setDisable(disabled);
            if(disabled) disableJoinButtons();
        });
    }

    private Stage getCurrentStage() {
        try {
            if (currentStage != null && currentStage.isShowing()) return currentStage;
            Node node = null;
            if(labelStatus != null && labelStatus.getScene() != null) node = labelStatus;
            else if (flowPanePartite != null && flowPanePartite.getScene() != null) node = flowPanePartite;
            else if (buttonCreaPartita != null && buttonCreaPartita.getScene() != null) node = buttonCreaPartita;

            if (node != null && node.getScene().getWindow() instanceof Stage) {
                currentStage = (Stage) node.getScene().getWindow();
                if(currentStage.isShowing()) return currentStage;
            }
            return getCurrentStageFallback();
        } catch (Exception e) {
            System.err.println("Exception getting current stage: " + e.getMessage());
            return null;
        }
    }

    private Stage getCurrentStageFallback() {
        return Stage.getWindows().stream()
                .filter(Window::isShowing)
                .filter(w -> w instanceof Stage)
                .map(w -> (Stage)w)
                .findFirst()
                .orElse(null);
    }

} // Fine classe HomePageController