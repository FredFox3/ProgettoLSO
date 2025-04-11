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
    private String lastGameResult = null; // Memorizza l'ultimo risultato (WIN/LOSE/DRAW)
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
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (buttons[i][j] == null) continue;
                String symbol = boardCells[i * 3 + j]; boolean isEmpty = "-".equals(symbol);
                buttons[i][j].setText(isEmpty ? " " : symbol); buttons[i][j].setDisable(!isEmpty);
            }
        }
        gridPane.setDisable(!gameActive.get() || (gameActive.get() && !myTurn));
    }

    @Override
    public void onYourTurn() {
        if (!isSetupComplete.get()) { this.cachedTurn.set(true); return; }
        if (!gameActive.get()) return;
        handleYourTurnInternal();
    }

    private void handleYourTurnInternal() {
        if (!gameActive.get()) return;
        myTurn = true;
        TextTurno.setText("Your turn! (" + mySymbol + ")");
        gridPane.setDisable(false);
    }

    private void handleCellClick(int row, int col) {
        if (buttons[row][col] == null) return;
        if (myTurn && gameActive.get() && buttons[row][col].getText().trim().isEmpty()) {
            myTurn = false;
            buttons[row][col].setText(String.valueOf(mySymbol));
            gridPane.setDisable(true);
            TextTurno.setText("Sending move...");
            if(networkService != null) networkService.sendMove(row, col);
            else { /* Network Error */ if (returnToHomeCallback != null) Platform.runLater(()->returnToHomeCallback.accept("Network Error")); }
        }
    }

    @Override
    public void onOpponentLeft() {
        System.out.println(getCurrentTimestamp() + " - GC: onOpponentLeft received.");
        gameActive.set(false); gameFinishedWaitingRematch.set(false); myTurn = false;
        gridPane.setDisable(true);
        String message = "Opponent left. You Win!";
        TextTurno.setText(message);
        showInfo("Game Over", message + "\nReturning to lobby.");
        if (returnToHomeCallback != null) Platform.runLater(() -> returnToHomeCallback.accept("Opponent left - You Win!"));
    }

    // ---- onRematchOffer AGGIORNATO ----
    @Override
    public void onRematchOffer() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onRematchOffer received. Last result: " + lastGameResult + ". WaitingRematch Flag: " + gameFinishedWaitingRematch.get());
        if (!gameFinishedWaitingRematch.get()) { // Controllo: Dovremmo essere in questo stato
            System.err.println(getCurrentTimestamp()+" - GC: WARNING - onRematchOffer received but gameFinishedWaitingRematch is false!");
            // Forse forzare il flag?
            // gameFinishedWaitingRematch.set(true); // Considerare se è sicuro farlo
            // Oppure ignorare? Dipende dalla causa... per ora logghiamo
        }

        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Rematch?");
            String headerText = "Game Over!";
            String contentText = "Play again?";
            boolean isDraw = "DRAW".equalsIgnoreCase(this.lastGameResult);
            boolean isWinner = "WIN".equalsIgnoreCase(this.lastGameResult);

            if(isDraw) {
                headerText = "It's a Draw!";
                contentText = "Play again?";
            } else if (isWinner) { // L'offerta arriva solo al vincitore o in pareggio
                headerText = "You Won!";
                contentText = "Host a new game vs this opponent?"; // Il server offre solo al vincitore nel caso win/lose
            } else { // L'offerta non dovrebbe arrivare al perdente
                System.err.println(getCurrentTimestamp()+" - GC: ERROR - Received rematch offer but result was LOSS? Should not happen.");
                alert.close(); // Chiudi alert per sicurezza
                if(returnToHomeCallback!=null) returnToHomeCallback.accept("Error: Unexpected rematch offer");
                return;
            }

            alert.setHeaderText(headerText);
            alert.setContentText(contentText);

            ButtonType buttonTypeYes = new ButtonType("Yes, Play Again");
            ButtonType buttonTypeNo = new ButtonType("No, Back to Lobby");
            alert.getButtonTypes().setAll(buttonTypeYes, buttonTypeNo);
            try { Stage owner = getCurrentStage(); if(owner != null && owner.isShowing()) alert.initOwner(owner); } catch(Exception e) { System.err.println("GC: Error setting owner for rematch alert: "+e.getMessage()); }

            Optional<ButtonType> result = alert.showAndWait();

            // *** MODIFICA CHIAVE: Controlla SE l'avversario ha rifiutato MENTRE il popup era aperto ***
            if (isDraw && opponentDeclinedWhileWaiting.getAndSet(false)) {
                // L'avversario ha RIFIUTATO durante la nostra attesa (solo rilevante per Draw)
                System.out.println(getCurrentTimestamp()+" - GC: Opponent declined (DRAW) while local player was deciding. Choice ignored. Returning home.");
                gameFinishedWaitingRematch.set(false); // Non siamo più in attesa della rivincita
                // Mostra popup informativo
                Platform.runLater(() -> {
                    showInfo("Rematch Cancelled", "The opponent declined the rematch while you were deciding.\nReturning to the lobby.");
                    // Ritorna alla home page
                    if (returnToHomeCallback != null) {
                        returnToHomeCallback.accept("Opponent declined (while deciding)");
                    } else {
                        System.err.println("GC: returnToHomeCallback is null after opponent declined while waiting!");
                    }
                });
                return; // Esce dalla gestione della scelta locale
            }

            // Se l'avversario non ha rifiutato (o non era un pareggio), procedi con la scelta locale
            boolean myChoiceIsYes = result.isPresent() && result.get() == buttonTypeYes;

            if (myChoiceIsYes) {
                // L'UTENTE HA DETTO YES
                System.out.println(getCurrentTimestamp()+" - GC: User chose YES for rematch. Sending REMATCH YES.");
                if(networkService != null){
                    TextTurno.setText("Sending choice... Waiting for opponent/server...");
                    networkService.sendRematchChoice(true);
                    // Manteniamo gameFinishedWaitingRematch = true perché aspettiamo
                    // una risposta server (ACCEPTED, GAME_START) o la decisione dell'altro (in pareggio)
                    gameFinishedWaitingRematch.set(true); // Conferma che stiamo aspettando l'esito
                    System.out.println(getCurrentTimestamp()+" - GC: Sent REMATCH YES, flag waitingRematch="+gameFinishedWaitingRematch.get());
                } else {
                    /* Network Error */
                    gameFinishedWaitingRematch.set(false);
                    TextTurno.setText("Network Error!");
                    showError("Network Error", "Could not send rematch choice.");
                    if (returnToHomeCallback != null) Platform.runLater(()->returnToHomeCallback.accept("Network Error"));
                }
            } else {
                // L'UTENTE HA DETTO NO (o ha chiuso il popup)
                System.out.println(getCurrentTimestamp()+" - GC: User chose NO for rematch. Sending REMATCH NO.");
                if(networkService != null){
                    TextTurno.setText("Declining rematch...");
                    networkService.sendRematchChoice(false); // Invia il comando
                    // NON modificare gameFinishedWaitingRematch qui.
                    // Aspettiamo la conferma RESP:REMATCH_DECLINED dal server,
                    // che chiamerà onRematchDeclined() e resetterà il flag.
                    System.out.println(getCurrentTimestamp()+" - GC: Sent REMATCH NO, flag waitingRematch="+gameFinishedWaitingRematch.get());
                } else {
                    /* Network Error */
                    gameFinishedWaitingRematch.set(false);
                    TextTurno.setText("Network Error!");
                    showError("Network Error", "Could not send rematch choice.");
                    if (returnToHomeCallback != null) Platform.runLater(()->returnToHomeCallback.accept("Network Error"));
                }
            }
        });
    }
    // ---- FINE onRematchOffer ----


    @Override
    public void onRematchAccepted(int receivedGameId) {
        System.out.println(getCurrentTimestamp() + " - GC: onRematchAccepted received (Game " + receivedGameId + ")");
        gameFinishedWaitingRematch.set(false); gameActive.set(false);
        Platform.runLater(() -> showInfo("Rematch Accepted (Host)", "Rematch accepted! Hosting game " + receivedGameId + ".\nReturning to lobby to wait."));
        if (returnToHomeCallback != null) Platform.runLater(() -> returnToHomeCallback.accept("Rematch accepted, waiting"));
        else System.err.println("GC: Callback null on onRematchAccepted!");
    }

    @Override
    public void onGameOver(String result) {
        System.out.println(getCurrentTimestamp() + " - GC: onGameOver - Result: " + result + " | Current gameActive=" + gameActive.get());

        if (!gameActive.compareAndSet(true, false)) {
            System.out.println(getCurrentTimestamp()+" - GC: Ignoring redundant onGameOver ("+result+"). Game already inactive.");
            return;
        }

        this.lastGameResult = result;
        gameFinishedWaitingRematch.set(true); // Mettiamo in attesa INIZIALMENTE per tutti
        myTurn = false;
        this.opponentDeclinedWhileWaiting.set(false);

        final String finalResult = result; // Capture result for lambda

        Platform.runLater(() -> {
            gridPane.setDisable(true);

            String message = "Game Over! ";
            boolean loserReturn = false; // Flag to trigger immediate return for loser

            if ("WIN".equalsIgnoreCase(finalResult)) {
                message += "You Won!";
                message += "\nWaiting for rematch options..."; // Winner waits
            } else if ("DRAW".equalsIgnoreCase(finalResult)) {
                message += "It's a Draw!";
                message += "\nWaiting for rematch options..."; // Draw waits
            } else if ("LOSE".equalsIgnoreCase(finalResult)){
                message += "You Lost.";
                // Non aspettare opzioni, verrà inviato subito RESP:REMATCH_DECLINED
                message += "\nReturning to lobby...";
                loserReturn = true; // Imposta il flag per tornare indietro
            } else {
                message += "Unknown result (" + finalResult + ")";
                message += "\nReturning to lobby...";
                loserReturn = true; // Torna indietro per sicurezza su risultato sconosciuto
            }

            TextTurno.setText(message);
            System.out.println(getCurrentTimestamp()+" - GC (UI): Game Over UI updated. Result: "+finalResult+". WaitingRematch="+gameFinishedWaitingRematch.get());
            if(buttonLeave!=null) buttonLeave.setDisable(false);

            // ** NOTA: Il ritorno effettivo per il perdente avverrà quando riceverà
            // ** RESP:REMATCH_DECLINED subito dopo questo messaggio.

            // Se avessimo voluto ritornare *direttamente qui* (meno pulito, perché il server
            // potrebbe mandare il messaggio DECLINED subito dopo), avremmo fatto:
            // if (loserReturn) {
            //     handleLossReturn(); // Chiama un helper per tornare alla home
            // }
        });
    }


    // --- onRematchDeclined MODIFICATO ---
    @Override
    public void onRematchDeclined() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onRematchDeclined received. LastResult="+lastGameResult+" | WaitingFlag before:"+gameFinishedWaitingRematch.get());

        // Questo metodo ora è chiamato in 3 casi:
        // 1. Hai scelto "NO" alla rivincita (Draw o Win).
        // 2. Eri il PERDENTE e il server ti manda questo *immediatamente* dopo GAMEOVER LOSE.
        // 3. Eri in PAREGGIO, hai scelto YES, ma l'avversario aveva GIA' scelto NO.

        boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false); // Resetta il flag SEMPRE
        gameActive.set(false); // Gioco non attivo
        opponentDeclinedWhileWaiting.set(false); // Reset, per sicurezza

        final String alertTitle = "Rematch Declined";
        String alertContent = "The rematch was declined.\nReturning to the lobby.";
        String returnReason = "Rematch declined";

        // Se eravamo perdenti, il messaggio è leggermente diverso
        if ("LOSE".equalsIgnoreCase(lastGameResult)) {
            alertContent = "Game lost.\nReturning to the lobby.";
            returnReason = "Lost game";
        } else if (!wasWaiting && !"LOSE".equalsIgnoreCase(lastGameResult)) {
            // Se NON stavamo aspettando (es. doppio click leave, race condition?) E non era una sconfitta, logga un warning
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): WARNING - Received RematchDeclined but wasn't waiting and didn't lose.");
            // Potremmo non mostrare l'alert per evitare duplicati? Per ora lo mostriamo.
        }


        final String finalAlertContent = alertContent;
        final String finalReturnReason = returnReason;

        Platform.runLater(() -> {
            showInfo(alertTitle, finalAlertContent);

            if (returnToHomeCallback != null) {
                returnToHomeCallback.accept(finalReturnReason);
            } else {
                System.err.println("GC: returnToHomeCallback null after onRematchDeclined!");
                // Maybe show error here as well
                showError("Critical Error", "Cannot return to lobby after game over. Callback missing.");
            }
        });
    }

    // ---- onOpponentRematchDecision AGGIORNATO ----
    @Override
    public void onOpponentRematchDecision(boolean opponentAccepted) {
        final String decision = opponentAccepted ? "accepted" : "declined";
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onOpponentRematchDecision received: "+decision+" *** | Current waiting Flag: "+gameFinishedWaitingRematch.get() + " | isDraw: "+("DRAW".equalsIgnoreCase(lastGameResult)));

        // CASO 1: L'avversario ha RIFIUTATO
        if (!opponentAccepted) {
            // Controlla se questo messaggio arriva MENTRE l'utente locale sta ancora decidendo (nel caso DRAW)
            if ("DRAW".equalsIgnoreCase(lastGameResult) && gameFinishedWaitingRematch.get()) {
                System.out.println(getCurrentTimestamp() + " - GC: Opponent DECLINED (DRAW) while local player WAITING. Setting flag.");
                opponentDeclinedWhileWaiting.set(true); // Imposta il flag che verrà controllato DOPO la scelta locale
                // ** NON FARE NULLA QUI ** -> La gestione avverrà in onRematchOffer
            } else {
                // L'utente locale NON stava aspettando la propria decisione, quindi:
                // a) Era il perdente che aspettava la decisione del vincitore.
                // b) Era in pareggio, ma aveva già scelto YES (e ora l'altro dice NO).
                // c) Aveva già scelto NO (ricevere questo è meno probabile, ma possibile race condition).
                // In tutti questi casi -> la partita NON continua -> vai in lobby.
                System.out.println(getCurrentTimestamp()+" - GC: Opponent DECLINED but local player NOT waiting for own choice (or not draw). Returning home.");
                boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false); // Resetta il flag, per sicurezza
                gameActive.set(false);
                Platform.runLater(() -> {
                    showInfo("Rematch Declined", "The opponent declined the rematch.\nReturning to the lobby.");
                    if (returnToHomeCallback != null) { returnToHomeCallback.accept("Opponent declined rematch"); }
                    else { System.err.println("GC: Callback null on Opponent DECLINED decision!"); }
                });
            }
        }
        // CASO 2: L'avversario ha ACCETTATO
        else { // opponentAccepted == true
            // Questo può accadere in due scenari:
            // a) CASO DRAW: Tu hai scelto YES, lui sceglie YES -> Riceverai NOTIFY:GAME_START subito dopo.
            //    Non serve fare nulla qui, aspetta onGameStart. Il flag gameFinishedWaitingRematch viene resettato da onGameStart.
            // b) CASO LOSS: Tu sei il perdente, il vincitore sceglie YES -> Devi tornare alla lobby.
            if ("LOSE".equalsIgnoreCase(lastGameResult)) {
                System.out.println(getCurrentTimestamp()+" - GC: Opponent (Winner) ACCEPTED rematch. Returning loser to lobby.");
                boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false); // Reset flag
                gameActive.set(false);
                Platform.runLater(() -> {
                    showInfo("Rematch Accepted (by Opponent)", "The winner accepted the rematch and is hosting a new game.\nReturning to the lobby.");
                    if (returnToHomeCallback != null) { returnToHomeCallback.accept("Opponent accepted, back to lobby"); }
                    else { System.err.println("GC: Callback null on Opponent ACCEPTED (Loser case)!"); }
                });
            } else if ("DRAW".equalsIgnoreCase(lastGameResult)) {
                // Pareggio e l'altro ha accettato. Non fare nulla qui.
                // Se anche tu accetti (o hai già accettato), onGameStart arriverà.
                // Se tu rifiuti, onRematchDeclined arriverà.
                System.out.println(getCurrentTimestamp()+" - GC: Opponent ACCEPTED (DRAW). Awaiting further server action (GameStart or local decision). Flag waitingRematch="+gameFinishedWaitingRematch.get());
            } else { // WINNER - Non dovrebbe ricevere "Opponent Accepted"
                System.err.println(getCurrentTimestamp()+" - GC: Winner received Opponent ACCEPTED? Unexpected.");
            }
        }
    }
    // ---- FINE onOpponentRematchDecision ----

    // ---- onError AGGIORNATO per ignorare errore specifico ----
    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp() + " - GC: onError received: " + message);

        // Ignora l'errore specifico che arriva dopo aver mandato REMATCH NO perché l'altro ha declinato
        if (message != null && message.contains("Unknown command or invalid state (1) for command: REMATCH NO")) {
            System.out.println(getCurrentTimestamp()+" - GC: IGNORING expected 'invalid state' error after sending REMATCH NO (because opponent already declined/left).");
            return; // Non fare nulla, il ritorno alla home è già gestito
        }

        if (!gameActive.get() && !gameFinishedWaitingRematch.get()) {
            System.out.println(getCurrentTimestamp()+" - GC: Error received but game not active/waiting. Ignoring.");
            return; // Ignora se non in gioco/attesa
        }

        Platform.runLater(()-> {
            if (message.contains("Not your turn") || message.contains("Invalid move")) {
                TextTurno.setText(message.replace("ERROR:", "").trim() + " Try again.");
                if (myTurn && gameActive.get()) {
                    gridPane.setDisable(false);
                    for(Button[] row : buttons) for(Button btn : row) if(btn!=null) btn.setDisable(btn.getText().trim().isEmpty());
                } else { gridPane.setDisable(true); }
            } else {
                showError("Server Error", message);
                TextTurno.setText("Error: " + message);
                gridPane.setDisable(true);
                gameActive.set(false); gameFinishedWaitingRematch.set(false);
                if (returnToHomeCallback != null) returnToHomeCallback.accept("Server Error");
            }
        });
    }
    // ---- FINE onError ----

    @FXML
    private void handleLeaveGame() {
        System.out.println(getCurrentTimestamp() + " - GC: Leave Game clicked.");
        gameActive.set(false); gameFinishedWaitingRematch.set(false); opponentDeclinedWhileWaiting.set(false);
        myTurn = false;
        gridPane.setDisable(true); if(buttonLeave != null) buttonLeave.setDisable(true);
        TextTurno.setText("Leaving...");
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

    // --- Listener non previsti qui (con Log come prima) ---
    @Override public void onConnected() { System.err.println("GC: Unexpected onConnected"); }
    @Override public void onNameRequested() { System.err.println("GC: Unexpected onNameRequested"); }
    @Override public void onNameAccepted() { System.err.println("GC: Unexpected onNameAccepted"); }
    @Override public void onGamesList(List<NetworkService.GameInfo> g) { System.err.println("GC: Unexpected onGamesList"); }
    @Override public void onGameCreated(int gid) { System.err.println("GC: Unexpected onGameCreated"); }
    @Override public void onJoinRequestSent(int gid) { System.err.println("GC: Unexpected onJoinRequestSent"); }
    @Override public void onJoinRequestReceived(String n) { System.err.println("GC: Unexpected onJoinRequestReceived"); }
    @Override public void onJoinAccepted(int gid, char s, String on) { System.err.println("GC: Unexpected onJoinAccepted"); }
    @Override public void onJoinRejected(int gid, String cn) { System.err.println("GC: Unexpected onJoinRejected"); }
    @Override public void onActionConfirmed(String m) { System.err.println("GC: Unexpected onActionConfirmed: "+m); }
    @Override public void onMessageReceived(String rm) { System.err.println("GC: Unexpected raw message: "+rm); }

    // --- onGameStart come prima ---
    @Override
    public void onGameStart(int recGameId, char recSymbol, String recOpponentName) {
        System.out.println(getCurrentTimestamp()+" - GC: onGameStart received for game "+recGameId);
        if(this.gameId != recGameId){ System.err.println("GC: ERROR GameStart for wrong ID!"); return; }
        if (gameFinishedWaitingRematch.compareAndSet(true,false)) {
            System.out.println(getCurrentTimestamp() + " - GC: GameStart -> DRAW REMATCH STARTING!");
            gameActive.set(true); this.mySymbol = recSymbol; this.opponentName = recOpponentName;
            Platform.runLater(() -> {
                TextTurno.setText("Rematch! vs " + this.opponentName + " (Sei " + this.mySymbol + ")");
                for (Button[] row : buttons) for (Button btn : row) if(btn!=null) btn.setText(" ");
                gridPane.setDisable(true); if (buttonLeave != null) buttonLeave.setDisable(false);
            });
        } else { System.err.println("GC: ERROR GameStart received in unexpected state. Active="+gameActive.get()); }
    }

    @Override
    public void onNameRejected(String reason) {
        System.err.println(getCurrentTimestamp() + " - GameController ("+this.hashCode()+"): !!! UNEXPECTED onNameRejected received: " + reason + " !!!");
        gameActive.set(false); // Disattiva lo stato del gioco
        gameFinishedWaitingRematch.set(false);
        myTurn = false;
        Platform.runLater(() -> {
            showError("Critical State Error", "Received name rejection while in game: " + reason + "\nReturning to lobby.");
            if (gridPane != null) gridPane.setDisable(true);
            if (buttonLeave != null) buttonLeave.setDisable(true);
            if (TextTurno != null) TextTurno.setText("Unexpected error...");

            // Usa il callback per tornare alla home page
            if (returnToHomeCallback != null) {
                returnToHomeCallback.accept("Unexpected name rejection: " + reason);
            } else {
                System.err.println("GameController: CRITICAL - returnToHomeCallback is null after unexpected name rejection!");
                // In caso estremo, disconnetti
                if(networkService != null) networkService.disconnect();
            }
        });
    }

    // --- Metodi Helper UI ---
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
            return Stage.getWindows().stream()
                    .filter(Window::isShowing).filter(w -> w instanceof Stage).map(w -> (Stage)w)
                    .findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }
}