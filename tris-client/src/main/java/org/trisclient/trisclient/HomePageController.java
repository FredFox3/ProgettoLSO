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
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize CHIAMATO. LastReturnReason: " + lastReturnReason);
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
                System.out.println(getCurrentTimestamp() + " - Inizializzazione: Riutilizzo istanza NetworkService connessa esistente.");
                prepareForReturn(returnReason);
            } else {
                System.out.println(getCurrentTimestamp() + " - Inizializzazione: NetworkService è null o disconnesso. Necessaria nuova connessione.");
                if (networkServiceInstance != null) {
                    System.out.println(getCurrentTimestamp()+" - Inizializzazione: Pulizia NetworkService disconnesso.");
                    networkServiceInstance.cleanupExecutor();
                }
                networkServiceInstance = null;

                labelStatus.setText("Inserisci il nome per connetterti.");
                setButtonsDisabled(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();

                askForNameAndConnect();
            }
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): initialize (runLater) END");
        });
    }

    private void prepareForReturn(String statusMessage) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): prepareForReturn CHIAMATO con stato: " + statusMessage);
        if (networkServiceInstance == null || !networkServiceInstance.isConnected()) {
            System.err.println(getCurrentTimestamp()+" - ERRORE in prepareForReturn: NetworkService non valido!");
            Platform.runLater(() -> {
                labelStatus.setText("Errore: Connessione persa.");
                setButtonsDisabled(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();
            });
            return;
        }

        isNavigatingToGame.set(false);
        cachedBoardDuringNavigation = null;
        cachedTurnDuringNavigation.set(false);

        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Impostazione listener a QUESTA istanza.");
        networkServiceInstance.setServerListener(this);

        final boolean rematchAccepted = (statusMessage != null && statusMessage.contains("Rivincita accettata"));

        Platform.runLater(() -> {
            String initialStatus;
            if (rematchAccepted) {
                initialStatus = "In attesa di un nuovo avversario...";
            } else if (statusMessage != null && !statusMessage.trim().isEmpty()) {
                String welcomeBack = (staticPlayerName != null ? " Bentornato, " + staticPlayerName + "!" : "");
                if (statusMessage.contains("Partita Terminata") || statusMessage.contains("Avversario disconnesso") ||
                        statusMessage.contains("Rivincita rifiutata") || statusMessage.contains("Avversario ha deciso") ||
                        statusMessage.contains("Nome già preso") || statusMessage.contains("Partita persa")) {
                    initialStatus = "Ultimo tentativo terminato." + welcomeBack;
                } else if ("ABBANDONO_VOLONTARIO".equals(statusMessage) || statusMessage.contains("LEFT_")) {
                    initialStatus = "Rientrato nella Lobby." + welcomeBack;
                } else if (statusMessage.startsWith("Disconnesso:")) {
                    initialStatus = "Disconnesso." + welcomeBack;
                }
                else {
                    initialStatus = statusMessage + welcomeBack;
                }
            } else {
                initialStatus = "Rientrato nella Lobby."+ (staticPlayerName != null ? " Bentornato, " + staticPlayerName + "!" : "");
            }
            labelStatus.setText(initialStatus);


            if (rematchAccepted) {
                System.out.println(getCurrentTimestamp()+" - HomePageController: Caso rivincita accettata in prepareForReturn.");
                setButtonsDisabled(true);
                if(buttonCreaPartita != null) buttonCreaPartita.setDisable(true);
                if(buttonRefresh != null) buttonRefresh.setDisable(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();
                Label waitingLabel = new Label("Sei in attesa di un avversario nella tua partita.");
                if (flowPanePartite != null) flowPanePartite.getChildren().add(waitingLabel);
                System.out.println(getCurrentTimestamp()+" - HomePageController: Salto richiesta LIST perché il giocatore è IN ATTESA.");

            } else {
                System.out.println(getCurrentTimestamp()+" - HomePageController: Caso ritorno normale in prepareForReturn.");
                setButtonsDisabled(true);
                if (flowPanePartite != null) flowPanePartite.getChildren().clear();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): prepareForReturn: Richiesta lista partite.");
                networkServiceInstance.sendListRequest();
            }
        });
    }

    private void askForNameAndConnect() {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): askForNameAndConnect CHIAMATO");

        Platform.runLater(() -> {
            if (networkServiceInstance == null) {
                System.out.println(getCurrentTimestamp()+" - askForNameAndConnect: Creazione nuova istanza NetworkService (era null).");
                networkServiceInstance = new NetworkService();
            } else if (networkServiceInstance.isConnected()){
                System.out.println(getCurrentTimestamp()+" - askForNameAndConnect: Già connesso, salto connessione. Richiesto solo nome.");
                showNameDialogAndSend(null);
                return;
            }

            labelStatus.setText("Inserisci il nome per connetterti:");
            setButtonsDisabled(true);
            if (flowPanePartite != null) flowPanePartite.getChildren().clear();

            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Connessione usando THIS come listener INIZIALE.");
            networkServiceInstance.connect("127.0.0.1", 12345, this);
        });
    }

    private void showNameDialogAndSend(String headerText) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): showNameDialogAndSend CHIAMATO. Header: " + headerText);
        Platform.runLater(() -> {
            if (networkServiceInstance == null) {
                System.err.println("showNameDialogAndSend: Impossibile mostrare dialogo, networkService è null.");
                askForNameAndConnect();
                return;
            }

            TextInputDialog dialog = new TextInputDialog(staticPlayerName != null ? staticPlayerName : "");
            dialog.setTitle("Inserisci Nome Giocatore");
            dialog.setHeaderText(headerText != null ? headerText : "Inserisci il tuo nome per iniziare:");
            dialog.setContentText("Nome:");
            Stage owner = getCurrentStage();
            if (owner != null) dialog.initOwner(owner);

            Optional<String> result = dialog.showAndWait();
            result.ifPresentOrElse(name -> {
                String trimmedName = name.trim();
                if (trimmedName.isEmpty()) {
                    showNameDialogAndSend("Attenzione, il nome non può essere vuoto. Inseriscine uno valido:");
                } else {
                    staticPlayerName = trimmedName;
                    labelStatus.setText("Invio nome '" + staticPlayerName + "'...");
                    System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Invio nome: " + staticPlayerName);
                    networkServiceInstance.sendName(staticPlayerName);
                }
            }, () -> {
                labelStatus.setText("Inserimento nome annullato. Disconnessione.");
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Utente ha annullato dialogo nome. Disconnessione.");
                if(networkServiceInstance != null && networkServiceInstance.isConnected()) {
                    networkServiceInstance.disconnect();
                }
                setButtonsDisabled(true);
            });
        });
    }

    @FXML
    private void handleCreaPartita() {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): handleCreaPartita CHIAMATO");
        if (networkServiceInstance == null || !networkServiceInstance.isConnected()) {
            System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Non connesso. Avvio processo di connessione...");
            askForNameAndConnect();
            return;
        }
        setButtonsDisabled(true);
        labelStatus.setText("Creazione partita...");
        disableJoinButtons();
        networkServiceInstance.sendCreateGame();
    }

    @FXML
    private void handleRefresh() {
        System.out.println(getCurrentTimestamp() + " - HomePageController (" + this.hashCode() + "): handleRefresh CHIAMATO");
        if (networkServiceInstance != null && networkServiceInstance.isConnected()) {
            setButtonsDisabled(true);
            disableJoinButtons();
            labelStatus.setText("Aggiornamento lista partite...");
            networkServiceInstance.sendListRequest();
        } else {
            System.err.println(getCurrentTimestamp() + " - HomePageController (" + this.hashCode() + "): Impossibile aggiornare, non connesso.");
            labelStatus.setText("Non connesso. Impossibile aggiornare.");
            setButtonsDisabled(true);
        }
    }

    @Override
    public void onConnected() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onConnected");
        Platform.runLater(() -> {
            labelStatus.setText("Connesso. In attesa richiesta nome dal server...");
            setButtonsDisabled(true);
        });
    }

    @Override
    public void onNameRequested() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onNameRequested");
        Platform.runLater(() -> {
            showNameDialogAndSend(null);
        });
    }


    @Override
    public void onNameRejected(String reason) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onNameRejected. Motivo: " + reason);
        Platform.runLater(() -> {
            String dialogHeader = "Attenzione, nome già in uso o non valido. Inseriscine un altro:";
            if (reason != null && reason.contains("Name already taken")) {
                dialogHeader = "Attenzione, nome già esistente. Inseriscine un altro:";
            }
            showNameDialogAndSend(dialogHeader);
        });
    }


    @Override
    public void onNameAccepted() {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onNameAccepted");
        Platform.runLater(() -> {
            labelStatus.setText("Accesso effettuato come " + staticPlayerName + ". Richiesta lista partite...");
            setButtonsDisabled(true);
        });
        networkServiceInstance.sendListRequest();
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onDisconnected. Motivo: " + reason);
        lastReturnReason = reason;

        Platform.runLater(() -> {
            String displayReason = reason;
            if ("VOLUNTARY_LEAVE".equals(reason) || "ABBANDONO_VOLONTARIO".equals(reason)) displayReason = "Abbandono volontario";
            else if ("Disconnected by client".equals(reason)) displayReason = "Disconnesso dal client";
            else if (reason.contains("Name entry cancelled")) displayReason = "Inserimento nome annullato";
            else if (reason.contains("Server is shutting down")) displayReason = "Il server si sta spegnendo";
            else if (reason.contains("Server closed connection")) displayReason = "Il server ha chiuso la connessione";
            else if (reason.startsWith("Connection error:")) displayReason = "Errore di connessione";
            else if (reason.startsWith("IO error:")) displayReason = "Errore I/O";

            labelStatus.setText("Disconnesso: " + displayReason);
            setButtonsDisabled(true);
            if (flowPanePartite != null) flowPanePartite.getChildren().clear();
            System.out.println(getCurrentTimestamp() + " - GUI: Stato UI disconnesso aggiornato.");

            boolean voluntaryDisconnect = "ABBANDONO_VOLONTARIO".equals(reason)
                    || "Disconnected by client".equals(reason)
                    || reason.contains("Rivincita")
                    || reason.contains("Avversario ha deciso")
                    || reason.contains("Name entry cancelled");
            if (!voluntaryDisconnect && !reason.contains("Server is shutting down") && !reason.contains("Server closed connection")) {
                showError("Disconnesso", "Perdita di connessione inaspettata: " + displayReason);
            }
        });
    }

    @Override
    public void onGamesList(List<NetworkService.GameInfo> games) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onGamesList con " + (games != null ? games.size() : 0) + " partite.");

        Platform.runLater(() -> {
            if (flowPanePartite == null) {
                System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): FATALE - flowPanePartite è NULL!");
                return;
            }

            boolean amIWaiting = false;
            int myWaitingGameId = -1;
            String myStatusIfWaiting = "";

            if (games != null && staticPlayerName != null) {
                for (NetworkService.GameInfo gameInfo : games) {
                    if (gameInfo != null && staticPlayerName.equals(gameInfo.creatorName) && "Waiting".equalsIgnoreCase(gameInfo.state)) {
                        amIWaiting = true;
                        myWaitingGameId = gameInfo.id;
                        myStatusIfWaiting = gameInfo.state;
                        System.out.println("onGamesList: Giocatore '" + staticPlayerName + "' è IN ATTESA (" + myStatusIfWaiting + ") nella partita " + gameInfo.id);
                        break;
                    }
                }
            }

            flowPanePartite.getChildren().clear();
            int displayedGamesCount = 0;

            if (games != null) {
                for (NetworkService.GameInfo gameInfo : games) {
                    if (gameInfo == null) continue;

                    boolean isMyWaitingGame = (amIWaiting && gameInfo.id == myWaitingGameId);
                    if (isMyWaitingGame) {
                        System.out.println("onGamesList: Salto visualizzazione della partita " + gameInfo.id + " perché appartiene al giocatore in attesa.");
                        continue;
                    }

                    try {
                        FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/partita-item-view.fxml"));
                        Node gameItemNode = loader.load();
                        PartitaItemController controller = loader.getController();

                        controller.setData(gameInfo.id, gameInfo.creatorName, gameInfo.state, staticPlayerName, amIWaiting);

                        flowPanePartite.getChildren().add(gameItemNode);
                        displayedGamesCount++;
                    } catch (Exception e) {
                        System.err.println(getCurrentTimestamp() + " - Errore caricamento/impostazione PartitaItem: "+e.getMessage());
                        e.printStackTrace();
                    }
                }
            }

            if (displayedGamesCount == 0 && !amIWaiting) {
                flowPanePartite.getChildren().add(new Label("Nessun'altra partita disponibile a cui unirsi."));
            } else if (displayedGamesCount == 0 && amIWaiting) {
                flowPanePartite.getChildren().add(new Label("Sei in attesa di un avversario..."));
            }

            boolean isConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());
            if(isConnected){
                if(buttonRefresh != null) buttonRefresh.setDisable(false);

                if (amIWaiting) {
                    if(buttonCreaPartita != null) buttonCreaPartita.setDisable(true);
                    labelStatus.setText("In attesa di avversario per partita " + (myWaitingGameId > 0 ? myWaitingGameId : "") + "...");
                } else {
                    if(buttonCreaPartita != null) buttonCreaPartita.setDisable(false);
                    int joinableGames = 0;
                    if(games != null){
                        for(NetworkService.GameInfo gi : games){
                            if(gi != null && "Waiting".equalsIgnoreCase(gi.state) && (staticPlayerName == null || !staticPlayerName.equals(gi.creatorName))){
                                joinableGames++;
                            }
                        }
                    }
                    if (labelStatus.getText() == null || labelStatus.getText().isEmpty() || labelStatus.getText().startsWith("Accesso come") || labelStatus.getText().startsWith("Logged in") || labelStatus.getText().startsWith("Aggiornamento")) {
                        labelStatus.setText("Accesso come " + staticPlayerName + ". Partite a cui unirsi: " + joinableGames);
                    }
                }
            } else {
                if(buttonCreaPartita != null) buttonCreaPartita.setDisable(true);
                if(buttonRefresh != null) buttonRefresh.setDisable(true);
                if(labelStatus!=null && !labelStatus.getText().startsWith("Disconnesso")) labelStatus.setText("Disconnesso.");
                disableJoinButtons();
            }
        });
    }

    @Override
    public void onGameCreated(int gameId) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onGameCreated per partita " + gameId);
        Platform.runLater(() -> {
            labelStatus.setText("Partita " + gameId + " creata. In attesa dell'avversario...");
            setButtonsDisabled(true);
            disableJoinButtons();
            handleRefresh();
        });
    }

    @Override
    public void onJoinRequestSent(int gameId) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onJoinRequestSent per partita " + gameId);
        Platform.runLater(() -> {
            labelStatus.setText("Richiesta inviata per la partita " + gameId + ". In attesa di approvazione...");
            setButtonsDisabled(true);
            disableJoinButtons();
        });
    }

    @Override
    public void onJoinRequestReceived(String requesterName) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onJoinRequestReceived da " + requesterName);
        Platform.runLater(() -> {
            if (networkServiceInstance == null || !networkServiceInstance.isConnected()) return;

            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Richiesta di Partecipazione");
            alert.setHeaderText("Il giocatore '" + requesterName + "' vuole unirsi alla tua partita.");
            alert.setContentText("Vuoi accettare?");
            ButtonType buttonTypeAccept = new ButtonType("Accetta");
            ButtonType buttonTypeReject = new ButtonType("Rifiuta");
            alert.getButtonTypes().setAll(buttonTypeAccept, buttonTypeReject);
            Stage owner = getCurrentStage();
            if (owner != null) alert.initOwner(owner);

            Optional<ButtonType> result = alert.showAndWait();
            String decision = "Reject";
            if (result.isPresent() && result.get() == buttonTypeAccept) {
                decision = "Accept";
            }
            String statusUpdate = (decision.equals("Accept") ? "Accettazione" : "Rifiuto") + " di " + requesterName + "...";
            labelStatus.setText(statusUpdate);
            if("Accept".equals(decision)) networkServiceInstance.sendAcceptRequest(requesterName);
            else networkServiceInstance.sendRejectRequest(requesterName);
        });
    }

    @Override
    public void onJoinAccepted(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onJoinAccepted per partita " + gameId);
        Platform.runLater(() -> {
            labelStatus.setText("Richiesta di partecipazione ACCETTATA! Avvio partita " + gameId + "...");
        });
    }

    @Override
    public void onJoinRejected(int gameId, String creatorName) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onJoinRejected per partita " + gameId);
        Platform.runLater(() -> {
            showError("Partecipazione Rifiutata", "Richiesta di unirsi alla partita " + gameId + " rifiutata da " + creatorName + ".");
            labelStatus.setText("Richiesta di partecipazione alla partita " + gameId + " rifiutata.");
            boolean isConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());
            setButtonsDisabled(!isConnected);
            handleRefresh();
        });
    }

    @Override
    public void onActionConfirmed(String message) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onActionConfirmed: " + message);
        Platform.runLater(() -> {
            String displayMessage = message;
            if (message.startsWith("Rejected request from")) {
                displayMessage = "Richiesta rifiutata da " + message.substring("Rejected request from".length()).trim();
                labelStatus.setText(displayMessage + ". Ancora in attesa...");
                setButtonsDisabled(true);
                handleRefresh();
                System.out.println("onActionConfirmed: Utente ha rifiutato giocatore, rimango in stato ATTESA.");
            } else if (message.startsWith("QUIT_OK")) {
                // labelStatus.setText("Azione confermata.");
            } else {
                System.out.println("Azione confermata non gestita: " + message);
                // labelStatus.setText("Azione confermata: " + message);
            }
        });
    }


    @Override
    public void onGameStart(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onGameStart ricevuto per partita " + gameId);
        lastReturnReason = null;
        Platform.runLater(() -> labelStatus.setText("Partita " + gameId + " in avvio..."));
        isNavigatingToGame.set(true);
        cachedBoardDuringNavigation = null;
        cachedTurnDuringNavigation.set(false);
        navigateToGameScreen(gameId, symbol, opponentName);
    }

    @Override public void onBoardUpdate(String[] board) { if (isNavigatingToGame.get()) cachedBoardDuringNavigation = board; else System.err.println(getCurrentTimestamp()+" - HomePage: !!! Inaspettato onBoardUpdate !!! Board: "+Arrays.toString(board)); }
    @Override public void onYourTurn() { if (isNavigatingToGame.get()) cachedTurnDuringNavigation.set(true); else System.err.println(getCurrentTimestamp()+" - HomePage: !!! Inaspettato onYourTurn !!!"); }
    @Override public void onGameOver(String result) { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Inaspettato onGameOver("+result+") !!!"); }
    @Override public void onOpponentLeft() { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Inaspettato onOpponentLeft !!!"); }
    @Override public void onRematchOffer() { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Inaspettato onRematchOffer !!!");}
    @Override public void onRematchAccepted(int gameId) { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Inaspettato onRematchAccepted("+gameId+") !!!");}
    @Override public void onRematchDeclined() { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Inaspettato onRematchDeclined !!!");}
    @Override public void onOpponentRematchDecision(boolean opponentAccepted) { System.err.println(getCurrentTimestamp()+" - HomePage: !!! Inaspettato onOpponentRematchDecision("+opponentAccepted+") !!!");}
    @Override public void onMessageReceived(String rawMessage) { System.err.println(getCurrentTimestamp() + " - HomePage: !!! Inaspettato messaggio raw: " + rawMessage + " !!!"); }


    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp() + " - HomePageController ("+this.hashCode()+"): GUI: onError: " + message + " | isNavigating="+isNavigatingToGame.get());
        Platform.runLater(() -> {
            showError("Errore del Server", message);

            if (isNavigatingToGame.compareAndSet(true, false)) {
                System.err.println(getCurrentTimestamp() + " - HomePage: Errore durante navigazione partita. Annullamento.");
                if(labelStatus!=null) labelStatus.setText("Errore avvio partita: " + message);
                boolean stillConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());
                setButtonsDisabled(!stillConnected);
                if(stillConnected) handleRefresh();
                return;
            }

            if(labelStatus != null) labelStatus.setText("Errore: " + message);

            boolean stillConnected = (networkServiceInstance != null && networkServiceInstance.isConnected());
            setButtonsDisabled(!stillConnected);
            if(stillConnected && !message.contains("Server is shutting down") && !message.contains("full") && !message.contains("Server si sta spegnendo")){
                handleRefresh();
            }
        });
    }

    public void returnToHomePage(String statusMessage) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage CHIAMATO con messaggio: " + statusMessage);
        lastReturnReason = statusMessage;

        Platform.runLater(() -> {
            try {
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage (runLater) START per motivo: " + lastReturnReason);
                Stage stageToUse = getCurrentStage();
                if (stageToUse == null) throw new IOException("Stage è NULL, impossibile tornare alla home!");

                FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/home-page-view.fxml"));
                Parent homeRoot = loader.load();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): Caricato nuovo home-page-view.fxml. Nuovo controller hash: "+loader.getController().hashCode());

                Scene scene = stageToUse.getScene();
                if (scene == null) { scene = new Scene(homeRoot); stageToUse.setScene(scene); }
                else { scene.setRoot(homeRoot); }

                stageToUse.setTitle("Tris - Lobby");
                stageToUse.show();
                System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): returnToHomePage (runLater) END. Stage mostra Home View.");

            } catch (Exception e) {
                System.err.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): !!! ECCEZIONE CRITICA durante returnToHomePage !!!");
                e.printStackTrace();
                showError("Errore Critico UI", "Impossibile tornare alla Home Page.\n" + e.getMessage());
                Platform.exit();
            }
        });
    }

    private void navigateToGameScreen(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp()+" - HomePageController ("+this.hashCode()+"): navigateToGameScreen CHIAMATO per partita " + gameId);
        Platform.runLater(() -> {
            final String[] boardToPass = cachedBoardDuringNavigation;
            final boolean turnToPass = cachedTurnDuringNavigation.getAndSet(false);
            cachedBoardDuringNavigation = null;

            try {
                Stage stageToUse = getCurrentStage();
                if (stageToUse == null) throw new IOException("Impossibile navigare: Stage non trovato!");

                FXMLLoader loader = new FXMLLoader(getClass().getResource("/org/trisclient/trisclient/game-view.fxml"));
                Parent gameRoot = loader.load();
                GameController gameController = loader.getController();
                System.out.println(getCurrentTimestamp()+" - HomePage Nav: game-view caricato. Controller: "+gameController.hashCode());

                gameController.setupGame(networkServiceInstance, gameId, symbol, opponentName, this::returnToHomePage, boardToPass, turnToPass);

                Scene scene = stageToUse.getScene();
                if (scene == null) { scene = new Scene(gameRoot); stageToUse.setScene(scene); }
                else { scene.setRoot(gameRoot); }

                stageToUse.setTitle("Tris - Partita " + gameId + " vs " + opponentName);
                stageToUse.show();
                System.out.println(getCurrentTimestamp()+" - HomePage Nav: Stage mostra Game View.");
                isNavigatingToGame.set(false);

            } catch (Exception e) {
                System.err.println(getCurrentTimestamp()+" - HomePage Nav: !!! ECCEZIONE navigando alla schermata di gioco !!!");
                e.printStackTrace();
                showError("Errore Critico UI", "Impossibile caricare la schermata di gioco.\n" + e.getMessage());
                isNavigatingToGame.set(false);
                setButtonsDisabled(!(networkServiceInstance != null && networkServiceInstance.isConnected()));
                handleRefresh();
                if(labelStatus != null) labelStatus.setText("Errore caricamento partita.");
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
        } catch (Exception e) { System.err.println("Errore mostrando alert ERRORE: "+e.getMessage()); }
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
            System.err.println("Eccezione ottenendo stage corrente: " + e.getMessage());
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