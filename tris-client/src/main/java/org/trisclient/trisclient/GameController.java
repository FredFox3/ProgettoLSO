package org.trisclient.trisclient;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class GameController implements NetworkService.ServerListener {

    @FXML private Label TextTurno;
    @FXML private GridPane gridPane;

    private NetworkService networkService;
    private int gameId;
    private char mySymbol;
    private String opponentName;
    private Consumer<String> returnToHomeCallback;

    private Button[][] buttons = new Button[3][3];
    private boolean myTurn = false;
    private final AtomicBoolean isSetupComplete = new AtomicBoolean(false);
    private final AtomicBoolean gameActive = new AtomicBoolean(false);

    private volatile String[] cachedBoard = null;
    private final AtomicBoolean cachedTurn = new AtomicBoolean(false);

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    @FXML
    public void initialize() {
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): initialize CALLED");
        isSetupComplete.set(false);
        gameActive.set(false);
        cachedBoard = null;
        cachedTurn.set(false);
        myTurn = false;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Button btn = new Button(" ");
                btn.setMinSize(95, 95);
                btn.setMaxSize(100,100);
                btn.setStyle("-fx-font-size: 36px; -fx-font-weight: bold;");
                final int row = i;
                final int col = j;
                btn.setOnAction(e -> handleCellClick(row, col));
                buttons[i][j] = btn;
                gridPane.add(btn, j, i);
            }
        }
        gridPane.setDisable(true);
        TextTurno.setText("Loading game...");
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): initialize FINISHED");
    }

    public void setupGame(NetworkService serviceInstance, int gameId, char symbol, String opponentName,
                          Consumer<String> returnCallback,
                          String[] initialBoard, boolean initialTurn) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): setupGame CALLED. Service: " + serviceInstance + ", GameID: " + gameId + ", Symbol: " + symbol + ", Opponent: " + opponentName);
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): setupGame received initialBoard=" + (initialBoard != null ? Arrays.toString(initialBoard) : "null") + ", initialTurn=" + initialTurn);

        this.networkService = serviceInstance;
        this.gameId = gameId;
        this.mySymbol = symbol;
        this.opponentName = opponentName;
        this.returnToHomeCallback = returnCallback;

        this.isSetupComplete.set(false);
        this.gameActive.set(false);
        this.myTurn = false;

        if (initialBoard != null) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Populating internal cache with initialBoard.");
            this.cachedBoard = initialBoard;
        } else {
            this.cachedBoard = null;
        }
        this.cachedTurn.set(initialTurn);
        if (initialTurn) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Populating internal cache with initialTurn=true.");
        }

        if (this.networkService == null) {
            System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+") FATAL: NetworkService instance is null in setupGame!");
            Platform.runLater(() -> {
                showError("Errore Critico", "Errore interno, impossibile avviare la partita.");
                if (returnToHomeCallback != null) returnToHomeCallback.accept("Internal Error");
            });
            return;
        }

        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): --- About to set listener ---");
        this.networkService.setServerListener(this);
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): --- Listener set ---");

        Platform.runLater(() -> {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in Platform.runLater): Starting final setup steps.");
            TextTurno.setText("Partita " + gameId + " vs " + opponentName + ". Tu sei " + mySymbol + ". Attendendo...");
            gridPane.setDisable(true);
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in Platform.runLater): Initial text set and grid disabled.");

            isSetupComplete.set(true);
            gameActive.set(true);
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in Platform.runLater): Setup COMPLETE. isSetupComplete=true, gameActive=true");

            processCachedMessages();
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in Platform.runLater): Finished final setup steps.");
        });

        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): setupGame method finished (Platform.runLater scheduled).");
    }

    private void processCachedMessages() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Processing cached messages START");
        boolean processedSomething = false;

        String[] boardToProcess = this.cachedBoard;
        if (boardToProcess != null) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Found cached board: " + Arrays.toString(boardToProcess) + ". Processing...");
            this.cachedBoard = null;
            handleBoardUpdateInternal(boardToProcess);
            processedSomething = true;
        }

        if (this.cachedTurn.getAndSet(false)) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Found cached turn. Processing...");
            handleYourTurnInternal();
            processedSomething = true;
        }

        if (!processedSomething) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): No cached messages to process.");
        }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Processing cached messages END");
    }

    @Override
    public void onBoardUpdate(String[] boardCells) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onBoardUpdate received on FX Thread *** | isSetupComplete=" + isSetupComplete.get() + ", gameActive=" + gameActive.get() + ", Board: " + Arrays.toString(boardCells));

        if (!isSetupComplete.get()) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Caching board update (setup still incomplete).");
            this.cachedBoard = boardCells;
        } else if (!gameActive.get()) {
            System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Ignoring board update as game is not active.");
        } else {
            System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Setup complete and game active. Processing board update directly.");
            handleBoardUpdateInternal(boardCells);
        }
    }

    private void handleBoardUpdateInternal(String[] boardCells) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleBoardUpdateInternal processing board | Current myTurn="+myTurn);
        if (boardCells.length != 9) {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): ERROR Invalid board length received: " + boardCells.length);
            return;
        }
        int emptyCells = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (buttons[i][j] == null) {
                    System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): ERROR Button at ["+i+"]["+j+"] is null!");
                    continue;
                }
                String symbol = boardCells[i * 3 + j];
                boolean isEmpty = "EMPTY".equals(symbol);
                buttons[i][j].setText(isEmpty ? " " : symbol);
                buttons[i][j].setDisable(!isEmpty);
                if(isEmpty) emptyCells++;
            }
        }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Board UI updated (texts and disabled non-empty). Empty cells: " + emptyCells);

        if (!myTurn) {
            gridPane.setDisable(true);
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleBoardUpdateInternal - Grid disabled (not my turn).");
        } else {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleBoardUpdateInternal - Grid state untouched (my turn).");
        }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleBoardUpdateInternal END | Grid disabled: " + gridPane.isDisabled());
    }

    @Override
    public void onYourTurn() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onYourTurn received on FX Thread *** | isSetupComplete=" + isSetupComplete.get() + ", gameActive=" + gameActive.get());

        if (!isSetupComplete.get()) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Caching turn notification (setup incomplete).");
            this.cachedTurn.set(true);
        } else if (!gameActive.get()) {
            System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Ignoring turn notification as game is not active.");
        } else {
            System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Setup complete and game active. Processing turn notification directly.");
            handleYourTurnInternal();
        }
    }

    private void handleYourTurnInternal() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleYourTurnInternal processing | Current myTurn state = "+myTurn);
        if (!gameActive.get()) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleYourTurnInternal - Game not active, ignoring.");
            return;
        }
        myTurn = true;
        TextTurno.setText("Il tuo turno! (" + mySymbol + ")");
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Enabling GridPane and empty buttons for your turn.");
        gridPane.setDisable(false);
        int enabledCount = 0;
        for(int r=0; r<3; r++) {
            for (int c=0; c<3; c++) {
                if (buttons[r][c] == null) {
                    System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): ERROR Button at ["+r+"]["+c+"] is null!");
                    continue;
                }
                boolean isEmpty = buttons[r][c].getText().trim().isEmpty();
                buttons[r][c].setDisable(!isEmpty);
                if(isEmpty) {
                    enabledCount++;
                }
            }
        }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Finished enabling buttons. Enabled count: " + enabledCount);
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleYourTurnInternal END | Grid disabled: " + gridPane.isDisabled());
    }

    private void handleCellClick(int row, int col) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Cell clicked: " + row + "," + col + " (myTurn=" + myTurn + ", gameActive="+gameActive.get()+")");

        if (buttons[row][col] == null) {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): ERROR Clicked button at ["+row+"]["+col+"] is null!");
            return;
        }

        if (myTurn && gameActive.get() && buttons[row][col].getText().trim().isEmpty()) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Valid click detected.");
            myTurn = false;

            buttons[row][col].setText(String.valueOf(mySymbol));
            gridPane.setDisable(true);
            TextTurno.setText("Invio mossa ("+row+","+col+"). Attendi...");
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): UI updated for pending move.");

            if(networkService != null) {
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Sending MOVE "+row+" "+col);
                networkService.sendMove(row, col);
            } else {
                System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Cannot send MOVE, networkService is null!");
                showError("Errore di Rete", "Impossibile inviare la mossa.");
                TextTurno.setText("Errore di connessione.");
                if (gameActive.compareAndSet(true, false)) {
                    if (returnToHomeCallback != null) Platform.runLater(() -> returnToHomeCallback.accept("Network Error"));
                }
            }
        } else {
            if (!myTurn) System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Click ignored (not my turn).");
            else if (!gameActive.get()) System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Click ignored (game not active).");
            else System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Click ignored (cell not empty).");
        }
    }

    @Override
    public void onGameOver(String result) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onGameOver received on FX Thread *** Result: " + result + ". Current gameActive=" + gameActive.get());
        if (!gameActive.compareAndSet(true, false)) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): GameOver received, but game already inactive. Ignoring.");
            return;
        }
        myTurn = false;
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Game deactivated due to GameOver.");

        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Updating UI for GameOver.");
        gridPane.setDisable(true);
        String message;
        switch (result) {
            case "WIN": message = "Hai Vinto!"; break;
            case "LOSE": message = "Hai Perso."; break;
            case "DRAW": message = "Pareggio!"; break;
            default: message = "Partita terminata (" + result + ")";
        }
        TextTurno.setText(message);
        showInfo("Partita Terminata", message + "\nTorni alla lobby.");
        if (returnToHomeCallback != null) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Calling returnToHomeCallback.");
            returnToHomeCallback.accept("Game Over: " + result);
        } else {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): returnToHomeCallback is null after game over!");
        }
    }

    @Override
    public void onOpponentLeft() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onOpponentLeft received on FX Thread *** Current gameActive=" + gameActive.get());
        if (!gameActive.compareAndSet(true, false)) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): OpponentLeft received, but game already inactive. Ignoring.");
            return;
        }
        myTurn = false;
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Game deactivated due to OpponentLeft.");

        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Updating UI for OpponentLeft.");
        gridPane.setDisable(true);
        String message = "Hai vinto! L'avversario ha abbandonato la partita.";
        TextTurno.setText(message);
        showInfo("Partita Terminata", message + "\nTorni alla lobby.");

        if (returnToHomeCallback != null) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Calling returnToHomeCallback.");
            returnToHomeCallback.accept("Opponent left - You Win!");
        } else {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): returnToHomeCallback is null after opponent left!");
        }
    }

    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onError received on FX Thread *** Message: " + message + " | gameActive="+gameActive.get());
        if (!gameActive.get()) {
            System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Error received but game not active. Ignoring. Message: "+message);
            return;
        }

        System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Processing error while game is active.");
        if (message.contains("Not your turn")) {
            TextTurno.setText("Attendi il tuo turno!");
            myTurn = false;
            gridPane.setDisable(true);
        } else if (message.contains("Invalid move")) {
            TextTurno.setText("Mossa non valida! Riprova.");
            if (myTurn) {
                gridPane.setDisable(false);
                int enabledCount = 0;
                for(int r=0; r<3; r++) {
                    for (int c=0; c<3; c++) {
                        if (buttons[r][c] == null) continue;
                        boolean isEmpty = buttons[r][c].getText().trim().isEmpty();
                        buttons[r][c].setDisable(!isEmpty);
                        if(isEmpty) enabledCount++;
                    }
                }
                System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Re-enabled grid and " + enabledCount + " empty buttons after invalid move (myTurn was true).");
            } else {
                System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Invalid move error received, but myTurn was already false. Grid remains disabled.");
                gridPane.setDisable(true);
            }
        } else {
            showError("Errore dal Server", message);
            TextTurno.setText("Errore: " + message);
            gridPane.setDisable(true);
        }
    }

    @FXML
    private void handleLeaveGame() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Leave Game button clicked. gameActive="+gameActive.get());
        NetworkService service = this.networkService;

        if (gameActive.compareAndSet(true, false)) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Game deactivated due to Leave Game button (first click).");
            myTurn = false;

            gridPane.setDisable(true);
            TextTurno.setText("Uscita dalla partita...");

            String alertTitle = "Partita Abbandonata";
            String alertContent = "Hai abbandonato la partita (Hai perso).\nTorni alla lobby.";
            String callbackMsg = "VOLUNTARY_LEAVE";

            if (service != null && service.isConnected()) {
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Sending QUIT to server.");
                service.sendQuit();
            } else {
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Not connected, cannot send QUIT.");
                alertContent = "Hai abbandonato la partita (offline).\nTorni alla lobby.";
            }

            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Showing leave alert and scheduling return home immediately.");
            showInfo(alertTitle, alertContent);

            if (returnToHomeCallback != null) {
                final String finalCallbackMsg = callbackMsg;
                Platform.runLater(() -> returnToHomeCallback.accept(finalCallbackMsg));
            } else {
                System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): returnToHomeCallback is null when leaving game!");
            }

        } else {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Leave game clicked but game already inactive.");
            if (returnToHomeCallback != null) {
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Callback exists, return likely already triggered.");
            } else {
                System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Clicked leave on inactive game AND callback is null!");
            }
        }
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onDisconnected event received on FX Thread *** Reason: " + reason + ". Current gameActive=" + gameActive.get());

        boolean voluntaryLeave = "Disconnected by client".equals(reason);

        if (gameActive.compareAndSet(true, false)) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Game deactivated due to UNEXPECTED Disconnection.");
            myTurn = false;

            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Updating UI for unexpected disconnection during game.");
            gridPane.setDisable(true);
            TextTurno.setText("Disconnesso: " + reason);

            showInfo("Disconnesso", "Connessione persa: " + reason + "\nTorni alla lobby.");

            if (returnToHomeCallback != null) {
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Calling returnToHomeCallback for unexpected disconnect.");
                final String finalCallbackMessage = "Disconnected: " + reason;
                Platform.runLater(() -> returnToHomeCallback.accept(finalCallbackMessage));
            } else {
                System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): returnToHomeCallback is null after unexpected disconnection!");
            }

        } else {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Disconnected received, but game already inactive. Reason: " + reason);
            if (voluntaryLeave) {
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Ignoring voluntary disconnect confirmation (handled by handleLeaveGame).");
            } else {
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Unexpected disconnect received while game inactive. No action taken.");
            }
        }
    }

    @Override public void onConnected() { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received onConnected UNEXPECTEDLY !!!"); }
    @Override public void onNameRequested() { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received NameRequested UNEXPECTEDLY !!!"); }
    @Override public void onNameAccepted() { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received NameAccepted UNEXPECTEDLY !!!"); }
    @Override public void onGamesList(List<NetworkService.GameInfo> games) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received GamesList UNEXPECTEDLY !!!"); }
    @Override public void onGameCreated(int gameId) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received GameCreated UNEXPECTEDLY for game "+gameId+" !!!"); }
    @Override public void onJoinOk(int gameId, char symbol, String opponentName) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received JoinOk UNEXPECTEDLY for game "+gameId+" !!!"); }
    @Override public void onGameStart(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Received GameStart. Target GameID="+gameId+", My GameID="+this.gameId+". Current gameActive="+gameActive.get()+", myTurn="+myTurn);
        if (this.gameId == gameId && gameActive.get()) {
            System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Ignoring redundant GameStart signal for active game.");
        } else {
            System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received GameStart UNEXPECTEDLY or for wrong/inactive game !!!");
        }
    }
    @Override
    public void onMessageReceived(String rawMessage) {
        System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onMessageReceived (Unhandled by NetworkService parser) on FX Thread *** Message: " + rawMessage);
    }

    private void showInfo(String title, String content) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showInfo(title, content));
            return;
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        try {
            Stage owner = getCurrentStage();
            if (owner != null) alert.initOwner(owner);
        } catch (Exception e) { System.err.println("Error getting owner stage for info alert: "+e.getMessage());}
        try {
            Stage owner = getCurrentStage();
            if (owner != null && owner.isShowing()) {
                alert.showAndWait();
            } else {
                System.out.println(getCurrentTimestamp() + " - GC: Suppressing showAndWait for info alert as owner stage is null or not showing.");
            }
        } catch (Exception e) { System.err.println("Error during showAndWait for info alert: "+e.getMessage());}
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
        try {
            Stage owner = getCurrentStage();
            if (owner != null) alert.initOwner(owner);
        } catch (Exception e) { System.err.println("Error getting owner stage for error alert: "+e.getMessage());}
        try {
            Stage owner = getCurrentStage();
            if (owner != null && owner.isShowing()) {
                alert.showAndWait();
            } else {
                System.out.println(getCurrentTimestamp() + " - GC: Suppressing showAndWait for error alert as owner stage is null or not showing.");
            }
        } catch (Exception e) { System.err.println("Error during showAndWait for error alert: "+e.getMessage());}
    }

    private Stage getCurrentStage() {
        try {
            if (gridPane != null && gridPane.getScene() != null && gridPane.getScene().getWindow() instanceof Stage) {
                Stage stage = (Stage) gridPane.getScene().getWindow();
                if (stage.isShowing()) {
                    return stage;
                }
            }
            return Stage.getWindows().stream()
                    .filter(Window::isShowing)
                    .filter(w -> w instanceof Stage)
                    .map(w -> (Stage)w)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            System.err.println("Exception trying to get current stage: " + e.getMessage());
            return null;
        }
    }
}