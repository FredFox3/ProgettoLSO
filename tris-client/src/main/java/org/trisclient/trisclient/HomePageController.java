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
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


public class HomePageController implements Initializable, NetworkService.ServerListener {

    @FXML private Button buttonCreaPartita;
    @FXML private FlowPane flowPanePartite;
    @FXML private Label labelStatus;

    // Usa una variabile statica per NetworkService e PlayerName
    public static NetworkService networkServiceInstance; // Reso public static
    public static String staticPlayerName;             // Reso public static
    private Stage currentStage; // Riferimento allo stage corrente

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }


    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize CALLED");
        // Tentativo di ottenere lo stage subito, potrebbe essere null se chiamato troppo presto
        Platform.runLater(() -> {
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize (runLater) START");
            // Tenta di ottenere lo Stage qui, più probabile che la scena sia pronta
            if (currentStage == null && flowPanePartite != null && flowPanePartite.getScene() != null) {
                currentStage = (Stage) flowPanePartite.getScene().getWindow();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Stage obtained in runLater: " + (currentStage != null));
            } else if(currentStage == null) {
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Stage still null in runLater.");
            }

            if (networkServiceInstance == null) {
                System.out.println(getCurrentTimestamp() + " - Initializing: NetworkService is null, creating and connecting...");
                networkServiceInstance = new NetworkService();
                labelStatus.setText("Connecting...");
                buttonCreaPartita.setDisable(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear(); // Controllo null
                askForNameAndConnect();
            } else {
                // Se torniamo da una partita, networkServiceInstance esiste.
                // Assicurati che sia connesso prima di procedere.
                System.out.println(getCurrentTimestamp() + " - Initializing: NetworkService exists. Is connected: " + networkServiceInstance.isConnected());
                labelStatus.setText("Loading...");
                if (networkServiceInstance.isConnected()) {
                    System.out.println(getCurrentTimestamp() + " - Initializing: Preparing for return (connected).");
                    prepareForReturn("Returned to Lobby.");
                } else {
                    // Se networkService esiste ma non è connesso (es. errore precedente),
                    // forse dovremmo resettare e riconnettere? O mostrare errore?
                    System.err.println(getCurrentTimestamp() + " - Initializing: NetworkService exists but is NOT connected. Resetting...");
                    networkServiceInstance = null; // Resetta per forzare nuova connessione
                    staticPlayerName = null;
                    labelStatus.setText("Connection lost. Please restart."); // O tenta riconnessione
                    buttonCreaPartita.setDisable(true);
                    if (flowPanePartite != null) flowPanePartite.getChildren().clear();
                    // Potresti richiamare askForNameAndConnect() qui per tentare riconnessione automatica
                    // askForNameAndConnect();
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
            Platform.runLater(() -> { // Aggiorna UI nel thread corretto
                labelStatus.setText("Error: Connection lost.");
                buttonCreaPartita.setDisable(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();
            });
            return;
        }
        // Assicurati che il listener sia impostato su QUESTA istanza
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Setting listener to THIS instance.");
        networkServiceInstance.setServerListener(this);

        // Aggiorna UI nel thread JavaFX
        Platform.runLater(() -> {
            labelStatus.setText(statusMessage + " Welcome back, " + staticPlayerName + "!");
            buttonCreaPartita.setDisable(false);
            if (flowPanePartite != null) flowPanePartite.getChildren().clear();
        });

        // Richiedi lista partite
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
            Platform.runLater(()-> labelStatus.setText("Internal Error."));
            return;
        }

        // Esegui il dialogo nel Platform.runLater per sicurezza
        Platform.runLater(() -> {
            TextInputDialog dialog = new TextInputDialog("Giocatore");
            dialog.setTitle("Nome Giocatore");
            dialog.setHeaderText("Inserisci il tuo nome:");
            dialog.setContentText("Nome:");
            // Prova ad ottenere lo stage corrente per initOwner
            Stage owner = currentStage;
            if (owner == null && labelStatus != null && labelStatus.getScene() != null) {
                owner = (Stage) labelStatus.getScene().getWindow();
            }
            if(owner != null) dialog.initOwner(owner);

            Optional<String> result = dialog.showAndWait();
            result.ifPresentOrElse(name -> {
                if (name.trim().isEmpty()) {
                    showError("Nome non valido", "Il nome non può essere vuoto.");
                    askForNameAndConnect(); // Richiama (già dentro Platform.runLater)
                } else {
                    staticPlayerName = name.trim();
                    labelStatus.setText("Connecting as " + staticPlayerName + "...");
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Connecting with initial listener THIS instance.");
                    networkServiceInstance.connect("127.0.0.1", 12345, this); // Usa this come listener iniziale
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
            System.err.println(getCurrentTimestamp() + " - GUI: Server requested name, but staticPlayerName is invalid!");
            // Questo non dovrebbe accadere se askForNameAndConnect funziona
            showError("Errore Interno", "Nome giocatore non disponibile per l'invio.");
            if(networkServiceInstance != null) networkServiceInstance.disconnect();
            return;
        }
        Platform.runLater(() -> labelStatus.setText("Server requested name. Sending '" + staticPlayerName + "'..."));
        // L'invio avviene direttamente da sendMessage ora
        networkServiceInstance.sendName(staticPlayerName);
    }

    @Override
    public void onNameAccepted() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onNameAccepted");
        Platform.runLater(() -> {
            labelStatus.setText("Logged in as " + staticPlayerName + ". Requesting game list...");
            buttonCreaPartita.setDisable(false);
        });
        networkServiceInstance.sendListRequest(); // Richiedi lista
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onDisconnected. Reason: " + reason);

        // Controlla se il service era già null per evitare doppie azioni
        boolean wasConnected = (networkServiceInstance != null);
        networkServiceInstance = null; // Resetta sempre
        staticPlayerName = null;    // Resetta sempre

        Platform.runLater(() -> {
            if (wasConnected) { // Mostra errore/notifica solo se eravamo connessi
                showError("Disconnesso", "Connessione persa: " + reason + "\nSarà necessario riavviare il client per riconnettersi.");
            } else {
                System.out.println(getCurrentTimestamp() + " - GUI: onDisconnected event, but service was already null.");
            }

            labelStatus.setText("Disconnected: " + reason);
            buttonCreaPartita.setDisable(true);
            if (flowPanePartite != null) flowPanePartite.getChildren().clear();

            System.out.println(getCurrentTimestamp() + " - GUI: State reset due to disconnection. Restart client to reconnect.");
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
            if (staticPlayerName == null) { // Aggiunto controllo se il nome non è ancora stato definito
                labelStatus.setText("Connected. Games available: " + games.size());
            } else {
                labelStatus.setText("Logged in as " + staticPlayerName + ". Games available: " + games.size());
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
                        // Passa solo i dati necessari, il service è statico
                        controller.setData(gameInfo.id, gameInfo.creatorName);
                        flowPanePartite.getChildren().add(gameItemNode);
                    } catch (IOException e) {
                        System.err.println(getCurrentTimestamp() + " - Error loading PartitaItem.fxml: "+e.getMessage());
                        // e.printStackTrace(); // Decommenta per debug FXML
                    }
                }
            }
            // Abilita il bottone crea solo se loggato (name accepted)
            if (staticPlayerName != null) {
                buttonCreaPartita.setDisable(false);
            } else {
                buttonCreaPartita.setDisable(true); // Disabilita se non ancora loggato
            }
        });
    }

    @Override
    public void onGameCreated(int gameId) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onGameCreated for game " + gameId);
        Platform.runLater(() -> {
            labelStatus.setText("Game " + gameId + " created. Waiting for opponent...");
            buttonCreaPartita.setDisable(true);
            disableJoinButtons();
        });
    }

    @Override
    public void onJoinOk(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onJoinOk for game " + gameId);
        Platform.runLater(() -> labelStatus.setText("Joined game " + gameId + ". Waiting for start..."));
    }

    @Override
    public void onGameStart(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onGameStart received for game " + gameId);
        // IMPORTANTE: Assicurati che questa istanza sia ancora quella attiva prima di navigare
        // Questo controllo è implicito perché il listener dovrebbe essere questo controller.
        Platform.runLater(() -> labelStatus.setText("Game " + gameId + " started! Loading game screen..."));
        navigateToGameScreen(gameId, symbol, opponentName);
    }

    public void returnToHomePage(String statusMessage) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage CALLED with message: " + statusMessage);
        Platform.runLater(() -> {
            try {
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage (runLater) START");
                // Ottieni stage CORRENTE
                Stage stageToUse = currentStage;
                if (stageToUse == null && labelStatus != null && labelStatus.getScene() != null) { // Usa labelStatus come fallback
                    stageToUse = (Stage) labelStatus.getScene().getWindow();
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Got stage from labelStatus.");
                }
                // Fallback estremo: cerca la finestra visibile (rischioso)
                if (stageToUse == null) {
                    stageToUse = Stage.getWindows().stream()
                            .filter(Window::isShowing)
                            .filter(w -> w instanceof Stage)
                            .map(w -> (Stage)w)
                            .findFirst()
                            .orElse(null);
                    if(stageToUse != null) System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Got stage via Window list fallback.");
                }

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
                // La nuova istanza controllerà lo stato di networkServiceInstance in initialize

            } catch (Exception e) {
                System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! EXCEPTION during returnToHomePage !!!");
                e.printStackTrace();
                showError("Errore UI", "Impossibile tornare alla Home Page.\n" + e.getMessage());
            }
        });
    }

    // Metodi non usati direttamente in HomePage ma necessari per l'interfaccia
    @Override public void onBoardUpdate(String[] board) { System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! Received onBoardUpdate UNEXPECTEDLY !!!"); }
    @Override public void onYourTurn() { System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! Received onYourTurn UNEXPECTEDLY !!!"); }
    @Override public void onGameOver(String result) { System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! Received onGameOver UNEXPECTEDLY !!! Result: "+result); }
    @Override public void onOpponentLeft() { System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! Received onOpponentLeft UNEXPECTEDLY !!!"); }

    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onError: " + message);
        Platform.runLater(() -> {
            showError("Errore dal Server", message);
            if (labelStatus != null) labelStatus.setText("Error: " + message); // Controllo null
            // Riabilita 'Crea Partita' se l'errore non è fatale e siamo connessi
            if (networkServiceInstance != null && networkServiceInstance.isConnected()) {
                if (buttonCreaPartita != null && (message.contains("not found") || message.contains("Invalid") || message.contains("Cannot join"))) {
                    buttonCreaPartita.setDisable(false);
                    // Richiedi aggiornamento lista in caso di errori non fatali
                    networkServiceInstance.sendListRequest();
                }
            } else {
                // Se non siamo connessi, il bottone dovrebbe essere già disabilitato da onDisconnected
                if(buttonCreaPartita != null) buttonCreaPartita.setDisable(true);
            }
        });
    }

    // --- Metodi Ausiliari ---

    private void navigateToGameScreen(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): navigateToGameScreen CALLED for game " + gameId);
        Platform.runLater(() -> {
            try {
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): navigateToGameScreen (runLater) START");
                // Ottieni stage corrente
                Stage stageToUse = currentStage;
                if (stageToUse == null && labelStatus != null && labelStatus.getScene() != null) { // Usa labelStatus come fallback
                    stageToUse = (Stage) labelStatus.getScene().getWindow();
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Got stage from labelStatus for navigation.");
                }
                if (stageToUse == null) {
                    // Fallback estremo
                    stageToUse = Stage.getWindows().stream().filter(Window::isShowing).map(w -> (Stage)w).findFirst().orElse(null);
                    if(stageToUse != null) System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Got stage via Window list fallback for navigation.");
                }

                if (stageToUse == null) {
                    System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Stage is NULL in navigateToGameScreen! Cannot navigate.");
                    showError("Errore UI Critico", "Impossibile caricare la schermata di gioco (Stage non trovato).");
                    // Forse riportare allo stato iniziale?
                    if(networkServiceInstance!=null) networkServiceInstance.sendListRequest(); // Prova ad aggiornare la lista
                    buttonCreaPartita.setDisable(false); // Riabilita creazione
                    labelStatus.setText("Error loading game screen.");
                    return;
                } else {
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Using Stage: "+ stageToUse.hashCode());
                }

                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Loading game-view.fxml...");
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/game-view.fxml"));
                Parent gameRoot = loader.load();
                GameController gameController = loader.getController();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): game-view.fxml loaded. Controller instance: " + gameController + " Hash: "+gameController.hashCode());

                // Passa l'istanza statica e il callback per tornare
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Calling gameController.setupGame()...");
                // Passa il METODO di QUESTA istanza come callback
                gameController.setupGame(networkServiceInstance, gameId, symbol, opponentName, this::returnToHomePage);
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): gameController.setupGame() RETURNED.");


                Scene scene = stageToUse.getScene();
                if (scene == null) {
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Creating new Scene for game view.");
                    scene = new Scene(gameRoot);
                    stageToUse.setScene(scene);
                } else {
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Setting root on existing Scene for game view.");
                    scene.setRoot(gameRoot);
                }
                stageToUse.setTitle("Tris - Partita " + gameId);
                stageToUse.show();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): navigateToGameScreen (runLater) END. Stage is showing Game View.");

            } catch (Exception e) {
                System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! EXCEPTION during navigation to game screen !!!");
                e.printStackTrace();
                showError("Errore UI Critico", "Impossibile caricare la schermata di gioco.\n" + e.getMessage());
                // Prova a ripristinare lo stato della lobby
                if(networkServiceInstance!=null) networkServiceInstance.sendListRequest();
                if(buttonCreaPartita != null) buttonCreaPartita.setDisable(false);
                if(labelStatus != null) labelStatus.setText("Error loading game.");

            }
        });
    }


    private void showError(String title, String content) {
        // Assicurati che giri sul thread JavaFX
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showError(title, content));
            return;
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        // Tenta di ottenere lo stage per l'owner
        Stage owner = currentStage;
        if (owner == null && labelStatus != null && labelStatus.getScene() != null) { // Usa labelStatus come fallback
            owner = (Stage) labelStatus.getScene().getWindow();
        }
        // Fallback estremo
        if (owner == null) {
            owner = Stage.getWindows().stream().filter(Window::isShowing).map(w -> (Stage)w).findFirst().orElse(null);
        }
        if(owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait();
    }

    private void disableJoinButtons() {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): disableJoinButtons CALLED");
        if(flowPanePartite == null) return;
        // Usa Platform.runLater per modifiche UI
        Platform.runLater(() -> {
            for (Node node : flowPanePartite.getChildren()) {
                Button joinButton = (Button) node.lookup("#buttonUniscitiPartita");
                if (joinButton != null) {
                    joinButton.setDisable(true);
                }
            }
        });
    }
}