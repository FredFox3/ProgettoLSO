package org.trisclient.trisclient;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import java.io.IOException;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.fxml.Initializable;

public class GameController implements Initializable, NetworkService.ServerListener {

    @FXML private Label TextTurno;
    @FXML private GridPane gridPane;
    @FXML private Button buttonLeave;

    private NetworkService networkService;
    private int gameId;
    private char mySymbol;
    private String opponentName;
    private Consumer<String> returnToHomeCallback;

    private Button[][] buttons = new Button[3][3];
    private boolean myTurn = false;
    private final AtomicBoolean isSetupComplete = new AtomicBoolean(false);
    private final AtomicBoolean gameActive = new AtomicBoolean(false);
    private final AtomicBoolean gameFinishedWaitingRematch = new AtomicBoolean(false);
    private String lastGameResult = null;
    private final AtomicBoolean opponentDeclinedWhileWaiting = new AtomicBoolean(false);
    private volatile String[] cachedBoard = null;
    private final AtomicBoolean cachedTurn = new AtomicBoolean(false);
    private final AtomicBoolean isReturningHome = new AtomicBoolean(false);

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): FXML initialize CHIAMATO");
        this.lastGameResult = null; this.opponentDeclinedWhileWaiting.set(false);
        isSetupComplete.set(false); gameActive.set(false); gameFinishedWaitingRematch.set(false);
        cachedBoard = null; cachedTurn.set(false); myTurn = false;
        isReturningHome.set(false);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Button btn = new Button(" ");
                btn.setMinSize(95, 95); btn.setMaxSize(100,100);
                btn.setStyle("-fx-font-size: 36px; -fx-font-weight: bold;");
                final int row = i; final int col = j;
                btn.setOnAction(e -> handleCellClick(row, col));
                buttons[i][j] = btn;
                gridPane.add(btn, j, i);
            }
        }
        gridPane.setDisable(true); if(buttonLeave!=null) buttonLeave.setDisable(false);
        TextTurno.setText("Caricamento partita...");
    }

    public void setupGame(NetworkService serviceInstance, int gameId, char symbol, String opponentName,
                          Consumer<String> returnCallback,
                          String[] initialBoard, boolean initialTurn) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): setupGame CHIAMATO. GameID: " + gameId);
        this.lastGameResult = null; this.opponentDeclinedWhileWaiting.set(false);
        this.networkService = serviceInstance; this.gameId = gameId; this.mySymbol = symbol;
        this.opponentName = opponentName; this.returnToHomeCallback = returnCallback;
        this.isSetupComplete.set(false); this.gameActive.set(false);
        this.gameFinishedWaitingRematch.set(false); this.myTurn = false;
        this.isReturningHome.set(false);
        if (initialBoard != null) this.cachedBoard = initialBoard; else this.cachedBoard = null;
        this.cachedTurn.set(initialTurn);

        if (this.networkService == null) {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Errore critico in setupGame - NetworkService è null!");
            Platform.runLater(() -> {
                showError("Errore Critico", "Errore interno di rete. Impossibile avviare la partita.");
                if (returnToHomeCallback != null) returnToHomeCallback.accept("Errore di Rete Critico");
            });
            return;
        }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Impostazione listener");
        this.networkService.setServerListener(this);

        Platform.runLater(() -> {
            TextTurno.setText("Partita " + gameId + " vs " + opponentName + ". Sei " + mySymbol + ".");
            gridPane.setDisable(true); if (buttonLeave != null) buttonLeave.setDisable(false);
            isSetupComplete.set(true); gameActive.set(true);
            System.out.println(getCurrentTimestamp() + " - GC (runLater): Setup COMPLETATO. gameActive=true");
            processCachedMessages();
        });
    }

    private void processCachedMessages() {
        System.out.println(getCurrentTimestamp() + " - GC: Elaborazione messaggi in cache...");
        String[] boardToProcess = this.cachedBoard;
        if (boardToProcess != null) { this.cachedBoard = null; handleBoardUpdateInternal(boardToProcess); }
        if (this.cachedTurn.getAndSet(false)) { handleYourTurnInternal(); }
    }

    @Override
    public void onBoardUpdate(String[] boardCells) {
        if (!isSetupComplete.get()) { this.cachedBoard = boardCells; return; }
        if (!gameActive.get() && !gameFinishedWaitingRematch.get()) return;
        handleBoardUpdateInternal(boardCells);
    }

    private void handleBoardUpdateInternal(String[] boardCells) {
        if (boardCells.length != 9) return;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (buttons[i][j] == null) continue;
                String symbol = boardCells[i * 3 + j]; boolean isEmpty = "-".equals(symbol);
                buttons[i][j].setText(isEmpty ? " " : symbol);
            }
        }
        gridPane.setDisable(!gameActive.get() || !myTurn);
    }


    @Override
    public void onYourTurn() {
        System.out.println(getCurrentTimestamp()+" - GC: onYourTurn ricevuto. isSetupComplete="+isSetupComplete.get()+" | gameActive="+gameActive.get());
        if (!isSetupComplete.get()) { this.cachedTurn.set(true); return; }
        if (!gameActive.get()) {
            System.out.println(getCurrentTimestamp()+" - GC: Ignoro onYourTurn perché gameActive è false.");
            return;
        }
        handleYourTurnInternal();
    }

    private void handleYourTurnInternal() {
        if (!gameActive.get()) return;
        myTurn = true;
        Platform.runLater(() -> {
            TextTurno.setText("È il tuo turno! (" + mySymbol + ")");
            System.out.println(getCurrentTimestamp()+" - GC (UI): Abilitazione gridPane per il tuo turno.");
            gridPane.setDisable(false);
        });
    }

    private void handleCellClick(int row, int col) {
        if (buttons[row][col] == null) return;
        if (myTurn && gameActive.get() && buttons[row][col].getText().trim().isEmpty()) {
            System.out.println(getCurrentTimestamp()+" - GC: Gestione click su "+row+","+col);
            myTurn = false;
            buttons[row][col].setText(String.valueOf(mySymbol));
            gridPane.setDisable(true);
            TextTurno.setText("Invio mossa...");
            if(networkService != null) networkService.sendMove(row, col);
            else {
                System.err.println(getCurrentTimestamp()+" - GC: Errore di rete durante l'invio della mossa!");
                if (returnToHomeCallback != null) Platform.runLater(()->returnToHomeCallback.accept("Errore di Rete"));
            }
        } else {
            System.out.println(getCurrentTimestamp()+" - GC: Click ignorato su "+row+","+col + " (myTurn="+myTurn+", gameActive="+gameActive.get()+", buttonText='"+buttons[row][col].getText()+"')");
        }
    }

    @Override
    public void onOpponentLeft() {
        System.out.println(getCurrentTimestamp() + " - GC: onOpponentLeft ricevuto.");
        gameActive.set(false); gameFinishedWaitingRematch.set(false); myTurn = false;
        Platform.runLater(() -> {
            gridPane.setDisable(true);
            String message = "L'avversario ha abbandonato. Hai vinto!";
            TextTurno.setText(message);
            showInfo("Partita Terminata", message + "\nRitorno alla lobby.");
            if (returnToHomeCallback != null) returnToHomeCallback.accept("Avversario disconnesso - Hai Vinto!");
        });
    }

    @Override
    public void onNameRejected(String reason) {
        System.err.println(getCurrentTimestamp() + " - GameController ("+this.hashCode()+"): !!! INASPETTATO onNameRejected ricevuto: " + reason + " !!!");
        gameActive.set(false);
        gameFinishedWaitingRematch.set(false);
        myTurn = false;
        Platform.runLater(() -> {
            showError("Errore Critico di Stato", "Ricevuto rifiuto nome durante la partita: " + reason + "\nRitorno alla lobby.");
            if (gridPane != null) gridPane.setDisable(true);
            if (buttonLeave != null) buttonLeave.setDisable(true);
            if (TextTurno != null) TextTurno.setText("Errore inaspettato...");

            if (returnToHomeCallback != null) {
                returnToHomeCallback.accept("Rifiuto nome inaspettato: " + reason);
            } else {
                System.err.println("GameController: CRITICO - returnToHomeCallback è null dopo rifiuto nome inaspettato!");
                if(networkService != null) networkService.disconnect();
            }
        });
    }

    @Override
    public void onRematchOffer() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onRematchOffer ricevuto. Ultimo risultato: " + lastGameResult + ". Flag AttesaRivincita: " + gameFinishedWaitingRematch.get());
        if (!gameFinishedWaitingRematch.get()) {
            System.err.println(getCurrentTimestamp()+" - GC: ATTENZIONE - onRematchOffer ricevuto ma gameFinishedWaitingRematch è false!");
        }

        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Rivincita?");
            String headerText = "Partita Terminata!";
            String contentText = "Giocare ancora?";
            boolean isDraw = "DRAW".equalsIgnoreCase(this.lastGameResult);
            boolean isWinner = "WIN".equalsIgnoreCase(this.lastGameResult);

            if(isDraw) { headerText = "È un Pareggio!"; contentText = "Giocare ancora?"; }
            else if (isWinner) { headerText = "Hai Vinto!"; contentText = "Ospitare una nuova partita?"; }
            else { System.err.println(getCurrentTimestamp()+" - GC: ERRORE - Ricevuta offerta rivincita ma risultato era SCONFITTA? Non dovrebbe succedere."); alert.close(); if(returnToHomeCallback!=null) returnToHomeCallback.accept("Errore: Offerta rivincita inaspettata"); return; }

            alert.setHeaderText(headerText);
            alert.setContentText(contentText);

            ButtonType buttonTypeYes = new ButtonType("Sì, Gioca Ancora");
            ButtonType buttonTypeNo = new ButtonType("No, Torna alla Lobby");
            alert.getButtonTypes().setAll(buttonTypeYes, buttonTypeNo);
            try { Stage owner = getCurrentStage(); if(owner != null && owner.isShowing()) alert.initOwner(owner); } catch(Exception e) { System.err.println("GC: Errore impostazione owner per alert rivincita: "+e.getMessage()); }

            Optional<ButtonType> result = alert.showAndWait();

            if (opponentDeclinedWhileWaiting.getAndSet(false)) {
                System.out.println(getCurrentTimestamp()+" - GC: Avversario ha rifiutato (rilevato DOPO popup). Scelta ignorata. Ritorno home.");
                gameFinishedWaitingRematch.set(false);
                Platform.runLater(() -> {
                    showInfo("Rivincita Annullata", "L'avversario ha rifiutato la rivincita mentre stavi decidendo.\nRitorno alla lobby.");
                    if (returnToHomeCallback != null) {
                        returnToHomeCallback.accept("Avversario ha rifiutato (mentre decidevi)");
                    } else {
                        System.err.println("GC: returnToHomeCallback è null dopo che l'avversario ha rifiutato mentre si aspettava!");
                    }
                });
                return;
            }

            boolean myChoiceIsYes = result.isPresent() && result.get() == buttonTypeYes;

            if (myChoiceIsYes) {
                System.out.println(getCurrentTimestamp()+" - GC: Utente ha scelto SÌ per rivincita. Invio REMATCH YES.");
                if(networkService != null){
                    TextTurno.setText("Invio scelta... Attesa avversario/server...");
                    networkService.sendRematchChoice(true);
                    gameFinishedWaitingRematch.set(true);
                    System.out.println(getCurrentTimestamp()+" - GC: Inviato REMATCH YES, flag waitingRematch="+gameFinishedWaitingRematch.get());
                } else { gameFinishedWaitingRematch.set(false); TextTurno.setText("Errore di Rete!"); showError("Errore di Rete", "Impossibile inviare la scelta per la rivincita."); if (returnToHomeCallback != null) Platform.runLater(()->returnToHomeCallback.accept("Errore di Rete")); }
            } else {
                System.out.println(getCurrentTimestamp()+" - GC: Utente ha scelto NO per rivincita. Invio REMATCH NO.");
                if(networkService != null){
                    TextTurno.setText("Rifiuto rivincita...");
                    networkService.sendRematchChoice(false);
                    System.out.println(getCurrentTimestamp()+" - GC: Inviato REMATCH NO, flag waitingRematch="+gameFinishedWaitingRematch.get());
                } else { gameFinishedWaitingRematch.set(false); TextTurno.setText("Errore di Rete!"); showError("Errore di Rete", "Impossibile inviare la scelta per la rivincita."); if (returnToHomeCallback != null) Platform.runLater(()->returnToHomeCallback.accept("Errore di Rete")); }
            }
        });
    }

    @Override
    public void onRematchAccepted(int receivedGameId) {
        System.out.println(getCurrentTimestamp() + " - GC: onRematchAccepted ricevuto (Partita " + receivedGameId + ")");
        gameFinishedWaitingRematch.set(false); gameActive.set(false); myTurn=false;
        Platform.runLater(() -> {
            showInfo("Rivincita Accettata (Host)", "Rivincita accettata! Ospito la partita " + receivedGameId + ".\nRitorno alla lobby in attesa.");
            if (returnToHomeCallback != null) returnToHomeCallback.accept("Rivincita accettata, in attesa");
            else System.err.println("GC: Callback null su onRematchAccepted!");
        });
    }

    @Override
    public void onGameOver(String result) {
        System.out.println(getCurrentTimestamp() + " - GC: onGameOver - Risultato: " + result + " | gameActive attuale=" + gameActive.get());

        if (!gameActive.compareAndSet(true, false)) {
            System.out.println(getCurrentTimestamp()+" - GC: Ignoro onGameOver ridondante ("+result+"). Partita già inattiva.");
            return;
        }

        this.lastGameResult = result;
        gameFinishedWaitingRematch.set(true);
        myTurn = false;
        this.opponentDeclinedWhileWaiting.set(false);

        final String finalResult = result;

        Platform.runLater(() -> {
            gridPane.setDisable(true);

            String message = "Partita Terminata! ";
            if ("WIN".equalsIgnoreCase(finalResult)) { message += "Hai Vinto!"; message += "\nIn attesa di opzioni per la rivincita..."; }
            else if ("DRAW".equalsIgnoreCase(finalResult)) { message += "È un Pareggio!"; message += "\nIn attesa di opzioni per la rivincita..."; }
            else if ("LOSE".equalsIgnoreCase(finalResult)){ message += "Hai Perso."; message += "\nRitorno alla lobby..."; }
            else { message += "Risultato sconosciuto (" + finalResult + ")"; message += "\nRitorno alla lobby..."; }

            TextTurno.setText(message);
            System.out.println(getCurrentTimestamp()+" - GC (UI): UI Game Over aggiornata. Risultato: "+finalResult+". WaitingRematch="+gameFinishedWaitingRematch.get());
            if(buttonLeave!=null) buttonLeave.setDisable(false);
        });
    }

    @Override
    public void onRematchDeclined() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onRematchDeclined received. LastResult="+lastGameResult+" | WaitingFlag before:"+gameFinishedWaitingRematch.get());

        if (isReturningHome.get()) {
            System.out.println(getCurrentTimestamp()+" - GC: onRematchDeclined ignorato, ritorno già in corso.");
            return;
        }

        boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false);
        gameActive.set(false);
        myTurn = false;
        opponentDeclinedWhileWaiting.set(false);

        boolean showPopup = false;
        String alertTitle = "Info Rivincita";
        String alertContent = "Ritorno alla lobby.";
        String returnReason = "Rivincita rifiutata";

        if ("LOSE".equalsIgnoreCase(lastGameResult)) {
            alertTitle = "Partita Terminata";
            alertContent = "Partita persa.\nRitorno alla lobby.";
            returnReason = "Partita persa";
            showPopup = true;
        } else if (wasWaiting && "DRAW".equalsIgnoreCase(lastGameResult)) {
            alertTitle = "Rivincita Annullata";
            alertContent = "Ritorno alla lobby.";
            returnReason = "Avversario ha rifiutato prima";
            showPopup = true;
        }

        if (isReturningHome.getAndSet(true)) {
            System.out.println(getCurrentTimestamp() + " - GC: Doppia chiamata a ritorno home in onRematchDeclined, ignorando.");
            return;
        }

        final String finalAlertContent = alertContent;
        final String finalReturnReason = returnReason;
        final String finalAlertTitle = alertTitle;
        final boolean finalShowPopup = showPopup;

        Platform.runLater(() -> {
            if (finalShowPopup) {
                showInfo(finalAlertTitle, finalAlertContent);
            } else {
                System.out.println(getCurrentTimestamp() + " - GC: Ritorno silenzioso alla lobby dopo aver rifiutato la rivincita.");
            }

            if (returnToHomeCallback != null) {
                returnToHomeCallback.accept(finalReturnReason);
            } else {
                System.err.println("GC: returnToHomeCallback null after onRematchDeclined!");
                showError("Errore Critico", "Impossibile tornare alla lobby. Callback mancante.");
            }
        });
    }

    @Override
    public void onOpponentRematchDecision(boolean opponentAccepted) {
        final String decision = opponentAccepted ? "accettato" : "rifiutato";
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onOpponentRematchDecision ricevuto: "+decision+" *** | Flag attesa attuale: "+gameFinishedWaitingRematch.get() + " | isDraw: "+("DRAW".equalsIgnoreCase(lastGameResult)) + " | LastResult: " + lastGameResult);

        if (isReturningHome.get()) {
            System.out.println(getCurrentTimestamp()+" - GC: Ignoro onOpponentRematchDecision perché ritorno già in corso.");
            return;
        }
        if (!gameActive.get() && !gameFinishedWaitingRematch.get()){
            System.out.println(getCurrentTimestamp()+" - GC: Ignoro onOpponentRematchDecision, stato non attivo/in attesa.");
            return;
        }


        if (!opponentAccepted) {
            if ("LOSE".equalsIgnoreCase(lastGameResult)) {
                System.err.println(getCurrentTimestamp()+" - GC: ERRORE LOGICO - onOpponentRematchDecision(false) chiamato per un perdente?");
                gameFinishedWaitingRematch.set(false); gameActive.set(false); myTurn = false; isReturningHome.set(true);
                return;
            }

            System.out.println(getCurrentTimestamp()+" - GC: Avversario ha RIFIUTATO la rivincita. Ritorno home.");

            if (opponentDeclinedWhileWaiting.get()) {
                System.out.println(getCurrentTimestamp()+" - GC: Rifiuto avversario rilevato mentre popup era aperto (o subito dopo). Flag gestito da onRematchOffer.");
                gameFinishedWaitingRematch.set(false);
                return;
            }

            if (isReturningHome.getAndSet(true)) return;
            boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false);
            gameActive.set(false);
            myTurn = false;

            Platform.runLater(() -> {
                showInfo("Rivincita Rifiutata", "L'avversario ha rifiutato la rivincita.\nRitorno alla lobby.");
                if (returnToHomeCallback != null) {
                    returnToHomeCallback.accept("Avversario ha rifiutato la rivincita");
                } else {
                    System.err.println("GC: Callback null on Opponent DECLINED decision!");
                    showError("Errore Critico", "Impossibile tornare alla lobby dopo il rifiuto dell'avversario. Callback mancante.");
                    if(networkService != null) networkService.disconnect();
                }
            });

        } else {
            if ("DRAW".equalsIgnoreCase(lastGameResult)) {
                System.out.println(getCurrentTimestamp()+" - GC: Avversario ha ACCETTATO (PAREGGIO). Aspetto GAME_START.");
            } else {
                System.err.println(getCurrentTimestamp()+" - GC: Ricevuto onOpponentRematchDecision(true) ma non era pareggio? Stato: " + lastGameResult +". Ignoro.");
            }
        }
    }


    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp() + " - GC: onError ricevuto: " + message);

        if (message != null && message.contains("Unknown command or invalid state (1) for command: REMATCH NO")) {
            System.out.println(getCurrentTimestamp()+" - GC: IGNORO errore 'invalid state' atteso dopo invio REMATCH NO (perché avversario già rifiutato/uscito).");
            return;
        }

        if (!gameActive.get() && !gameFinishedWaitingRematch.get()) {
            System.out.println(getCurrentTimestamp()+" - GC: Errore ricevuto ma partita non attiva/in attesa. Ignoro.");
            return;
        }

        Platform.runLater(()-> {
            if (message.contains("Not your turn") || message.contains("Invalid move")) {
                String userMessage = message.replace("ERROR:", "").trim();
                if (userMessage.contains("Not your turn")) userMessage = "Non è il tuo turno.";
                else if (userMessage.contains("Invalid move")) userMessage = "Mossa non valida.";
                TextTurno.setText(userMessage + " Riprova.");
                if (myTurn && gameActive.get()) {
                    gridPane.setDisable(false);
                } else { gridPane.setDisable(true); }
            } else {
                showError("Errore del Server", message);
                TextTurno.setText("Errore: " + message);
                gridPane.setDisable(true);
                gameActive.set(false); gameFinishedWaitingRematch.set(false); myTurn = false;
                if (returnToHomeCallback != null) returnToHomeCallback.accept("Errore del Server");
            }
        });
    }

    @FXML
    private void handleLeaveGame() {
        System.out.println(getCurrentTimestamp() + " - GC: Cliccato Abbandona Partita.");
        gameActive.set(false); gameFinishedWaitingRematch.set(false); opponentDeclinedWhileWaiting.set(false);
        myTurn = false;
        Platform.runLater(() -> {
            gridPane.setDisable(true); if(buttonLeave != null) buttonLeave.setDisable(true);
            TextTurno.setText("Abbandono...");
        });
        final String callbackMsg = "ABBANDONO_VOLONTARIO";
        final String alertContent = "Hai abbandonato la partita.\nRitorno alla lobby.";
        if (networkService != null && networkService.isConnected()) networkService.sendQuit();
        if (returnToHomeCallback != null) Platform.runLater(() -> { showInfo("Partita Abbandonata", alertContent); returnToHomeCallback.accept(callbackMsg); });
        else showError("Errore", "Impossibile tornare alla home!");
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp() + " - GC: onDisconnected - Motivo: " + reason);
        gameActive.set(false); gameFinishedWaitingRematch.set(false); opponentDeclinedWhileWaiting.set(false); myTurn = false;
        final String finalReason = reason;
        Platform.runLater(() -> {
            gridPane.setDisable(true); if(buttonLeave != null) buttonLeave.setDisable(true);
            TextTurno.setText("Disconnesso");
            String userFriendlyReason = finalReason;
            if ("Disconnected by client".equals(finalReason)) userFriendlyReason = "Disconnessione richiesta dal client";
            else if ("Server closed connection".equals(finalReason)) userFriendlyReason = "Il server ha chiuso la connessione";
            else if (finalReason.startsWith("Connection error:")) userFriendlyReason = "Errore di connessione: " + finalReason.substring("Connection error:".length()).trim();
            else if (finalReason.startsWith("IO error:")) userFriendlyReason = "Errore I/O: " + finalReason.substring("IO error:".length()).trim();

            if (!"Disconnected by client".equals(finalReason) && !"ABBANDONO_VOLONTARIO".equals(finalReason))
                showInfo("Disconnesso", "Connessione persa: " + userFriendlyReason + "\nRitorno alla lobby.");
            if (returnToHomeCallback != null) returnToHomeCallback.accept("Disconnesso: " + finalReason);
            else System.err.println("GC: Callback null sulla disconnessione!");
        });
    }

    @Override public void onConnected() { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onConnected"); }
    @Override public void onNameRequested() { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onNameRequested"); }
    @Override public void onNameAccepted() { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onNameAccepted"); }
    @Override public void onGamesList(List<NetworkService.GameInfo> g) { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onGamesList"); }
    @Override public void onGameCreated(int gid) { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onGameCreated"); }
    @Override public void onJoinRequestSent(int gid) { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onJoinRequestSent"); }
    @Override public void onJoinRequestReceived(String n) { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onJoinRequestReceived"); }
    @Override public void onJoinAccepted(int gid, char s, String on) { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onJoinAccepted"); }
    @Override public void onJoinRejected(int gid, String cn) { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onJoinRejected"); }
    @Override public void onActionConfirmed(String m) { if (m != null && m.startsWith("QUIT_OK")) { System.out.println(getCurrentTimestamp()+" - GC: Ricevuto ActionConfirmed (probabilmente QUIT_OK): "+m); } else { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onActionConfirmed: "+m); } }
    @Override public void onMessageReceived(String rm) { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato messaggio raw: "+rm); }

    @Override
    public void onGameStart(int recGameId, char recSymbol, String recOpponentName) {
        System.out.println(getCurrentTimestamp()+" - GC: onGameStart ricevuto per partita "+recGameId);
        if(this.gameId != recGameId){ System.err.println("GC: ERRORE GameStart per ID errato!"); return; }

        boolean isDrawRematch = gameFinishedWaitingRematch.compareAndSet(true,false);
        gameActive.set(true);
        this.mySymbol = recSymbol; this.opponentName = recOpponentName;
        this.myTurn = false;

        if (isDrawRematch) {
            System.out.println(getCurrentTimestamp() + " - GC: GameStart -> RIVINCITA PAREGGIO INIZIA!");
            Platform.runLater(() -> {
                TextTurno.setText("Rivincita! vs " + this.opponentName + " (Sei " + this.mySymbol + ")");
                for (Button[] row : buttons) for (Button btn : row) if(btn!=null) btn.setText(" ");
                if (buttonLeave != null) buttonLeave.setDisable(false);
                System.out.println(getCurrentTimestamp()+" - GC (UI Rivincita): Griglia pulita. Stato griglia dipende dal prossimo YOUR_TURN.");
            });
        } else {
            System.out.println(getCurrentTimestamp() + " - GC: GameStart -> Inizio partita NORMALE.");
            if (isSetupComplete.get()) {
                System.out.println(getCurrentTimestamp()+" - GC: Elaborazione messaggi cache immediatamente dopo GameStart normale (Setup completo).");
                processCachedMessages();
            } else {
                System.out.println(getCurrentTimestamp()+" - GC: Rimando elaborazione messaggi cache, setup non ancora completo.");
            }
        }
    }

    private void showInfo(String title, String content) { showAlert(Alert.AlertType.INFORMATION, title, content); }
    private void showError(String title, String content) { showAlert(Alert.AlertType.ERROR, title, content); }
    private void showAlert(Alert.AlertType type, String title, String content){
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> showAlert(type, title, content)); return; }
        try {
            Alert alert = new Alert(type); alert.setTitle(title); alert.setHeaderText(null); alert.setContentText(content);
            Stage owner = getCurrentStage();
            if(owner != null && owner.isShowing()){ alert.initOwner(owner); alert.showAndWait(); }
            else { System.out.println("GC: Sopprimo alert '"+title+"' - nessuno stage proprietario valido."); }
        } catch (Exception e) { System.err.println("GC: Errore mostrando alert '"+title+"': "+e.getMessage()); }
    }
    private Stage getCurrentStage() {
        try {
            Node node = gridPane != null ? gridPane : TextTurno;
            if (node != null && node.getScene() != null && node.getScene().getWindow() instanceof Stage) {
                Stage stage = (Stage) node.getScene().getWindow();
                if (stage != null && stage.isShowing()) return stage;
            }
            return Stage.getWindows().stream()
                    .filter(Window::isShowing).filter(w -> w instanceof Stage).map(w -> (Stage)w)
                    .findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

}