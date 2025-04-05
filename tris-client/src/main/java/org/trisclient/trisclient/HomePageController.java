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
import javafx.stage.Window; // Importa Window


import java.io.IOException;
import java.net.URL;
import java.util.Arrays; // Import per log cache
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean; // Per gestire stato navigazione


public class HomePageController implements Initializable, NetworkService.ServerListener {

    @FXML private Button buttonCreaPartita;
    @FXML private FlowPane flowPanePartite;
    @FXML private Label labelStatus;

    // Usa una variabile statica per NetworkService e PlayerName
    public static NetworkService networkServiceInstance; // Reso public static
    public static String staticPlayerName;             // Reso public static
    private Stage currentStage; // Riferimento allo stage corrente

    // Cache temporanea per messaggi ricevuti durante la transizione a GameController
    private volatile String[] cachedBoardDuringNavigation = null; // volatile per visibilità tra thread
    private final AtomicBoolean cachedTurnDuringNavigation = new AtomicBoolean(false); // atomic per thread-safety
    private final AtomicBoolean isNavigatingToGame = new AtomicBoolean(false); // Flag per indicare transizione

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }


    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize CALLED");
        // Resetta cache e flag navigazione all'inizializzazione
        cachedBoardDuringNavigation = null;
        cachedTurnDuringNavigation.set(false);
        isNavigatingToGame.set(false);

        Platform.runLater(() -> {
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize (runLater) START");
            // Tenta di ottenere lo Stage qui
            if (currentStage == null && flowPanePartite != null && flowPanePartite.getScene() != null) {
                currentStage = (Stage) flowPanePartite.getScene().getWindow();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Stage obtained in runLater: " + (currentStage != null));
            } else if(currentStage == null) {
                currentStage = getCurrentStageFallback();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Stage fallback result in runLater: " + (currentStage != null));
            }

            if (networkServiceInstance == null) {
                System.out.println(getCurrentTimestamp() + " - Initializing: NetworkService is null, creating and connecting...");
                networkServiceInstance = new NetworkService();
                labelStatus.setText("Connecting...");
                buttonCreaPartita.setDisable(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();
                askForNameAndConnect();
            } else {
                System.out.println(getCurrentTimestamp() + " - Initializing: NetworkService exists. Is connected: " + networkServiceInstance.isConnected());
                labelStatus.setText("Loading...");
                if (networkServiceInstance.isConnected()) {
                    System.out.println(getCurrentTimestamp() + " - Initializing: Preparing for return (connected).");
                    prepareForReturn("Returned to Lobby.");
                } else {
                    System.err.println(getCurrentTimestamp() + " - Initializing: NetworkService exists but is NOT connected. Resetting...");
                    networkServiceInstance = null; // Resetta per forzare nuova connessione
                    staticPlayerName = null;
                    labelStatus.setText("Connection lost. Please restart.");
                    buttonCreaPartita.setDisable(true);
                    if (flowPanePartite != null) flowPanePartite.getChildren().clear();
                    // askForNameAndConnect(); // Potresti tentare riconnessione
                }
            }
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize (runLater) END");
        });
    }

    // Prepara la UI quando si torna alla lobby (o dopo connessione iniziale)
    private void prepareForReturn(String statusMessage) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): prepareForReturn CALLED");
        if (networkServiceInstance == null) {
            System.err.println(getCurrentTimestamp()+" - ERROR in prepareForReturn: networkServiceInstance is NULL!");
            Platform.runLater(() -> {
                labelStatus.setText("Error: Connection lost.");
                buttonCreaPartita.setDisable(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();
            });
            return;
        }
        // Resetta flag navigazione e cache quando torniamo alla home
        isNavigatingToGame.set(false);
        cachedBoardDuringNavigation = null;
        cachedTurnDuringNavigation.set(false);

        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Setting listener to THIS instance.");
        networkServiceInstance.setServerListener(this);

        Platform.runLater(() -> {
            labelStatus.setText(statusMessage + (staticPlayerName != null ? " Welcome back, " + staticPlayerName + "!" : ""));
            buttonCreaPartita.setDisable(false);
            if (flowPanePartite != null) flowPanePartite.getChildren().clear();
        });

        if(networkServiceInstance.isConnected()){
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): prepareForReturn: Requesting game list.");
            networkServiceInstance.sendListRequest();
        } else {
            System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): prepareForReturn: Cannot request list, not connected.");
            Platform.runLater(() -> {
                labelStatus.setText("Error: Not connected.");
                buttonCreaPartita.setDisable(true);
            });
        }
    }


    private void askForNameAndConnect() {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): askForNameAndConnect CALLED");
        if (networkServiceInstance == null) {
            System.err.println(getCurrentTimestamp()+" - Cannot ask for name: networkServiceInstance is null!");
            Platform.runLater(()-> labelStatus.setText("Internal Error. Restart needed."));
            return;
        }

        Platform.runLater(() -> {
            TextInputDialog dialog = new TextInputDialog("Giocatore");
            dialog.setTitle("Nome Giocatore");
            dialog.setHeaderText("Inserisci il tuo nome:");
            dialog.setContentText("Nome:");

            Stage owner = getCurrentStage(); // Usa helper
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
                    networkServiceInstance.connect("127.0.0.1", 12345, this);
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
            System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Cannot create game, not connected.");
            showError("Errore", "Non connesso al server.");
            return;
        }
        buttonCreaPartita.setDisable(true);
        labelStatus.setText("Creating game...");
        disableJoinButtons();
        networkServiceInstance.sendCreateGame();
    }

    // --- Implementazione ServerListener ---

    @Override
    public void onConnected() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onConnected");
        Platform.runLater(() -> labelStatus.setText("Connected. Waiting for server name request..."));
    }

    @Override
    public void onNameRequested() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onNameRequested");
        if (staticPlayerName == null || staticPlayerName.trim().isEmpty()) {
            System.err.println(getCurrentTimestamp() + " - GUI: Server requested name, but staticPlayerName is invalid! Disconnecting.");
            showError("Errore Interno", "Nome giocatore non disponibile per l'invio. Disconnessione.");
            if(networkServiceInstance != null) networkServiceInstance.disconnect();
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

        boolean wasConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());
        // Non impostare a null, ma gestisci lo stato
        // if (networkServiceInstance != null) { // Non chiamare disconnect qui }
        staticPlayerName = null;
        isNavigatingToGame.set(false); // Resetta flag navigazione

        Platform.runLater(() -> {
            // Mostra errore solo se la disconnessione non era attesa (non "Disconnected by client")
            // e se eravamo effettivamente connessi.
            if (wasConnected && !"Disconnected by client".equals(reason)) {
                showError("Disconnesso", "Connessione persa: " + reason + "\nPotrebbe essere necessario riavviare il client.");
            } else if (!wasConnected) {
                System.out.println(getCurrentTimestamp() + " - GUI: onDisconnected event, but service was already null or not connected.");
            }

            labelStatus.setText("Disconnected: " + reason + ". Restart to connect.");
            buttonCreaPartita.setDisable(true);
            if (flowPanePartite != null) flowPanePartite.getChildren().clear();

            System.out.println(getCurrentTimestamp() + " - GUI: State reset due to disconnection. Restart client to reconnect.");
        });
    }


    @Override
    public void onMessageReceived(String rawMessage) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: Unhandled message: " + rawMessage);
        // Platform.runLater(() -> { labelStatus.setText("Received unknown message from server."); });
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
            } else if (networkServiceInstance != null && networkServiceInstance.isConnected()) {
                labelStatus.setText("Connected. Games available: " + games.size());
            } else {
                labelStatus.setText("Disconnected.");
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
                    } catch (IOException e) {
                        System.err.println(getCurrentTimestamp() + " - Error loading PartitaItem.fxml: "+e.getMessage());
                        e.printStackTrace();
                        flowPanePartite.getChildren().add(new Label("Errore caricamento item partita"));
                    } catch (Exception e) {
                        System.err.println(getCurrentTimestamp() + " - Generic error processing game item: "+e.getMessage());
                        e.printStackTrace();
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
        Platform.runLater(() -> labelStatus.setText("Game " + gameId + " started! Loading game screen..."));

        // --- CORREZIONE: Imposta il flag QUI, prima di chiamare navigate ---
        isNavigatingToGame.set(true);
        // --- Resetta cache LOCALE prima della navigazione, la lettura avverrà DOPO ---
        cachedBoardDuringNavigation = null;
        cachedTurnDuringNavigation.set(false);

        navigateToGameScreen(gameId, symbol, opponentName);
    }

    // Metodo per tornare alla Home Page (es. da GameController)
    public void returnToHomePage(String statusMessage) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage CALLED with message: " + statusMessage);
        Platform.runLater(() -> {
            try {
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage (runLater) START");
                Stage stageToUse = getCurrentStage(); // Usa helper

                if (stageToUse == null) {
                    System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Stage is still NULL in returnToHomePage! Cannot return.");
                    showError("Errore UI Critico", "Impossibile tornare alla Home Page (Stage non trovato).");
                    return;
                } else {
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Using Stage: "+ stageToUse.hashCode());
                }

                FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/home-page-view.fxml"));
                Parent homeRoot = loader.load();
                // HomePageController newHomeController = loader.getController(); // Non serve recuperare il nuovo controller qui
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

            } catch (IOException e) {
                System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! IOException during returnToHomePage !!!");
                e.printStackTrace();
                showError("Errore UI", "Impossibile caricare la Home Page.\n" + e.getMessage());
            } catch (Exception e) {
                System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! Generic EXCEPTION during returnToHomePage !!!");
                e.printStackTrace();
                showError("Errore UI Critico", "Errore imprevisto tornando alla Home Page.\n" + e.getMessage());
            }
        });
    }

    // --- Metodi Listener che potrebbero arrivare durante la navigazione ---

    @Override public void onBoardUpdate(String[] board) {
        if (isNavigatingToGame.get()) {
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): CACHING onBoardUpdate during navigation: " + Arrays.toString(board));
            cachedBoardDuringNavigation = board; // Sovrascrive eventuale cache precedente
        } else {
            System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! Received onBoardUpdate UNEXPECTEDLY (not navigating) !!! Board: "+Arrays.toString(board));
        }
    }

    @Override public void onYourTurn() {
        if (isNavigatingToGame.get()) {
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): CACHING onYourTurn during navigation.");
            cachedTurnDuringNavigation.set(true); // Imposta a true
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

            // Se l'errore arriva durante la navigazione, annullala
            if (isNavigatingToGame.compareAndSet(true, false)) {
                System.err.println(getCurrentTimestamp() + " - HomePageController: Error received during game navigation. Aborting navigation attempt.");
                buttonCreaPartita.setDisable(false);
                if (networkServiceInstance != null && networkServiceInstance.isConnected()) {
                    networkServiceInstance.sendListRequest();
                }
                labelStatus.setText("Error starting game: " + message);
                return;
            }

            boolean stillConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());
            if(stillConnected){
                // Riabilita Crea e richiede lista se l'errore suggerisce un fallimento di join/create
                if (buttonCreaPartita != null && (message.contains("full") || message.contains("not found") || message.contains("Invalid") || message.contains("Cannot join") || message.contains("not waiting"))) {
                    buttonCreaPartita.setDisable(false);
                    networkServiceInstance.sendListRequest();
                }
            } else {
                if(buttonCreaPartita != null) buttonCreaPartita.setDisable(true);
            }
        });
    }

    // --- Metodi Ausiliari ---

    // --- MODIFICATO ---
    private void navigateToGameScreen(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): navigateToGameScreen CALLED for game " + gameId);
        // isNavigatingToGame è già true

        // Esegui tutto nel Platform.runLater per garantire coerenza UI e lettura cache
        Platform.runLater(() -> {
            // --- NUOVO: Leggi i valori cachati QUI, DENTRO runLater ---
            final String[] boardToPass = cachedBoardDuringNavigation;
            final boolean turnToPass = cachedTurnDuringNavigation.getAndSet(false); // Leggi e resetta atomicamente
            // --- Pulisci cache locale ORA ---
            cachedBoardDuringNavigation = null;
            // -------------------------------------------------------------

            // Log dei valori che verranno passati
            if(boardToPass != null) System.out.println(getCurrentTimestamp()+" - HomePageController (in runLater): Preparing to pass cached board: " + Arrays.toString(boardToPass));
            if(turnToPass) System.out.println(getCurrentTimestamp()+" - HomePageController (in runLater): Preparing to pass cached turn=true.");

            try {
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): navigateToGameScreen (runLater) START");
                Stage stageToUse = getCurrentStage(); // Usa helper

                if (stageToUse == null) {
                    System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Stage is NULL in navigateToGameScreen! Cannot navigate.");
                    showError("Errore UI Critico", "Impossibile caricare la schermata di gioco (Stage non trovato).");
                    isNavigatingToGame.set(false); // Resetta flag
                    // Ripristina lobby
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
                // --- Passa i valori letti DENTRO runLater ---
                gameController.setupGame(
                        networkServiceInstance,
                        gameId,
                        symbol,
                        opponentName,
                        this::returnToHomePage, // Callback per tornare
                        boardToPass,            // Usa variabile letta qui
                        turnToPass              // Usa variabile letta qui
                );
                // --------------------------------------------------
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

                // La navigazione è completa, resetta il flag
                isNavigatingToGame.set(false);

            } catch (Exception e) {
                System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! EXCEPTION during navigation to game screen !!!");
                e.printStackTrace();
                showError("Errore UI Critico", "Impossibile caricare la schermata di gioco.\n" + e.getMessage());
                isNavigatingToGame.set(false); // Resetta flag anche in caso di errore
                // Ripristina lobby
                if(networkServiceInstance!=null && networkServiceInstance.isConnected()){
                    networkServiceInstance.sendListRequest();
                    if(buttonCreaPartita != null) buttonCreaPartita.setDisable(false);
                    if(labelStatus != null) labelStatus.setText("Error loading game.");
                }
            }
        }); // Fine Platform.runLater
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
        Stage owner = getCurrentStage(); // Usa helper
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

    // Helper per ottenere lo stage corrente in modo più sicuro
    private Stage getCurrentStage() {
        try {
            // Prova dallo stage memorizzato
            if (currentStage != null && currentStage.isShowing()) {
                return currentStage;
            }
            // Prova dal labelStatus
            if (labelStatus != null && labelStatus.getScene() != null && labelStatus.getScene().getWindow() instanceof Stage) {
                currentStage = (Stage) labelStatus.getScene().getWindow();
                return currentStage;
            }
            // Prova dal flowPanePartite
            if (flowPanePartite != null && flowPanePartite.getScene() != null && flowPanePartite.getScene().getWindow() instanceof Stage) {
                currentStage = (Stage) flowPanePartite.getScene().getWindow();
                return currentStage;
            }
            // Fallback: cerca la prima finestra visibile
            return getCurrentStageFallback();
        } catch (Exception e) {
            System.err.println("Exception trying to get current stage: " + e.getMessage());
            return null;
        }
    }

    // Fallback separato per chiarezza
    private Stage getCurrentStageFallback() {
        return Stage.getWindows().stream()
                .filter(Window::isShowing)
                .filter(w -> w instanceof Stage)
                .map(w -> (Stage)w)
                .findFirst()
                .orElse(null);
    }
}