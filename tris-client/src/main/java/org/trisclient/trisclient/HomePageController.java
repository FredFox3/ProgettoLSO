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
    @FXML private FlowPane flowPanePartite;
    @FXML private Label labelStatus;

    public static NetworkService networkServiceInstance;
    public static String staticPlayerName;
    private Stage currentStage;

    private volatile String[] cachedBoardDuringNavigation = null;
    private final AtomicBoolean cachedTurnDuringNavigation = new AtomicBoolean(false);
    private final AtomicBoolean isNavigatingToGame = new AtomicBoolean(false);

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
        lastReturnReason = null; // Consuma

        Platform.runLater(() -> {
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize (runLater) START. Consumed ReturnReason: " + returnReason);
            if (currentStage == null && (flowPanePartite != null && flowPanePartite.getScene() != null)) {
                currentStage = (Stage) flowPanePartite.getScene().getWindow();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Stage obtained in runLater: " + (currentStage != null));
            } else if(currentStage == null) {
                currentStage = getCurrentStageFallback();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Stage fallback result in runLater: " + (currentStage != null));
            }

            if (networkServiceInstance == null) {
                // Primo avvio o dopo errore grave
                System.out.println(getCurrentTimestamp() + " - Initializing: NetworkService is null, creating and connecting...");
                labelStatus.setText("Connecting...");
                buttonCreaPartita.setDisable(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();
                askForNameAndConnect();
            } else {
                // Istanza NetworkService esistente
                boolean isConnected = networkServiceInstance.isConnected();
                System.out.println(getCurrentTimestamp() + " - Initializing: NetworkService exists. Is connected: " + isConnected);
                String statusMsgForPrepare = "Returned to Lobby."; // Default

                if (isConnected) {
                    // --- CASO NORMALE: Connesso (include ritorno da partita senza disconnessione) ---
                    System.out.println(getCurrentTimestamp() + " - Initializing: Preparing for return (connected). Reason: " + returnReason);
                    if (returnReason != null) { // Usa il motivo specifico se disponibile
                        statusMsgForPrepare = returnReason;
                    }
                    prepareForReturn(statusMsgForPrepare);

                } else {
                    // --- CASO DISCONNESSO ---
                    // Controlla se la disconnessione era dovuta alla chiusura della finestra precedente
                    boolean windowCloseDisconnect = "Disconnected by client".equals(returnReason);

                    if (windowCloseDisconnect) {
                        // Disconnessione VOLONTARIA (chiusura finestra). Chiedi nome per riconnettere.
                        System.out.println(getCurrentTimestamp() + " - Initializing: NetworkService exists but disconnected (Window Close). Resetting state for reconnection.");
                        networkServiceInstance.cleanupExecutor(); // Pulisci vecchio executor
                        networkServiceInstance.setServerListener(this); // Reimposta listener
                        labelStatus.setText("Disconnected. Enter name to reconnect.");
                        buttonCreaPartita.setDisable(true);
                        if (flowPanePartite != null) flowPanePartite.getChildren().clear();
                        // Chiedi nome per triggerare riconnessione
                        askForNameAndConnect();

                    } else {
                        // Disconnessione INATTESA (Errore server, rete, etc.). Reset completo.
                        System.err.println(getCurrentTimestamp() + " - Initializing: NetworkService exists but is NOT connected (Unexpected). Resetting...");
                        if(networkServiceInstance != null) networkServiceInstance.cleanupExecutor();
                        networkServiceInstance = null; // Resetta completamente l'istanza
                        staticPlayerName = null; // Perde il nome
                        labelStatus.setText("Connection lost. Please restart or enter name.");
                        buttonCreaPartita.setDisable(true);
                        if (flowPanePartite != null) flowPanePartite.getChildren().clear();
                        // Avvia recupero chiedendo nome (creerà nuova istanza service)
                        askForNameAndConnect();
                    }
                }
            }
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize (runLater) END");
        });
    }

    // --- Resto del file HomePageController.java (INVARIATO dalla versione precedente) ---
    // ... (assicurati che tutti gli altri metodi siano presenti) ...

    // Prepara la UI quando si torna alla lobby (o dopo connessione iniziale)
    private void prepareForReturn(String statusMessage) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): prepareForReturn CALLED with status: " + statusMessage);
        if (networkServiceInstance == null) {
            System.err.println(getCurrentTimestamp()+" - WARNING in prepareForReturn: networkServiceInstance is NULL! Initialize should handle.");
            return;
        }

        isNavigatingToGame.set(false);
        cachedBoardDuringNavigation = null;
        cachedTurnDuringNavigation.set(false);

        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Setting listener to THIS instance.");
        networkServiceInstance.setServerListener(this);

        Platform.runLater(() -> {
            String welcomeMsg = "";
            if (statusMessage != null && statusMessage.startsWith("Opponent left")) {
                welcomeMsg = "You won the last game!";
            } else if (statusMessage != null && statusMessage.startsWith("Game Over:")) {
                welcomeMsg = "Last game finished.";
            } else if (statusMessage != null && !"VOLUNTARY_LEAVE".equals(statusMessage) && !"Disconnected by client".equals(statusMessage)) {
                welcomeMsg = statusMessage;
            } else {
                welcomeMsg = "Returned to Lobby."; // Default per VOLUNTARY_LEAVE o null
            }

            labelStatus.setText(welcomeMsg + (staticPlayerName != null ? " Welcome back, " + staticPlayerName + "!" : ""));
            buttonCreaPartita.setDisable(false);
            if (flowPanePartite != null) flowPanePartite.getChildren().clear();
        });

        if(networkServiceInstance.isConnected()){
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): prepareForReturn: Requesting game list.");
            networkServiceInstance.sendListRequest();
        } else {
            // Se arriviamo qui non connessi, c'è un problema logico altrove
            System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): prepareForReturn: UNEXPECTEDLY not connected. Initialize should have handled.");
            labelStatus.setText("Error: Not connected.");
            buttonCreaPartita.setDisable(true);
        }
    }


    // Chiede nome e tenta connessione/riconnessione
    private void askForNameAndConnect() {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): askForNameAndConnect CALLED");

        Platform.runLater(() -> {
            if (networkServiceInstance == null || !networkServiceInstance.canAttemptConnect()) {
                System.out.println(getCurrentTimestamp()+" - Creating/Re-creating NetworkService in askForNameAndConnect");
                if(networkServiceInstance != null) networkServiceInstance.cleanupExecutor();
                networkServiceInstance = new NetworkService();
            }

            TextInputDialog dialog = new TextInputDialog(staticPlayerName != null ? staticPlayerName : "Giocatore");
            dialog.setTitle("Nome Giocatore");
            dialog.setHeaderText("Inserisci il tuo nome per connetterti:");
            dialog.setContentText("Nome:");

            Stage owner = getCurrentStage();
            if(owner != null) dialog.initOwner(owner);

            Optional<String> result = dialog.showAndWait();
            result.ifPresentOrElse(name -> {
                if (name.trim().isEmpty()) {
                    showError("Nome non valido", "Il nome non può essere vuoto.");
                    askForNameAndConnect();
                } else {
                    staticPlayerName = name.trim();
                    labelStatus.setText("Connecting as " + staticPlayerName + "...");
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Connecting with initial listener THIS instance.");

                    if (!networkServiceInstance.canAttemptConnect()) {
                        System.out.println(getCurrentTimestamp()+" - Re-creating NetworkService before connect (second check)");
                        networkServiceInstance.cleanupExecutor();
                        networkServiceInstance = new NetworkService();
                    }

                    networkServiceInstance.connect("127.0.0.1", 12345, this);
                    buttonCreaPartita.setDisable(true);
                }
            }, () -> {
                Platform.runLater(() -> {
                    labelStatus.setText("Connessione annullata.");
                    buttonCreaPartita.setDisable(true);
                });
            });
        });
    }

    @FXML
    private void handleCreaPartita() {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): handleCreaPartita CALLED");
        if (networkServiceInstance == null || !networkServiceInstance.isConnected()) {
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Not connected. Triggering connection process...");
            buttonCreaPartita.setDisable(true);
            labelStatus.setText("Connecting...");
            if(flowPanePartite != null) flowPanePartite.getChildren().clear();
            askForNameAndConnect();
            return;
        }

        buttonCreaPartita.setDisable(true);
        labelStatus.setText("Creating game...");
        disableJoinButtons();
        networkServiceInstance.sendCreateGame();
    }

    @Override
    public void onConnected() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onConnected");
        Platform.runLater(() -> labelStatus.setText("Connected. Waiting for server name request..."));
    }

    @Override
    public void onNameRequested() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onNameRequested");
        if (staticPlayerName == null || staticPlayerName.trim().isEmpty()) {
            System.err.println(getCurrentTimestamp() + " - GUI: Server requested name, but staticPlayerName is invalid! Re-asking.");
            askForNameAndConnect();
            return;
        }
        Platform.runLater(() -> labelStatus.setText("Server requested name. Sending '" + staticPlayerName + "'..."));
        networkServiceInstance.sendName(staticPlayerName);
    }

    @Override
    public void onNameAccepted() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onNameAccepted");
        Platform.runLater(() -> {
            labelStatus.setText("Logged in as " + staticPlayerName + ". Requesting game list...");
            buttonCreaPartita.setDisable(false);
        });
        networkServiceInstance.sendListRequest();
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onDisconnected. Reason: " + reason);
        lastReturnReason = reason;

        Platform.runLater(() -> {
            labelStatus.setText("Disconnected: " + reason);
            buttonCreaPartita.setDisable(true);
            if (flowPanePartite != null) flowPanePartite.getChildren().clear();
            System.out.println(getCurrentTimestamp() + " - GUI: Disconnected state updated immediately.");

            boolean voluntaryDisconnect = "VOLUNTARY_LEAVE".equals(reason) || "Disconnected by client".equals(reason);
            if (!voluntaryDisconnect) {
                showError("Disconnesso", "Connessione persa inaspettatamente: " + reason);
            }
        });
    }

    @Override
    public void onMessageReceived(String rawMessage) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: Unhandled message: " + rawMessage);
    }

    @Override
    public void onGamesList(List<NetworkService.GameInfo> games) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onGamesList with " + games.size() + " games.");
        Platform.runLater(() -> {
            if (flowPanePartite == null) {
                System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): flowPanePartite is NULL in onGamesList!");
                return;
            }
            if (staticPlayerName != null && networkServiceInstance != null && networkServiceInstance.isConnected()) {
                labelStatus.setText("Logged in as " + staticPlayerName + ". Games available: " + games.size());
            } else if (networkServiceInstance != null && networkServiceInstance.isConnected()){
                labelStatus.setText("Connected (pending name?). Games available: " + games.size());
            }

            flowPanePartite.getChildren().clear();

            if (games.isEmpty()) {
                flowPanePartite.getChildren().add(new Label("Nessuna partita disponibile al momento."));
            } else {
                for (NetworkService.GameInfo gameInfo : games) {
                    try {
                        FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/partita-item-view.fxml"));
                        Node gameItemNode = loader.load();
                        PartitaItemController controller = loader.getController();
                        controller.setData(gameInfo.id, gameInfo.creatorName);
                        flowPanePartite.getChildren().add(gameItemNode);
                    } catch (Exception e) {
                        System.err.println(getCurrentTimestamp() + " - Error loading/setting PartitaItem: "+e.getMessage());
                        e.printStackTrace();
                        flowPanePartite.getChildren().add(new Label("Errore item partita"));
                    }
                }
            }
            boolean canCreate = (staticPlayerName != null && networkServiceInstance != null && networkServiceInstance.isConnected());
            buttonCreaPartita.setDisable(!canCreate);
        });
    }

    @Override
    public void onGameCreated(int gameId) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onGameCreated for game " + gameId);
        Platform.runLater(() -> {
            labelStatus.setText("Game " + gameId + " created. Waiting for opponent...");
            disableJoinButtons();
            buttonCreaPartita.setDisable(true);
        });
    }

    @Override
    public void onJoinOk(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onJoinOk for game " + gameId + ". Symbol: " + symbol + ", Opponent: " + opponentName);
        Platform.runLater(() -> labelStatus.setText("Joined game " + gameId + " vs " + opponentName + ". You are " + symbol + ". Waiting for start..."));
        buttonCreaPartita.setDisable(true);
        disableJoinButtons();
    }

    @Override
    public void onGameStart(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onGameStart received for game " + gameId + ", Symbol: " + symbol + ", Opponent: " + opponentName);
        lastReturnReason = null;
        Platform.runLater(() -> labelStatus.setText("Game " + gameId + " started! Loading game screen..."));
        isNavigatingToGame.set(true);
        cachedBoardDuringNavigation = null;
        cachedTurnDuringNavigation.set(false);
        navigateToGameScreen(gameId, symbol, opponentName);
    }

    public void returnToHomePage(String statusMessage) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage CALLED with message: " + statusMessage);
        lastReturnReason = statusMessage;

        Platform.runLater(() -> {
            try {
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage (runLater) START");
                Stage stageToUse = getCurrentStage();
                if (stageToUse == null) {
                    System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Stage is still NULL in returnToHomePage! Cannot return.");
                    showError("Errore UI Critico", "Impossibile tornare alla Home Page (Stage non trovato).");
                    return;
                } else {
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Using Stage: "+ stageToUse.hashCode());
                }

                FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/home-page-view.fxml"));
                Parent homeRoot = loader.load();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Loaded new home-page-view.fxml. New controller hash: "+loader.getController().hashCode());

                Scene scene = stageToUse.getScene();
                if (scene == null) {
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Creating new Scene for home page.");
                    scene = new Scene(homeRoot);
                    stageToUse.setScene(scene);
                } else {
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Setting root on existing Scene.");
                    scene.setRoot(homeRoot);
                }

                stageToUse.setTitle("Tris - Lobby");
                stageToUse.show();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage (runLater) END. Stage is showing.");

            } catch (Exception e) {
                System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! EXCEPTION during returnToHomePage !!!");
                e.printStackTrace();
                showError("Errore UI Critico", "Errore tornando alla Home Page.\n" + e.getMessage());
            }
        });
    }

    @Override public void onBoardUpdate(String[] board) {
        if (isNavigatingToGame.get()) {
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): CACHING onBoardUpdate during navigation: " + Arrays.toString(board));
            cachedBoardDuringNavigation = board;
        } else {
            System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! Received onBoardUpdate UNEXPECTEDLY (not navigating) !!! Board: "+Arrays.toString(board));
        }
    }
    @Override public void onYourTurn() {
        if (isNavigatingToGame.get()) {
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): CACHING onYourTurn during navigation.");
            cachedTurnDuringNavigation.set(true);
        } else {
            System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! Received onYourTurn UNEXPECTEDLY (not navigating) !!!");
        }
    }
    @Override public void onGameOver(String result) { System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! Received onGameOver UNEXPECTEDLY !!! Result: "+result + " | isNavigating="+isNavigatingToGame.get()); }
    @Override public void onOpponentLeft() { System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! Received onOpponentLeft UNEXPECTEDLY !!!" + " | isNavigating="+isNavigatingToGame.get()); }
    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onError: " + message + " | isNavigating="+isNavigatingToGame.get());
        Platform.runLater(() -> {
            showError("Errore dal Server", message);
            if (labelStatus != null) labelStatus.setText("Error: " + message);

            if (isNavigatingToGame.compareAndSet(true, false)) {
                System.err.println(getCurrentTimestamp() + " - HomePageController: Error received during game navigation. Aborting.");
                if(buttonCreaPartita != null) buttonCreaPartita.setDisable(false);
                if (networkServiceInstance != null && networkServiceInstance.isConnected()) {
                    networkServiceInstance.sendListRequest();
                }
                if(labelStatus != null) labelStatus.setText("Error starting game: " + message);
                return;
            }

            boolean stillConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());
            if(stillConnected){
                if (buttonCreaPartita != null && (message.contains("full") || message.contains("not found") || message.contains("Invalid") || message.contains("Cannot join") || message.contains("not waiting"))) {
                    buttonCreaPartita.setDisable(false);
                    networkServiceInstance.sendListRequest();
                }
            } else {
                if(buttonCreaPartita != null) buttonCreaPartita.setDisable(true);
            }
        });
    }

    private void navigateToGameScreen(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): navigateToGameScreen CALLED for game " + gameId);

        Platform.runLater(() -> {
            final String[] boardToPass = cachedBoardDuringNavigation;
            final boolean turnToPass = cachedTurnDuringNavigation.getAndSet(false);
            cachedBoardDuringNavigation = null;

            if(boardToPass != null) System.out.println(getCurrentTimestamp()+" - HomePageController (in runLater): Preparing to pass cached board: " + Arrays.toString(boardToPass));
            if(turnToPass) System.out.println(getCurrentTimestamp()+" - HomePageController (in runLater): Preparing to pass cached turn=true.");

            try {
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): navigateToGameScreen (runLater) START");
                Stage stageToUse = getCurrentStage();

                if (stageToUse == null) {
                    System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Stage is NULL in navigateToGameScreen! Cannot navigate.");
                    showError("Errore UI Critico", "Impossibile caricare la schermata di gioco (Stage non trovato).");
                    isNavigatingToGame.set(false);
                    if(networkServiceInstance!=null && networkServiceInstance.isConnected()) {
                        networkServiceInstance.sendListRequest();
                        if(buttonCreaPartita!=null) buttonCreaPartita.setDisable(false);
                        if(labelStatus!=null) labelStatus.setText("Error loading game screen. Returned to lobby.");
                    } else {
                        if(labelStatus!=null) labelStatus.setText("Error loading game screen. Connection lost.");
                    }
                    return;
                } else {
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Using Stage: "+ stageToUse.hashCode());
                }

                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Loading game-view.fxml...");
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/game-view.fxml"));
                Parent gameRoot = loader.load();
                GameController gameController = loader.getController();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): game-view.fxml loaded. Controller instance: " + gameController + " Hash: "+gameController.hashCode());


                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Calling gameController.setupGame()...");
                gameController.setupGame(
                        networkServiceInstance,
                        gameId,
                        symbol,
                        opponentName,
                        this::returnToHomePage,
                        boardToPass,
                        turnToPass
                );
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): gameController.setupGame() RETURNED.");

                Scene scene = stageToUse.getScene();
                if (scene == null) {
                    scene = new Scene(gameRoot);
                    stageToUse.setScene(scene);
                } else {
                    scene.setRoot(gameRoot);
                }
                stageToUse.setTitle("Tris - Partita " + gameId + " vs " + opponentName);
                stageToUse.show();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): navigateToGameScreen (runLater) END. Stage is showing Game View.");

                isNavigatingToGame.set(false);

            } catch (Exception e) {
                System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! EXCEPTION during navigation to game screen !!!");
                e.printStackTrace();
                showError("Errore UI Critico", "Impossibile caricare la schermata di gioco.\n" + e.getMessage());
                isNavigatingToGame.set(false);
                if(networkServiceInstance!=null && networkServiceInstance.isConnected()){
                    networkServiceInstance.sendListRequest();
                    if(buttonCreaPartita != null) buttonCreaPartita.setDisable(false);
                    if(labelStatus != null) labelStatus.setText("Error loading game.");
                }
            }
        });
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
        Stage owner = getCurrentStage();
        if(owner != null) {
            alert.initOwner(owner);
        } else {
            System.err.println(getCurrentTimestamp()+" - HomePageController: Could not find owner stage for error alert.");
        }
        alert.showAndWait();
    }

    private void disableJoinButtons() {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): disableJoinButtons CALLED");
        if(flowPanePartite == null) return;
        Platform.runLater(() -> {
            for (Node node : flowPanePartite.getChildren()) {
                if (node != null) {
                    Button joinButton = (Button) node.lookup("#buttonUniscitiPartita");
                    if (joinButton != null) {
                        joinButton.setDisable(true);
                    }
                }
            }
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Finished disabling join buttons.");
        });
    }

    private Stage getCurrentStage() {
        try {
            if (currentStage != null && currentStage.isShowing()) {
                return currentStage;
            }
            if (labelStatus != null && labelStatus.getScene() != null && labelStatus.getScene().getWindow() instanceof Stage) {
                currentStage = (Stage) labelStatus.getScene().getWindow();
                return currentStage;
            }
            if (flowPanePartite != null && flowPanePartite.getScene() != null && flowPanePartite.getScene().getWindow() instanceof Stage) {
                currentStage = (Stage) flowPanePartite.getScene().getWindow();
                return currentStage;
            }
            return getCurrentStageFallback();
        } catch (Exception e) {
            System.err.println("Exception trying to get current stage: " + e.getMessage());
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
}