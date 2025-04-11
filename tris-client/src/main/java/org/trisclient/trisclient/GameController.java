/* ======== GameController.java ======== */
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

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }


    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): FXML initialize CALLED");
        this.lastGameResult = null; this.opponentDeclinedWhileWaiting.set(false);
        isSetupComplete.set(false); gameActive.set(false); gameFinishedWaitingRematch.set(false);
        cachedBoard = null; cachedTurn.set(false); myTurn = false;
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
        TextTurno.setText("Loading game...");
    }

    public void setupGame(NetworkService serviceInstance, int gameId, char symbol, String opponentName,
                          Consumer<String> returnCallback,
                          String[] initialBoard, boolean initialTurn) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): setupGame CALLED. GameID: " + gameId);
        this.lastGameResult = null; this.opponentDeclinedWhileWaiting.set(false);
        this.networkService = serviceInstance; this.gameId = gameId; this.mySymbol = symbol;
        this.opponentName = opponentName; this.returnToHomeCallback = returnCallback;
        this.isSetupComplete.set(false); this.gameActive.set(false);
        this.gameFinishedWaitingRematch.set(false); this.myTurn = false;
        if (initialBoard != null) this.cachedBoard = initialBoard; else this.cachedBoard = null;
        this.cachedTurn.set(initialTurn);

        if (this.networkService == null) { /*...*/ return; }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Setting listener");
        this.networkService.setServerListener(this);

        Platform.runLater(() -> {
            TextTurno.setText("Game " + gameId + " vs " + opponentName + ". You are " + mySymbol + ".");
            gridPane.setDisable(true); if (buttonLeave != null) buttonLeave.setDisable(false);
            isSetupComplete.set(true); gameActive.set(true);
            System.out.println(getCurrentTimestamp() + " - GC (runLater): Setup COMPLETE. gameActive=true");
            processCachedMessages();
        });
    }

    private void processCachedMessages() {
        System.out.println(getCurrentTimestamp() + " - GC: Processing cached messages...");
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
        // Aggiorna testo bottoni
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (buttons[i][j] == null) continue;
                String symbol = boardCells[i * 3 + j]; boolean isEmpty = "-".equals(symbol);
                buttons[i][j].setText(isEmpty ? " " : symbol);
                // NON aggiornare disable qui, dipende da myTurn
            }
        }
        // Disabilita/Abilita la griglia INTERA basandosi sullo stato generale e myTurn
        // Questo assicura coerenza anche se un singolo bottone rimaneva abilitato per errore
        gridPane.setDisable(!gameActive.get() || !myTurn);
    }


    @Override
    public void onYourTurn() {
        System.out.println(getCurrentTimestamp()+" - GC: onYourTurn received. isSetupComplete="+isSetupComplete.get()+" | gameActive="+gameActive.get());
        if (!isSetupComplete.get()) { this.cachedTurn.set(true); return; }
        if (!gameActive.get()) {
            System.out.println(getCurrentTimestamp()+" - GC: Ignoring onYourTurn because gameActive is false.");
            return; // Ignora se il gioco non è più attivo (es. è finito subito dopo)
        }
        handleYourTurnInternal();
    }

    private void handleYourTurnInternal() {
        if (!gameActive.get()) return; // Check extra
        myTurn = true;
        Platform.runLater(() -> { // Assicura che le modifiche UI siano sul thread FX
            TextTurno.setText("Your turn! (" + mySymbol + ")");
            System.out.println(getCurrentTimestamp()+" - GC (UI): Enabling gridPane for your turn.");
            gridPane.setDisable(false); // Abilita la griglia
        });
    }

    private void handleCellClick(int row, int col) {
        if (buttons[row][col] == null) return;
        // Controllo aggiuntivo: assicurati che la casella sia effettivamente vuota
        if (myTurn && gameActive.get() && buttons[row][col].getText().trim().isEmpty()) {
            System.out.println(getCurrentTimestamp()+" - GC: Handling click on "+row+","+col);
            myTurn = false; // Diventa non il mio turno
            buttons[row][col].setText(String.valueOf(mySymbol)); // Aggiorna UI subito
            gridPane.setDisable(true); // Disabilita la griglia subito dopo la mossa
            TextTurno.setText("Sending move...");
            if(networkService != null) networkService.sendMove(row, col);
            else { /* Network Error */ if (returnToHomeCallback != null) Platform.runLater(()->returnToHomeCallback.accept("Network Error")); }
        } else {
            System.out.println(getCurrentTimestamp()+" - GC: Ignored click on "+row+","+col + " (myTurn="+myTurn+", gameActive="+gameActive.get()+", buttonText='"+buttons[row][col].getText()+"')");
        }
    }

    @Override
    public void onOpponentLeft() {
        System.out.println(getCurrentTimestamp() + " - GC: onOpponentLeft received.");
        gameActive.set(false); gameFinishedWaitingRematch.set(false); myTurn = false;
        Platform.runLater(() -> {
            gridPane.setDisable(true);
            String message = "Opponent left. You Win!";
            TextTurno.setText(message);
            showInfo("Game Over", message + "\nReturning to lobby.");
            if (returnToHomeCallback != null) returnToHomeCallback.accept("Opponent left - You Win!");
        });
    }

    @Override
    public void onNameRejected(String reason) {
        System.err.println(getCurrentTimestamp() + " - GameController ("+this.hashCode()+"): !!! UNEXPECTED onNameRejected received: " + reason + " !!!");
        gameActive.set(false); gameFinishedWaitingRematch.set(false); myTurn = false;
        Platform.runLater(() -> {
            showError("Critical State Error", "Received name rejection while in game: " + reason + "\nReturning to lobby.");
            if (gridPane != null) gridPane.setDisable(true);
            if (buttonLeave != null) buttonLeave.setDisable(true);
            if (TextTurno != null) TextTurno.setText("Unexpected error...");
            if (returnToHomeCallback != null) { returnToHomeCallback.accept("Unexpected name rejection: " + reason); }
            else { System.err.println("GameController: CRITICAL - returnToHomeCallback is null after unexpected name rejection!"); if(networkService != null) networkService.disconnect(); }
        });
    }

    @Override
    public void onRematchOffer() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onRematchOffer received. Last result: " + lastGameResult + ". WaitingRematch Flag: " + gameFinishedWaitingRematch.get());
        if (!gameFinishedWaitingRematch.get()) {
            System.err.println(getCurrentTimestamp()+" - GC: WARNING - onRematchOffer received but gameFinishedWaitingRematch is false!");
        }

        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Rematch?");
            String headerText = "Game Over!";
            String contentText = "Play again?";
            boolean isDraw = "DRAW".equalsIgnoreCase(this.lastGameResult);
            boolean isWinner = "WIN".equalsIgnoreCase(this.lastGameResult);

            if(isDraw) { headerText = "It's a Draw!"; contentText = "Play again?"; }
            else if (isWinner) { headerText = "You Won!"; contentText = "Host a new game vs this opponent?"; }
            else { System.err.println(getCurrentTimestamp()+" - GC: ERROR - Received rematch offer but result was LOSS? Should not happen."); alert.close(); if(returnToHomeCallback!=null) returnToHomeCallback.accept("Error: Unexpected rematch offer"); return; }

            alert.setHeaderText(headerText);
            alert.setContentText(contentText);

            ButtonType buttonTypeYes = new ButtonType("Yes, Play Again");
            ButtonType buttonTypeNo = new ButtonType("No, Back to Lobby");
            alert.getButtonTypes().setAll(buttonTypeYes, buttonTypeNo);
            try { Stage owner = getCurrentStage(); if(owner != null && owner.isShowing()) alert.initOwner(owner); } catch(Exception e) { System.err.println("GC: Error setting owner for rematch alert: "+e.getMessage()); }

            Optional<ButtonType> result = alert.showAndWait();

            if (isDraw && opponentDeclinedWhileWaiting.getAndSet(false)) {
                System.out.println(getCurrentTimestamp()+" - GC: Opponent declined (DRAW) while local player was deciding. Choice ignored. Returning home.");
                gameFinishedWaitingRematch.set(false);
                Platform.runLater(() -> {
                    showInfo("Rematch Cancelled", "The opponent declined the rematch while you were deciding.\nReturning to the lobby.");
                    if (returnToHomeCallback != null) { returnToHomeCallback.accept("Opponent declined (while deciding)"); }
                    else { System.err.println("GC: returnToHomeCallback is null after opponent declined while waiting!"); }
                });
                return;
            }

            boolean myChoiceIsYes = result.isPresent() && result.get() == buttonTypeYes;

            if (myChoiceIsYes) {
                System.out.println(getCurrentTimestamp()+" - GC: User chose YES for rematch. Sending REMATCH YES.");
                if(networkService != null){
                    TextTurno.setText("Sending choice... Waiting for opponent/server...");
                    networkService.sendRematchChoice(true);
                    gameFinishedWaitingRematch.set(true);
                    System.out.println(getCurrentTimestamp()+" - GC: Sent REMATCH YES, flag waitingRematch="+gameFinishedWaitingRematch.get());
                } else { gameFinishedWaitingRematch.set(false); TextTurno.setText("Network Error!"); showError("Network Error", "Could not send rematch choice."); if (returnToHomeCallback != null) Platform.runLater(()->returnToHomeCallback.accept("Network Error")); }
            } else {
                System.out.println(getCurrentTimestamp()+" - GC: User chose NO for rematch. Sending REMATCH NO.");
                if(networkService != null){
                    TextTurno.setText("Declining rematch...");
                    networkService.sendRematchChoice(false);
                    System.out.println(getCurrentTimestamp()+" - GC: Sent REMATCH NO, flag waitingRematch="+gameFinishedWaitingRematch.get());
                } else { gameFinishedWaitingRematch.set(false); TextTurno.setText("Network Error!"); showError("Network Error", "Could not send rematch choice."); if (returnToHomeCallback != null) Platform.runLater(()->returnToHomeCallback.accept("Network Error")); }
            }
        });
    }

    @Override
    public void onRematchAccepted(int receivedGameId) {
        System.out.println(getCurrentTimestamp() + " - GC: onRematchAccepted received (Game " + receivedGameId + ")");
        gameFinishedWaitingRematch.set(false); gameActive.set(false); myTurn=false;
        Platform.runLater(() -> {
            showInfo("Rematch Accepted (Host)", "Rematch accepted! Hosting game " + receivedGameId + ".\nReturning to lobby to wait.");
            if (returnToHomeCallback != null) returnToHomeCallback.accept("Rematch accepted, waiting");
            else System.err.println("GC: Callback null on onRematchAccepted!");
        });
    }

    @Override
    public void onGameOver(String result) {
        System.out.println(getCurrentTimestamp() + " - GC: onGameOver - Result: " + result + " | Current gameActive=" + gameActive.get());

        if (!gameActive.compareAndSet(true, false)) {
            System.out.println(getCurrentTimestamp()+" - GC: Ignoring redundant onGameOver ("+result+"). Game already inactive.");
            return;
        }

        this.lastGameResult = result;
        gameFinishedWaitingRematch.set(true);
        myTurn = false;
        this.opponentDeclinedWhileWaiting.set(false);

        final String finalResult = result;

        Platform.runLater(() -> {
            gridPane.setDisable(true); // Sempre disabilitato dopo game over

            String message = "Game Over! ";
            if ("WIN".equalsIgnoreCase(finalResult)) { message += "You Won!"; message += "\nWaiting for rematch options..."; }
            else if ("DRAW".equalsIgnoreCase(finalResult)) { message += "It's a Draw!"; message += "\nWaiting for rematch options..."; }
            else if ("LOSE".equalsIgnoreCase(finalResult)){ message += "You Lost."; message += "\nReturning to lobby..."; }
            else { message += "Unknown result (" + finalResult + ")"; message += "\nReturning to lobby..."; }

            TextTurno.setText(message);
            System.out.println(getCurrentTimestamp()+" - GC (UI): Game Over UI updated. Result: "+finalResult+". WaitingRematch="+gameFinishedWaitingRematch.get());
            if(buttonLeave!=null) buttonLeave.setDisable(false); // Abilita leave
        });
    }

    @Override
    public void onRematchDeclined() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onRematchDeclined received. LastResult="+lastGameResult+" | WaitingFlag before:"+gameFinishedWaitingRematch.get());

        boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false);
        gameActive.set(false); myTurn=false;
        opponentDeclinedWhileWaiting.set(false);

        final String alertTitle = "Rematch Declined";
        String alertContent = "The rematch was declined.\nReturning to the lobby.";
        String returnReason = "Rematch declined";

        if ("LOSE".equalsIgnoreCase(lastGameResult)) { alertContent = "Game lost.\nReturning to the lobby."; returnReason = "Lost game"; }
        else if (!wasWaiting && !"LOSE".equalsIgnoreCase(lastGameResult)) { System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): WARNING - Received RematchDeclined but wasn't waiting and didn't lose."); }

        final String finalAlertContent = alertContent;
        final String finalReturnReason = returnReason;

        Platform.runLater(() -> {
            showInfo(alertTitle, finalAlertContent);
            if (returnToHomeCallback != null) { returnToHomeCallback.accept(finalReturnReason); }
            else { System.err.println("GC: returnToHomeCallback null after onRematchDeclined!"); showError("Critical Error", "Cannot return to lobby after game over. Callback missing."); }
        });
    }

    @Override
    public void onOpponentRematchDecision(boolean opponentAccepted) {
        final String decision = opponentAccepted ? "accepted" : "declined";
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onOpponentRematchDecision received: "+decision+" *** | Current waiting Flag: "+gameFinishedWaitingRematch.get() + " | isDraw: "+("DRAW".equalsIgnoreCase(lastGameResult)));

        if (!opponentAccepted) {
            if ("DRAW".equalsIgnoreCase(lastGameResult) && gameFinishedWaitingRematch.get()) {
                System.out.println(getCurrentTimestamp() + " - GC: Opponent DECLINED (DRAW) while local player WAITING. Setting flag.");
                opponentDeclinedWhileWaiting.set(true);
            } else {
                System.out.println(getCurrentTimestamp()+" - GC: Opponent DECLINED but local player NOT waiting for own choice (or not draw). Returning home.");
                boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false);
                gameActive.set(false); myTurn=false;
                Platform.runLater(() -> {
                    showInfo("Rematch Declined", "The opponent declined the rematch.\nReturning to the lobby.");
                    if (returnToHomeCallback != null) { returnToHomeCallback.accept("Opponent declined rematch"); }
                    else { System.err.println("GC: Callback null on Opponent DECLINED decision!"); }
                });
            }
        }
        else { // opponentAccepted == true
            if ("LOSE".equalsIgnoreCase(lastGameResult)) {
                System.out.println(getCurrentTimestamp()+" - GC: Opponent (Winner) ACCEPTED rematch. Returning loser to lobby.");
                boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false);
                gameActive.set(false); myTurn=false;
                Platform.runLater(() -> {
                    showInfo("Rematch Accepted (by Opponent)", "The winner accepted the rematch and is hosting a new game.\nReturning to the lobby.");
                    if (returnToHomeCallback != null) { returnToHomeCallback.accept("Opponent accepted, back to lobby"); }
                    else { System.err.println("GC: Callback null on Opponent ACCEPTED (Loser case)!"); }
                });
            } else if ("DRAW".equalsIgnoreCase(lastGameResult)) {
                System.out.println(getCurrentTimestamp()+" - GC: Opponent ACCEPTED (DRAW). Awaiting further server action (GameStart or local decision). Flag waitingRematch="+gameFinishedWaitingRematch.get());
            } else { System.err.println(getCurrentTimestamp()+" - GC: Winner received Opponent ACCEPTED? Unexpected."); }
        }
    }

    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp() + " - GC: onError received: " + message);

        if (message != null && message.contains("Unknown command or invalid state (1) for command: REMATCH NO")) {
            System.out.println(getCurrentTimestamp()+" - GC: IGNORING expected 'invalid state' error after sending REMATCH NO (because opponent already declined/left).");
            return;
        }

        if (!gameActive.get() && !gameFinishedWaitingRematch.get()) {
            System.out.println(getCurrentTimestamp()+" - GC: Error received but game not active/waiting. Ignoring.");
            return;
        }

        Platform.runLater(()-> {
            if (message.contains("Not your turn") || message.contains("Invalid move")) {
                TextTurno.setText(message.replace("ERROR:", "").trim() + " Try again.");
                if (myTurn && gameActive.get()) {
                    // Se era il mio turno e la mossa è fallita, riabilita la griglia
                    gridPane.setDisable(false);
                } else { gridPane.setDisable(true); }
            } else {
                showError("Server Error", message);
                TextTurno.setText("Error: " + message);
                gridPane.setDisable(true);
                gameActive.set(false); gameFinishedWaitingRematch.set(false); myTurn = false;
                if (returnToHomeCallback != null) returnToHomeCallback.accept("Server Error");
            }
        });
    }

    @FXML
    private void handleLeaveGame() {
        System.out.println(getCurrentTimestamp() + " - GC: Leave Game clicked.");
        gameActive.set(false); gameFinishedWaitingRematch.set(false); opponentDeclinedWhileWaiting.set(false);
        myTurn = false;
        Platform.runLater(() -> {
            gridPane.setDisable(true); if(buttonLeave != null) buttonLeave.setDisable(true);
            TextTurno.setText("Leaving...");
        });
        final String callbackMsg = "VOLUNTARY_LEAVE"; final String alertContent = "You left the game.\nReturning to lobby.";
        if (networkService != null && networkService.isConnected()) networkService.sendQuit();
        if (returnToHomeCallback != null) Platform.runLater(() -> { showInfo("Game Left", alertContent); returnToHomeCallback.accept(callbackMsg); });
        else showError("Error", "Cannot return to home!");
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp() + " - GC: onDisconnected - Reason: " + reason);
        gameActive.set(false); gameFinishedWaitingRematch.set(false); opponentDeclinedWhileWaiting.set(false); myTurn = false;
        final String finalReason = reason;
        Platform.runLater(() -> {
            gridPane.setDisable(true); if(buttonLeave != null) buttonLeave.setDisable(true);
            TextTurno.setText("Disconnected");
            if (!"Disconnected by client".equals(finalReason)) showInfo("Disconnected", "Connection lost: " + finalReason + "\nReturning to lobby.");
            if (returnToHomeCallback != null) returnToHomeCallback.accept("Disconnected: " + finalReason);
            else System.err.println("GC: Callback null on disconnect!");
        });
    }

    // --- Listener non previsti qui ---
    @Override public void onConnected() { System.err.println(getCurrentTimestamp()+" - GC: Unexpected onConnected"); }
    @Override public void onNameRequested() { System.err.println(getCurrentTimestamp()+" - GC: Unexpected onNameRequested"); }
    @Override public void onNameAccepted() { System.err.println(getCurrentTimestamp()+" - GC: Unexpected onNameAccepted"); }
    @Override public void onGamesList(List<NetworkService.GameInfo> g) { System.err.println(getCurrentTimestamp()+" - GC: Unexpected onGamesList"); }
    @Override public void onGameCreated(int gid) { System.err.println(getCurrentTimestamp()+" - GC: Unexpected onGameCreated"); }
    @Override public void onJoinRequestSent(int gid) { System.err.println(getCurrentTimestamp()+" - GC: Unexpected onJoinRequestSent"); }
    @Override public void onJoinRequestReceived(String n) { System.err.println(getCurrentTimestamp()+" - GC: Unexpected onJoinRequestReceived"); }
    @Override public void onJoinAccepted(int gid, char s, String on) { System.err.println(getCurrentTimestamp()+" - GC: Unexpected onJoinAccepted"); }
    @Override public void onJoinRejected(int gid, String cn) { System.err.println(getCurrentTimestamp()+" - GC: Unexpected onJoinRejected"); }
    @Override public void onActionConfirmed(String m) {
        // Potrebbe arrivare RESP:QUIT_OK qui se l'utente clicca leave, non è strettamente un errore
        if (m != null && m.startsWith("QUIT_OK")) {
            System.out.println(getCurrentTimestamp()+" - GC: Received ActionConfirmed (likely QUIT_OK): "+m);
        } else {
            System.err.println(getCurrentTimestamp()+" - GC: Unexpected onActionConfirmed: "+m);
        }
    }
    @Override public void onMessageReceived(String rm) { System.err.println(getCurrentTimestamp()+" - GC: Unexpected raw message: "+rm); }

    // --- onGameStart MODIFICATO ---
    @Override
    public void onGameStart(int recGameId, char recSymbol, String recOpponentName) {
        System.out.println(getCurrentTimestamp()+" - GC: onGameStart received for game "+recGameId);
        if(this.gameId != recGameId){ System.err.println("GC: ERROR GameStart for wrong ID!"); return; }

        // Verifica se è un rematch per pareggio
        boolean isDrawRematch = gameFinishedWaitingRematch.compareAndSet(true,false);

        // Sempre impostare il gioco come attivo (anche se non è un rematch, ma inizio partita)
        gameActive.set(true);
        // Aggiorna simboli e nomi in ogni caso
        this.mySymbol = recSymbol; this.opponentName = recOpponentName;
        // Reset del turno, sarà impostato da onYourTurn
        this.myTurn = false;

        if (isDrawRematch) {
            // Siamo in un rematch per pareggio
            System.out.println(getCurrentTimestamp() + " - GC: GameStart -> DRAW REMATCH STARTING!");
            Platform.runLater(() -> {
                TextTurno.setText("Rematch! vs " + this.opponentName + " (You are " + this.mySymbol + ")");
                // Pulisci board UI
                for (Button[] row : buttons) for (Button btn : row) if(btn!=null) btn.setText(" ");
                // *** NON disabilitare la griglia qui ***
                // Verrà abilitata da onYourTurn() se è il nostro turno
                if (buttonLeave != null) buttonLeave.setDisable(false);
                System.out.println(getCurrentTimestamp()+" - GC (UI Rematch): Board cleared. Grid state depends on next YOUR_TURN.");
            });
            // Potrebbe essere necessario processare una board o un turno se sono arrivati
            // quasi contemporaneamente a GAME_START? Forse non serve, `onYourTurn` dovrebbe arrivare dopo.
            // processCachedMessages(); // Proviamo senza per ora
        } else {
            // Caso normale di inizio partita (dopo join o accept)
            System.out.println(getCurrentTimestamp() + " - GC: GameStart -> NORMAL game start.");
            // La UI viene già preparata da setupGame e i messaggi board/turn sono in cache
            // `processCachedMessages()` li gestirà una volta che isSetupComplete è true
            // Potrebbe esserci il caso che SetupGame non sia ancora completo qui?
            if (isSetupComplete.get()) {
                System.out.println(getCurrentTimestamp()+" - GC: Processing cached messages immediately after normal GameStart (Setup was complete).");
                processCachedMessages(); // Processa subito board/turno se già setup
            } else {
                System.out.println(getCurrentTimestamp()+" - GC: Deferring processing cached messages, setup not complete yet.");
            }
        }
    }
    // --- FINE onGameStart ---


    // --- Metodi Helper UI (invariati) ---
    private void showInfo(String title, String content) { showAlert(Alert.AlertType.INFORMATION, title, content); }
    private void showError(String title, String content) { showAlert(Alert.AlertType.ERROR, title, content); }
    private void showAlert(Alert.AlertType type, String title, String content){
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> showAlert(type, title, content)); return; }
        try {
            Alert alert = new Alert(type); alert.setTitle(title); alert.setHeaderText(null); alert.setContentText(content);
            Stage owner = getCurrentStage();
            if(owner != null && owner.isShowing()){ alert.initOwner(owner); alert.showAndWait(); }
            else { System.out.println("GC: Suppressing alert '"+title+"' - no valid owner stage."); }
        } catch (Exception e) { System.err.println("GC: Error showing alert '"+title+"': "+e.getMessage()); }
    }
    private Stage getCurrentStage() {
        try {
            Node node = gridPane != null ? gridPane : TextTurno; // Usa un nodo presente
            if (node != null && node.getScene() != null && node.getScene().getWindow() instanceof Stage) {
                Stage stage = (Stage) node.getScene().getWindow();
                if (stage != null && stage.isShowing()) return stage;
            }
            // Fallback estremo se i nodi primari non danno lo stage
            return Stage.getWindows().stream()
                    .filter(Window::isShowing).filter(w -> w instanceof Stage).map(w -> (Stage)w)
                    .findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

} // Fine classe GameController