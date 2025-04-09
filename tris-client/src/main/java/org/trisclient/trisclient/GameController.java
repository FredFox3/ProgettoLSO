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
    public void onGameOver(String result) {
        System.out.println(getCurrentTimestamp() + " - GC: onGameOver - Result: " + result);
        if (!gameActive.compareAndSet(true, false)) return;
        this.lastGameResult = result;
        gameFinishedWaitingRematch.set(true); myTurn = false; gridPane.setDisable(true);

        String message = ("WIN".equalsIgnoreCase(result)) ? "You Won!" :
                ("DRAW".equalsIgnoreCase(result)) ? "It's a Draw!" : "You Lost.";
        message += "\nWaiting...";
        TextTurno.setText(message);
        if(buttonLeave!=null) buttonLeave.setDisable(false);
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
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onRematchOffer received. Last result: " + lastGameResult);
        if (!gameFinishedWaitingRematch.get()) { System.err.println("GC: RematchOffer in wrong state."); return; }

        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Rematch?");
            String headerText = "Game Over!";
            String contentText = "Play again?";
            boolean isDraw = "DRAW".equalsIgnoreCase(this.lastGameResult);
            if(isDraw) { headerText = "It's a Draw!"; }
            else { headerText = "You Won!"; contentText = "Host a new game?"; }
            alert.setHeaderText(headerText); alert.setContentText(contentText);

            ButtonType buttonTypeYes = new ButtonType("Yes, Play Again");
            ButtonType buttonTypeNo = new ButtonType("No, Back to Lobby");
            alert.getButtonTypes().setAll(buttonTypeYes, buttonTypeNo);
            try { Stage owner = getCurrentStage(); if(owner != null) alert.initOwner(owner); } catch(Exception e) {}

            Optional<ButtonType> result = alert.showAndWait();
            boolean myChoiceIsYes = result.isPresent() && result.get() == buttonTypeYes;

            // Controlla PRIMA se l'avversario ha già declinato
            if (opponentDeclinedWhileWaiting.getAndSet(false)) {
                System.out.println(getCurrentTimestamp()+" - GC: Opponent declined while deciding. Returning home.");
                gameFinishedWaitingRematch.set(false); // Uscita, non più in attesa
                if (returnToHomeCallback != null) {
                    final String msg = "Opponent declined while you were deciding.\nReturning to lobby.";
                    Platform.runLater(() -> { showInfo("Rematch Cancelled", msg); returnToHomeCallback.accept("Opponent declined (while deciding)"); });
                }
                return;
            }

            // Se l'avversario non aveva declinato, gestisci la scelta
            if (myChoiceIsYes) {
                // L'UTENTE HA DETTO YES
                System.out.println(getCurrentTimestamp()+" - GC: User chose YES. Sending REMATCH YES.");
                if(networkService != null){
                    TextTurno.setText("Sending choice... Waiting...");
                    networkService.sendRematchChoice(true);
                    gameFinishedWaitingRematch.set(true); // Aspetta risposta server
                    System.out.println(getCurrentTimestamp()+" - GC: Sent REMATCH YES, waiting server reply...");
                } else { /* Errore */ gameFinishedWaitingRematch.set(false); if (returnToHomeCallback != null) Platform.runLater(()->returnToHomeCallback.accept("Network Error")); }
            } else {
                // L'UTENTE HA DETTO NO
                System.out.println(getCurrentTimestamp()+" - GC: User chose NO. Sending REMATCH NO.");
                // NON impostare gameFinishedWaitingRematch a false qui, lascia che onRematchDeclined lo faccia
                if(networkService != null){
                    TextTurno.setText("Declining rematch...");
                    networkService.sendRematchChoice(false); // INVIA SOLO IL COMANDO
                    // ---> NESSUNA AZIONE UI QUI <---
                    // Aspetta onRematchDeclined dal server
                } else { /* Errore */ gameFinishedWaitingRematch.set(false); if (returnToHomeCallback != null) Platform.runLater(()->returnToHomeCallback.accept("Network Error")); }
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

    // ---- onRematchDeclined INVARIATO (ora è lui il responsabile finale) ----
    @Override
    public void onRematchDeclined() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onRematchDeclined received");
        boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false); // Resetta il flag e vedi se eravamo in attesa
        gameActive.set(false); // Gioco non attivo in ogni caso

        if (!wasWaiting) {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): WARNING - Received RematchDeclined but not in waiting state (flag already false).");
        }

        // Mostra l'alert e torna alla home
        Platform.runLater(() -> {
            showInfo("Rematch Declined", "Rematch was declined.\nReturning to lobby.");
        });
        if (returnToHomeCallback != null) {
            Platform.runLater(() -> returnToHomeCallback.accept("Rematch declined"));
        } else {
            System.err.println("GC: returnToHomeCallback null after onRematchDeclined!");
        }
    }
    // ---- FINE onRematchDeclined ----

    // ---- onOpponentRematchDecision AGGIORNATO ----
    @Override
    public void onOpponentRematchDecision(boolean opponentAccepted) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onOpponentRematchDecision received: "+opponentAccepted+" *** | waitingRematch="+gameFinishedWaitingRematch.get());

        // Resetta SEMPRE il flag opponentDeclinedWhileWaiting quando arriva una decisione finale
        opponentDeclinedWhileWaiting.set(false);

        if (gameFinishedWaitingRematch.getAndSet(false)) { // Eravamo in attesa
            // L'utente NON ha ancora risposto al proprio popup (caso pareggio), OPPURE
            // L'utente ha PERSO e stava semplicemente aspettando la decisione del vincitore.

            // Distinguiamo: Se NON era pareggio, era il perdente che aspettava.
            // Se era pareggio, può essere che l'utente stesse ancora decidendo.
            boolean isDraw = "DRAW".equalsIgnoreCase(lastGameResult);

            if (!isDraw || !opponentAccepted) { // Se ho perso OPPURE se era pareggio E l'altro ha RIFIUTATO
                // -> In entrambi questi casi, il GIOCO NON RICOMINCIA -> Vai alla lobby.
                System.out.println(getCurrentTimestamp()+" - GC: Opponent decision forces return to lobby (Loser or Draw Rejected).");
                Platform.runLater(() -> {
                    showInfo("Opponent Decision", "Opponent " + (opponentAccepted ? "accepted" : "declined") + " rematch.\nReturning to lobby.");
                    if (returnToHomeCallback != null) { returnToHomeCallback.accept("Opponent decided rematch"); }
                    else { System.err.println("GC: Callback null on OpponentRematchDecision!"); }
                });
            } else {
                // Caso Pareggio e l'avversario ha ACCETTATO.
                // Devo aver detto YES anch'io per arrivare a onGameStart.
                // Qui non faccio nulla, aspetto onGameStart (se ho detto YES)
                // o il mio onRematchDeclined (se ho detto NO).
                System.out.println(getCurrentTimestamp()+" - GC: Opponent ACCEPTED (draw) while waiting local choice (or after local YES). Awaiting further action (GameStart/RematchDeclined).");
                // Rimetto il flag waiting a true perché aspetto GameStart? Sì.
                gameFinishedWaitingRematch.set(true);
            }

        } else {
            // L'utente HA GIA' risposto al proprio popup (o non ne ha ricevuto uno -> era perdente e ha cliccato leave?)
            // Ricevere questo messaggio ORA è comunque ANOMALO perché la logica di REMATCH YES/NO
            // dovrebbe portare a onGameStart, onRematchAccepted, onRematchDeclined, o ritorno immediato.
            System.err.println(getCurrentTimestamp()+" - GC: WARNING - Received OpponentRematchDecision when NOT in waiting state? Opponent Accepted: " + opponentAccepted);
            gameActive.set(false); // Assicura che sia disattivo
            Platform.runLater(() -> {
                showInfo("Opponent Decision", "Opponent " + (opponentAccepted ? "accepted" : "declined") + " rematch (unexpected timing).\nReturning to lobby.");
                if (returnToHomeCallback != null) { returnToHomeCallback.accept("Opponent decided rematch (unexpected)"); }
            });
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