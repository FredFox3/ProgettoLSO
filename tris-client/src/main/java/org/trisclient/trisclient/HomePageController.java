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

    /**
     * Metodo Initialize aggiornato: chiamato ogni volta che HomePage viene caricata.
     * Deve decidere se riutilizzare NetworkService esistente o crearne uno nuovo.
     */
    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize CALLED. LastReturnReason: " + lastReturnReason);
        // Resetta stati specifici dell'istanza del controller
        cachedBoardDuringNavigation = null;
        cachedTurnDuringNavigation.set(false);
        isNavigatingToGame.set(false);

        String returnReason = lastReturnReason; // Legge e "consuma" la ragione del ritorno
        lastReturnReason = null;

        Platform.runLater(() -> {
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize (runLater) START. Consumed ReturnReason: " + returnReason);
            // Ottieni riferimento allo Stage il prima possibile
            if (currentStage == null && (flowPanePartite != null && flowPanePartite.getScene() != null)) {
                currentStage = (Stage) flowPanePartite.getScene().getWindow();
            } else if(currentStage == null) {
                currentStage = getCurrentStageFallback(); // Tentativo di fallback
            }

            // --- LOGICA CHIAVE ---
            if (networkServiceInstance != null && networkServiceInstance.isConnected()) {
                // CASO 1: NetworkService esiste ed è connesso -> Stiamo tornando da GameController
                System.out.println(getCurrentTimestamp() + " - Initializing: Reusing existing connected NetworkService instance.");
                prepareForReturn(returnReason); // Chiama prepareForReturn per agganciare questo listener e aggiornare l'UI
            } else {
                // CASO 2: NetworkService è null o disconnesso -> Prima esecuzione o errore precedente
                System.out.println(getCurrentTimestamp() + " - Initializing: NetworkService is null or disconnected. Needs new connection.");
                // Pulisce eventuali risorse residue se l'istanza esisteva ma era disconnessa
                if (networkServiceInstance != null) {
                    System.out.println(getCurrentTimestamp()+" - Initializing: Cleaning up disconnected NetworkService.");
                    networkServiceInstance.cleanupExecutor();
                }
                networkServiceInstance = null; // Assicura che sia null prima di chiedere il nome

                // Prepara UI per nuova connessione
                labelStatus.setText("Enter name to connect.");
                setButtonsDisabled(true); // Disabilita tutto
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();

                // Chiedi il nome e crea una nuova istanza di NetworkService
                askForNameAndConnect();
            }
            // --- FINE LOGICA CHIAVE ---

            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize (runLater) END");
        });
    }

    /**
     * Prepara l'HomePage quando si ritorna da GameController con una connessione attiva.
     * Aggancia questo controller come listener e richiede un refresh della lista partite.
     * @param statusMessage Messaggio ricevuto dal GameController (motivo del ritorno).
     */
    private void prepareForReturn(String statusMessage) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): prepareForReturn CALLED with status: " + statusMessage);
        if (networkServiceInstance == null || !networkServiceInstance.isConnected()) {
            System.err.println(getCurrentTimestamp()+" - ERROR in prepareForReturn: NetworkService invalid!");
            // Gestione errore - forse tornare ad askForNameAndConnect
            Platform.runLater(() -> {
                labelStatus.setText("Error: Connection lost.");
                setButtonsDisabled(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();
                // askForNameAndConnect(); // Potrebbe causare loop se la connessione fallisce ripetutamente
            });
            return;
        }

        isNavigatingToGame.set(false);
        cachedBoardDuringNavigation = null;
        cachedTurnDuringNavigation.set(false);

        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Setting listener to THIS instance.");
        networkServiceInstance.setServerListener(this);

        // --- LOGICA MODIFICATA ---
        final boolean rematchAccepted = (statusMessage != null && statusMessage.contains("Rematch accepted"));

        Platform.runLater(() -> {
            // Imposta messaggio di status iniziale
            String initialStatus;
            if (rematchAccepted) {
                initialStatus = "Waiting for new opponent..."; // Messaggio specifico
            } else if (statusMessage != null && !statusMessage.trim().isEmpty()) {
                // Usa la logica precedente per altri messaggi di ritorno
                if (statusMessage.contains("Game Over") || statusMessage.contains("Opponent left") ||
                        statusMessage.contains("Rematch declined") || statusMessage.contains("Opponent decided")) {
                    initialStatus = "Last game finished. " + (staticPlayerName != null ? " Welcome back, " + staticPlayerName + "!" : "");
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
                // CASO SPECIALE: Vincitore accetta rematch -> È in stato WAITING
                System.out.println(getCurrentTimestamp()+" - HomePageController: Rematch accepted case in prepareForReturn.");
                // Aggiorna subito l'UI per lo stato WAITING
                setButtonsDisabled(true); // Disabilita Crea e Refresh (già fatto, ma per sicurezza)
                if(buttonCreaPartita != null) buttonCreaPartita.setDisable(true);
                if(buttonRefresh != null) buttonRefresh.setDisable(true); // Anche refresh è inutile qui
                if (flowPanePartite != null) flowPanePartite.getChildren().clear(); // Pulisci lista (non dovrebbe esserci nulla di rilevante)
                // Aggiungi messaggio "Sei in attesa" se non c'è lista
                Label waitingLabel = new Label("You are waiting for an opponent in your game.");
                // TODO: Dovremmo avere l'ID della partita qui? Potremmo passarlo nel messaggio da GameController
                if (flowPanePartite != null) flowPanePartite.getChildren().add(waitingLabel);
                // *** NON INVIARE LIST ***
                System.out.println(getCurrentTimestamp()+" - HomePageController: Skipping LIST request because player is WAITING.");

            } else {
                // CASO NORMALE: Giocatore torna in LOBBY -> Invia LIST
                System.out.println(getCurrentTimestamp()+" - HomePageController: Normal return case in prepareForReturn.");
                // Disabilita temporaneamente i bottoni, onGamesList li riabiliterà
                setButtonsDisabled(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();
                // Richiedi la lista giochi aggiornata
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): prepareForReturn: Requesting game list.");
                networkServiceInstance.sendListRequest();
            }
        });
        // --- FINE LOGICA MODIFICATA ---
    }


    /**
     * Chiede il nome all'utente e avvia la connessione.
     * Crea una nuova istanza di NetworkService se networkServiceInstance è null.
     */
    private void askForNameAndConnect() {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): askForNameAndConnect CALLED");

        Platform.runLater(() -> {
            // Crea NetworkService solo se non esiste (initialize dovrebbe aver già pulito se necessario)
            if (networkServiceInstance == null) {
                System.out.println(getCurrentTimestamp()+" - askForNameAndConnect: Creating new NetworkService instance because it was null.");
                networkServiceInstance = new NetworkService();
            }

            // Mostra dialogo per il nome
            TextInputDialog dialog = new TextInputDialog(staticPlayerName != null ? staticPlayerName : "Giocatore"); // Precompila se esiste
            dialog.setTitle("Nome Giocatore");
            dialog.setHeaderText("Inserisci il tuo nome per connetterti:");
            dialog.setContentText("Nome:");
            Stage owner = getCurrentStage();
            if(owner != null) dialog.initOwner(owner);

            Optional<String> result = dialog.showAndWait();
            result.ifPresentOrElse(name -> {
                if (name.trim().isEmpty()) {
                    showError("Nome non valido", "Il nome non può essere vuoto.");
                    askForNameAndConnect(); // Chiedi di nuovo
                } else {
                    // Salva il nome staticamente
                    staticPlayerName = name.trim();
                    labelStatus.setText("Connecting as " + staticPlayerName + "...");
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Connecting with initial listener THIS instance.");
                    setButtonsDisabled(true);

                    // Avvia la connessione usando l'istanza (che ora esiste sicuramente)
                    networkServiceInstance.connect("127.0.0.1", 12345, this);
                }
            }, () -> { // L'utente ha annullato
                labelStatus.setText("Connection cancelled.");
                setButtonsDisabled(true); // Rimane disabilitato
            });
        });
    }

    // --- Gestori Eventi UI ---

    @FXML
    private void handleCreaPartita() {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): handleCreaPartita CALLED");
        if (networkServiceInstance == null || !networkServiceInstance.isConnected()) {
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Not connected. Triggering connection process...");
            askForNameAndConnect();
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
        }
    }

    // --- Implementazione Metodi ServerListener ---

    @Override
    public void onConnected() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onConnected");
        Platform.runLater(() -> {
            labelStatus.setText("Connected. Waiting for server name request...");
            setButtonsDisabled(true); // Bottoni disabilitati finché nome non accettato
        });
    }

    @Override
    public void onNameRequested() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onNameRequested");
        if (staticPlayerName == null || staticPlayerName.trim().isEmpty()) {
            System.err.println(getCurrentTimestamp() + " - GUI ERROR: Server requested name, but staticPlayerName is invalid! Re-asking.");
            Platform.runLater(this::askForNameAndConnect); // Riprova a chiedere nome
            return;
        }
        Platform.runLater(() -> labelStatus.setText("Sending name '" + staticPlayerName + "'..."));
        networkServiceInstance.sendName(staticPlayerName);
    }

    @Override
    public void onNameAccepted() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onNameAccepted");
        Platform.runLater(() -> {
            labelStatus.setText("Logged in as " + staticPlayerName + ". Requesting game list...");
            // Ancora non abilito bottoni, aspetto onGamesList per sapere lo stato
            setButtonsDisabled(true);
        });
        networkServiceInstance.sendListRequest(); // Richiedi lista dopo autenticazione
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onDisconnected. Reason: " + reason);
        lastReturnReason = reason; // Salva motivo per futuro initialize

        Platform.runLater(() -> {
            labelStatus.setText("Disconnected: " + reason);
            setButtonsDisabled(true); // Tutto disabilitato
            if (flowPanePartite != null) flowPanePartite.getChildren().clear(); // Pulisci lista partite
            System.out.println(getCurrentTimestamp() + " - GUI: Disconnected state UI updated.");

            // Mostra alert solo per disconnessioni inattese
            boolean voluntaryDisconnect = "VOLUNTARY_LEAVE".equals(reason)
                    || "Disconnected by client".equals(reason)
                    || reason.contains("Rematch")
                    || reason.contains("Opponent decided");
            if (!voluntaryDisconnect && !reason.contains("Server is shutting down")) {
                showError("Disconnected", "Unexpected connection loss: " + reason);
            }
        });
    }

    /**
     * Aggiorna l'interfaccia mostrando la lista delle partite ricevute.
     * Gestisce anche lo stato dei bottoni principali (Crea, Refresh) in base
     * al fatto che l'utente sia connesso e/o già in attesa di una partita.
     */
    @Override
    public void onGamesList(List<NetworkService.GameInfo> games) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onGamesList with " + games.size() + " games.");
        Platform.runLater(() -> {
            if (flowPanePartite == null) {
                System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): FATAL - flowPanePartite is NULL in onGamesList!");
                return;
            }

            boolean amIWaiting = false;
            int myWaitingGameId = -1;

            // Popola la lista delle partite nella UI
            flowPanePartite.getChildren().clear();
            if (games.isEmpty()) {
                flowPanePartite.getChildren().add(new Label("No games available."));
            } else {
                for (NetworkService.GameInfo gameInfo : games) {
                    try {
                        FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/partita-item-view.fxml"));
                        Node gameItemNode = loader.load();
                        PartitaItemController controller = loader.getController();
                        controller.setData(gameInfo.id, gameInfo.creatorName, gameInfo.state);
                        flowPanePartite.getChildren().add(gameItemNode);

                        // Controlla se IO sono in attesa in QUESTA partita
                        if ("Waiting".equalsIgnoreCase(gameInfo.state) && staticPlayerName != null && staticPlayerName.equals(gameInfo.creatorName)) {
                            amIWaiting = true;
                            myWaitingGameId = gameInfo.id;
                        }

                    } catch (Exception e) {
                        System.err.println(getCurrentTimestamp() + " - Error loading/setting PartitaItem: "+e.getMessage());
                        e.printStackTrace(); // Dettagli errore caricamento item
                    }
                }
            }

            // Aggiorna lo stato dell'UI DOPO aver processato la lista
            boolean isConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());

            if(isConnected){
                // Se connesso, il bottone Refresh è sempre abilitato
                if(buttonRefresh != null) buttonRefresh.setDisable(false);

                if (amIWaiting) {
                    // Connesso E in attesa -> Disabilita "Crea"
                    if(buttonCreaPartita != null) buttonCreaPartita.setDisable(true);
                    labelStatus.setText("Waiting for opponent in your game " + myWaitingGameId + "...");
                } else {
                    // Connesso E NON in attesa -> Abilita "Crea"
                    if(buttonCreaPartita != null) buttonCreaPartita.setDisable(false);
                    // Aggiorna status solo se non è già impostato da un ritorno recente
                    if(labelStatus.getText() == null || !labelStatus.getText().contains("finished") && !labelStatus.getText().contains("Waiting for opponent")) {
                        labelStatus.setText("Logged in as " + staticPlayerName + ". Games available: " + games.size());
                    }
                }
            } else {
                // Non connesso -> Disabilita tutto
                if(buttonCreaPartita != null) buttonCreaPartita.setDisable(true);
                if(buttonRefresh != null) buttonRefresh.setDisable(true);
                labelStatus.setText("Disconnected.");
                disableJoinButtons(); // Assicura che anche i join siano disabilitati
            }
        });
    }

    @Override
    public void onGameCreated(int gameId) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onGameCreated for game " + gameId);
        Platform.runLater(() -> {
            labelStatus.setText("Game " + gameId + " created. Waiting for opponent...");
            setButtonsDisabled(true); // Disabilita temporaneamente
            disableJoinButtons();
            // onGamesList verrà chiamata dal server o da un refresh per mostrare la nuova partita
            // Potremmo forzare un refresh per aggiornare subito, ma potrebbe essere ridondante
            // handleRefresh();
        });
    }

    @Override
    public void onJoinRequestSent(int gameId) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onJoinRequestSent for game " + gameId);
        Platform.runLater(() -> {
            labelStatus.setText("Request sent for game " + gameId + ". Waiting for approval...");
            setButtonsDisabled(true); // Disabilita azioni mentre aspetta risposta
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
            String decision = "Reject"; // Default a rifiuto
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
            // Navigazione avviene su onGameStart
        });
    }

    @Override
    public void onJoinRejected(int gameId, String creatorName) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onJoinRejected for game " + gameId);
        Platform.runLater(() -> {
            showError("Join Rejected", "Request to join game " + gameId + " rejected by " + creatorName + ".");
            labelStatus.setText("Join request for game " + gameId + " rejected.");
            // Riabilita UI e aggiorna lista
            boolean isConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());
            setButtonsDisabled(!isConnected); // Abilita in base alla connessione
            handleRefresh(); // Aggiorna lista
        });
    }

    @Override
    public void onActionConfirmed(String message) {
        // Es. per RESP:REJECT_OK
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onActionConfirmed: " + message);
        Platform.runLater(() -> {
            if (message.startsWith("Rejected request from")) {
                labelStatus.setText(message + ". Waiting for new players...");
                // Riabilita UI e aggiorna lista
                boolean isConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());
                setButtonsDisabled(!isConnected); // Abilita in base alla connessione
                handleRefresh(); // Aggiorna lista dopo rifiuto
            }
            // Aggiungere altri casi se necessario
        });
    }

    @Override
    public void onGameStart(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onGameStart received for game " + gameId);
        lastReturnReason = null; // Pulisci prima di navigare
        Platform.runLater(() -> labelStatus.setText("Game " + gameId + " starting..."));
        isNavigatingToGame.set(true);
        cachedBoardDuringNavigation = null;
        cachedTurnDuringNavigation.set(false);
        navigateToGameScreen(gameId, symbol, opponentName);
    }

    // --- Metodo per tornare alla Home (chiamato da GameController) ---
    public void returnToHomePage(String statusMessage) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage CALLED with message: " + statusMessage);
        lastReturnReason = statusMessage; // Salva motivo per la *nuova* istanza

        Platform.runLater(() -> {
            try {
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage (runLater) START for reason: " + lastReturnReason);
                Stage stageToUse = getCurrentStage();
                if (stageToUse == null) throw new IOException("Stage is NULL, cannot return to home!");

                // Ricarica l'FXML della Home. Questo creerà una NUOVA istanza del controller.
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/home-page-view.fxml"));
                Parent homeRoot = loader.load();
                // La nuova istanza eseguirà il suo `initialize` leggendo `lastReturnReason`
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Loaded new home-page-view.fxml. New controller hash: "+loader.getController().hashCode());

                Scene scene = stageToUse.getScene();
                if (scene == null) { scene = new Scene(homeRoot); stageToUse.setScene(scene); }
                else { scene.setRoot(homeRoot); } // Imposta la nuova root

                stageToUse.setTitle("Tris - Lobby");
                stageToUse.show();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage (runLater) END. Stage is showing Home View.");

            } catch (Exception e) {
                System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! CRITICAL EXCEPTION during returnToHomePage !!!");
                e.printStackTrace();
                showError("Critical UI Error", "Failed to return to Home Page.\n" + e.getMessage());
                Platform.exit(); // Potrebbe essere necessario uscire se l'UI è compromessa
            }
        });
    }


    // --- Metodi Listener che non dovrebbero essere chiamati qui ---
    @Override public void onBoardUpdate(String[] board) { if (isNavigatingToGame.get()) cachedBoardDuringNavigation = board; else System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onBoardUpdate !!! Board: "+Arrays.toString(board)); }
    @Override public void onYourTurn() { if (isNavigatingToGame.get()) cachedTurnDuringNavigation.set(true); else System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onYourTurn !!!"); }
    @Override public void onGameOver(String result) { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onGameOver("+result+") !!!"); }
    @Override public void onOpponentLeft() { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onOpponentLeft !!!"); }
    @Override public void onRematchOffer() { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onRematchOffer !!!");}
    @Override public void onRematchAccepted(int gameId) { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onRematchAccepted("+gameId+") !!!");}
    @Override public void onRematchDeclined() { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onRematchDeclined !!!");}
    @Override public void onOpponentRematchDecision(boolean opponentAccepted) { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Unexpected onOpponentRematchDecision("+opponentAccepted+") !!!");}
    @Override public void onMessageReceived(String rawMessage) { System.err.println(getCurrentTimestamp() + " - HomePage: !!! Unexpected raw message: " + rawMessage + " !!!"); }


    // --- Gestione Errori ---
    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onError: " + message + " | isNavigating="+isNavigatingToGame.get());
        Platform.runLater(() -> {
            showError("Server Error", message);

            if (isNavigatingToGame.compareAndSet(true, false)) {
                // Errore durante il tentativo di andare alla schermata di gioco
                System.err.println(getCurrentTimestamp() + " - HomePage: Error during game navigation. Aborting.");
                if(labelStatus!=null) labelStatus.setText("Error starting game: " + message);
            } else {
                // Errore generico mentre si è in lobby
                if(labelStatus != null) labelStatus.setText("Error: " + message);
            }

            // Resetta stato UI
            boolean stillConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());
            setButtonsDisabled(!stillConnected);
            if(stillConnected && !message.contains("Server is shutting down")){
                handleRefresh(); // Tenta refresh
            }
        });
    }

    // --- Metodi Helper ---

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
                isNavigatingToGame.set(false); // Flag navigazione OFF dopo successo

            } catch (Exception e) {
                System.err.println(getCurrentTimestamp()+" - HomePage Nav: !!! EXCEPTION navigating to game screen !!!");
                e.printStackTrace();
                showError("Critical UI Error", "Cannot load game screen.\n" + e.getMessage());
                isNavigatingToGame.set(false); // Reset flag in caso di errore
                // Tenta ripristino lobby UI
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

    // Imposta stato disabilitato per i bottoni principali (Crea, Refresh)
    // Verranno poi affinati da onGamesList in base allo stato del giocatore
    private void setButtonsDisabled(boolean disabled) {
        Platform.runLater(() -> {
            if (buttonCreaPartita != null) buttonCreaPartita.setDisable(disabled);
            if (buttonRefresh != null) buttonRefresh.setDisable(disabled);
            if(disabled) disableJoinButtons(); // Se disabilito i principali, disabilito anche i join
        });
    }

    // Ottiene lo stage corrente in modo più robusto
    private Stage getCurrentStage() {
        try {
            if (currentStage != null && currentStage.isShowing()) return currentStage;

            // Tenta di ottenerlo da un componente visibile
            Node node = null;
            if(labelStatus != null && labelStatus.getScene() != null) node = labelStatus;
            else if (flowPanePartite != null && flowPanePartite.getScene() != null) node = flowPanePartite;
            else if (buttonCreaPartita != null && buttonCreaPartita.getScene() != null) node = buttonCreaPartita;

            if (node != null && node.getScene().getWindow() instanceof Stage) {
                currentStage = (Stage) node.getScene().getWindow();
                if(currentStage.isShowing()) return currentStage;
            }
            // Fallback estremo
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