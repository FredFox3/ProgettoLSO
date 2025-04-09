package org.trisclient.trisclient;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType; // Import ButtonType
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional; // Import Optional
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class GameController implements NetworkService.ServerListener {

    @FXML private Label TextTurno;
    @FXML private GridPane gridPane;
    @FXML private Button buttonLeave; // Assicurati che il tuo FXML abbia questo fx:id

    private NetworkService networkService;
    private int gameId;
    private char mySymbol;
    private String opponentName;
    private Consumer<String> returnToHomeCallback;

    private Button[][] buttons = new Button[3][3];
    private boolean myTurn = false;
    private final AtomicBoolean isSetupComplete = new AtomicBoolean(false);
    // gameActive ora indica se la partita è IN CORSO (prima della fine)
    private final AtomicBoolean gameActive = new AtomicBoolean(false);
    // Nuovo stato per indicare se la partita è finita MA siamo ancora in questa schermata in attesa di decisione/callback rematch
    private final AtomicBoolean gameFinishedWaitingRematch = new AtomicBoolean(false);


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
        gameFinishedWaitingRematch.set(false); // Inizializza nuovo stato
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
        if(buttonLeave!=null) buttonLeave.setDisable(false); // Pulsante leave sempre attivo all'inizio
        TextTurno.setText("Loading game...");
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): initialize FINISHED");
    }

    public void setupGame(NetworkService serviceInstance, int gameId, char symbol, String opponentName,
                          Consumer<String> returnCallback,
                          String[] initialBoard, boolean initialTurn) {
        // ... (Setup iniziale come prima) ...
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): setupGame CALLED. Service: " + serviceInstance + ", GameID: " + gameId + ", Symbol: " + symbol + ", Opponent: " + opponentName);
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): setupGame received initialBoard=" + (initialBoard != null ? Arrays.toString(initialBoard) : "null") + ", initialTurn=" + initialTurn);

        this.networkService = serviceInstance;
        this.gameId = gameId;
        this.mySymbol = symbol;
        this.opponentName = opponentName;
        this.returnToHomeCallback = returnCallback;

        this.isSetupComplete.set(false);
        this.gameActive.set(false); // Start as not active yet
        this.gameFinishedWaitingRematch.set(false);
        this.myTurn = false;

        if (initialBoard != null) this.cachedBoard = initialBoard; else this.cachedBoard = null;
        this.cachedTurn.set(initialTurn);

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
            gridPane.setDisable(true); // Inizia disabilitata
            if (buttonLeave != null) buttonLeave.setDisable(false); // Abilita il leave
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in Platform.runLater): Initial text set and grid disabled.");

            isSetupComplete.set(true);
            gameActive.set(true); // Considera la partita attiva ORA
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in Platform.runLater): Setup COMPLETE. isSetupComplete=true, gameActive=true");

            processCachedMessages(); // Processa eventuali messaggi arrivati durante il setup
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in Platform.runLater): Finished final setup steps.");
        });

        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): setupGame method finished (Platform.runLater scheduled).");
    }

    private void processCachedMessages() {
        // ... (come prima) ...
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Processing cached messages START");
        boolean processedSomething = false;

        // Process Turn first
        if (this.cachedTurn.getAndSet(false)) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Found cached turn. Processing...");
            handleYourTurnInternal();
            processedSomething = true;
        }
        // Process Board second
        String[] boardToProcess = this.cachedBoard;
        if (boardToProcess != null) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Found cached board: " + Arrays.toString(boardToProcess) + ". Processing...");
            this.cachedBoard = null;
            handleBoardUpdateInternal(boardToProcess);
            processedSomething = true;
        }

        if (!processedSomething) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): No cached messages to process.");
        }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Processing cached messages END");
    }

    @Override
    public void onBoardUpdate(String[] boardCells) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onBoardUpdate received *** | isSetupComplete=" + isSetupComplete.get() + ", gameActive=" + gameActive.get() + ", Board: " + Arrays.toString(boardCells));
        if (!isSetupComplete.get()) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Caching board update (setup still incomplete).");
            this.cachedBoard = boardCells;
        } else if (!gameActive.get() && !gameFinishedWaitingRematch.get()) { // Ignora solo se NON attiva E NON in attesa di rematch
            System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Ignoring board update as game is inactive/already finished & decided.");
        } else {
            System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Setup complete. Processing board update directly.");
            handleBoardUpdateInternal(boardCells);
        }
    }

    private void handleBoardUpdateInternal(String[] boardCells) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleBoardUpdateInternal processing board | Current myTurn="+myTurn + ", gameActive="+gameActive.get()+", waitingRematch="+gameFinishedWaitingRematch.get());
        if (boardCells.length != 9) { /*...*/ return; }
        int emptyCellsCount = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (buttons[i][j] == null) { /*...*/ continue; }
                String symbol = boardCells[i * 3 + j];
                boolean isEmpty = "-".equals(symbol);
                buttons[i][j].setText(isEmpty ? " " : symbol);
                // L'abilitazione/disabilitazione del *singolo bottone* dipende solo se è vuoto
                buttons[i][j].setDisable(!isEmpty);
                if(isEmpty) emptyCellsCount++;
            }
        }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Board UI updated. Empty cells: " + emptyCellsCount);

        // Gestione stato GRIGLIA (non dei singoli bottoni)
        // La griglia va disabilitata se il gioco non è attivo OPPURE se è attivo ma non è il mio turno.
        // L'abilitazione viene fatta SOLO da handleYourTurnInternal.
        if (!gameActive.get() || (gameActive.get() && !myTurn) ) {
            gridPane.setDisable(true);
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleBoardUpdateInternal - Grid disabled (game not active OR not my turn).");
        } else {
            // Se il gioco è attivo ED è il mio turno, NON fare nulla qui,
            // lascia che handleYourTurnInternal abiliti la griglia.
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleBoardUpdateInternal - Grid state remains (my turn, enabled by handleYourTurnInternal).");
        }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleBoardUpdateInternal END | Grid disabled: " + gridPane.isDisabled());
    }

    @Override
    public void onYourTurn() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onYourTurn received *** | isSetupComplete=" + isSetupComplete.get() + ", gameActive=" + gameActive.get());
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
        if (!gameActive.get()) { /*...*/ return; }
        myTurn = true;
        TextTurno.setText("Il tuo turno! (" + mySymbol + ")");
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Enabling GridPane and empty buttons for your turn.");

        // Abilita la griglia PRINCIPALE
        gridPane.setDisable(false);

        // Abilita/Disabilita i SINGOLI bottoni
        int enabledCount = 0;
        for(int r=0; r<3; r++) {
            for (int c=0; c<3; c++) {
                if (buttons[r][c] == null) { /*...*/ continue; }
                boolean isEmpty = buttons[r][c].getText().trim().isEmpty();
                buttons[r][c].setDisable(!isEmpty); // Abilita solo se vuoto
                if(isEmpty) enabledCount++;
            }
        }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Finished enabling buttons. Enabled count: " + enabledCount);
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleYourTurnInternal END | Grid disabled: " + gridPane.isDisabled()); // Dovrebbe essere false
    }

    private void handleCellClick(int row, int col) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Cell clicked: " + row + "," + col + " (myTurn=" + myTurn + ", gameActive="+gameActive.get()+")");
        if (buttons[row][col] == null) {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): ERROR Clicked button at ["+row+"]["+col+"] is null!");
            return;
        }

        if (myTurn && gameActive.get() && buttons[row][col].getText().trim().isEmpty()) { // AGGIUNTO CONTROLLO gameActive
            // ... (logica invio mossa come prima) ...
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
                if (gameActive.compareAndSet(true, false)) { // Solo se era attivo
                    gameFinishedWaitingRematch.set(false); // Resetta anche questo flag
                    if (returnToHomeCallback != null) Platform.runLater(() -> returnToHomeCallback.accept("Network Error"));
                }
            }
        } else {
            if (!myTurn) System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Click ignored (not my turn).");
            else if (!gameActive.get()) System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Click ignored (game not active).");
            else System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Click ignored (cell not empty).");
        }
    }

    // --- MODIFICATO: onGameOver ---
    @Override
    public void onGameOver(String result) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onGameOver received *** Result: " + result + ". Current gameActive=" + gameActive.get());
        if (!gameActive.compareAndSet(true, false)) { // Usa compareAndSet per evitare doppie esecuzioni
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): GameOver received, but game already inactive. Ignoring.");
            return;
        }
        // Game è finito, ma siamo in attesa di decisione rematch o notifica avversario
        gameFinishedWaitingRematch.set(true);
        myTurn = false; // Nessuno ha più il turno
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Game DEACTIVATED. Waiting for rematch logic. gameActive=false, waitingRematch=true.");

        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Updating UI for GameOver (waiting rematch decision).");
        gridPane.setDisable(true); // Disabilita griglia definitivamente

        String message;
        boolean iWon = "WIN".equalsIgnoreCase(result);
        boolean isDraw = "DRAW".equalsIgnoreCase(result);

        if (iWon) message = "Hai Vinto!";
        else if (isDraw) message = "Pareggio!";
        else message = "Hai Perso.";

        // Aggiungi stato attesa
        message += "\nIn attesa...";

        TextTurno.setText(message);

        // --- NON tornare alla HOME PAGE qui ---
        // Aspetta onRematchOffer, onOpponentRematchDecision, o disconnessione.

        // Il bottone "Abbandona" rimane attivo per permettere uscita prima della decisione/callback
        if(buttonLeave!=null) buttonLeave.setDisable(false);

    }

    @Override
    public void onOpponentLeft() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onOpponentLeft received *** Current gameActive=" + gameActive.get()+ ", waitingRematch="+gameFinishedWaitingRematch.get());
        boolean wasActive = gameActive.getAndSet(false); // Prendi e setta false
        gameFinishedWaitingRematch.set(false); // Resetta anche questo
        myTurn = false;

        if(!wasActive) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): OpponentLeft received, but game already inactive. Ignoring UI updates, returning home.");
        } else {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Game deactivated due to OpponentLeft.");
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Updating UI for OpponentLeft.");
            gridPane.setDisable(true);
            String message = "Hai vinto! L'avversario ha abbandonato la partita.";
            TextTurno.setText(message);
            showInfo("Partita Terminata", message + "\nTorni alla lobby.");
        }


        if (returnToHomeCallback != null) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Calling returnToHomeCallback for OpponentLeft.");
            Platform.runLater(() -> returnToHomeCallback.accept("Opponent left - You Win!"));
        } else {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): returnToHomeCallback is null after opponent left!");
        }
    }

    // --- NUOVO: onRematchOffer ---
    @Override
    public void onRematchOffer() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onRematchOffer received ***");
        // Verifica se la partita era effettivamente finita (waitingRematch=true)
        if (!gameFinishedWaitingRematch.get()) {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Received RematchOffer but game wasn't in finished state. Ignoring.");
            return;
        }

        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Rivincita?");
            alert.setHeaderText("Partita finita. Hai Vinto!");
            alert.setContentText("Vuoi creare una nuova partita come proprietario?");

            ButtonType buttonTypeYes = new ButtonType("Sì, rigioca");
            ButtonType buttonTypeNo = new ButtonType("No, torna alla lobby");
            alert.getButtonTypes().setAll(buttonTypeYes, buttonTypeNo);

            try {
                Stage owner = getCurrentStage();
                if(owner != null) alert.initOwner(owner);
            } catch(Exception e) { System.err.println("Error setting owner for rematch offer alert: "+e.getMessage());}

            Optional<ButtonType> result = alert.showAndWait();
            boolean choice = result.isPresent() && result.get() == buttonTypeYes;

            if(networkService != null){
                TextTurno.setText("Invio scelta rivincita...");
                networkService.sendRematchChoice(choice);
            } else {
                System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Cannot send RematchChoice, NetworkService is null!");
                showError("Errore Rete", "Impossibile inviare scelta.");
                // Torna alla home se c'è errore
                gameFinishedWaitingRematch.set(false);
                if (returnToHomeCallback != null) returnToHomeCallback.accept("Network Error");
            }
        });
    }

    // --- NUOVO: onRematchAccepted ---
    @Override
    public void onRematchAccepted(int receivedGameId) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onRematchAccepted received for game "+receivedGameId+" *** (Only expected for winner rematch)");

        // Questa callback ora viene chiamata SOLO se eri il vincitore e hai accettato,
        // risultando nello stato WAITING sul server. Se eri in pareggio e hai accettato,
        // riceverai onGameStart (se l'altro accetta) o onRematchDeclined (se l'altro rifiuta).
        if (!gameFinishedWaitingRematch.getAndSet(false)) { // Verifica stato precedente
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): WARNING - Received RematchAccepted but wasn't in waiting state. Likely win scenario OK. Returning home.");
            // Non è un errore grave se arriva dopo una vittoria, procedi a tornare alla home
        } else {
            Platform.runLater(() -> {
                showInfo("Rematch Accepted (Waiting)", "Ok, sei in attesa di un nuovo avversario per la partita " + receivedGameId + ".\nTorni alla lobby.");
            });
        }

        // Torna alla home
        if (returnToHomeCallback != null) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Returning home after RematchAccepted (winner becomes host).");
            Platform.runLater(() -> returnToHomeCallback.accept("Rematch accepted, waiting"));
        } else {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): returnToHomeCallback is null after RematchAccepted!");
        }
    }

    // --- NUOVO: onRematchDeclined ---
    @Override
    public void onRematchDeclined() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onRematchDeclined received ***");
        if (!gameFinishedWaitingRematch.getAndSet(false)) { // Prendi e resetta il flag
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Received RematchDeclined but not in waiting state. Returning home anyway.");
        } else {
            Platform.runLater(() -> {
                showInfo("Rivincita Rifiutata", "Rivincita rifiutata. Torni alla lobby.");
            });
        }

        // Torna alla home
        if (returnToHomeCallback != null) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Returning home after RematchDeclined.");
            Platform.runLater(() -> returnToHomeCallback.accept("Rematch declined"));
        } else {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): returnToHomeCallback is null after RematchDeclined!");
        }
    }

    // --- NUOVO: onOpponentRematchDecision ---
    @Override
    public void onOpponentRematchDecision(boolean opponentAccepted) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onOpponentRematchDecision received: "+opponentAccepted+" ***");
        if (!gameFinishedWaitingRematch.getAndSet(false)) { // Prendi e resetta il flag
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Received OpponentRematchDecision but not in waiting state. Returning home anyway.");
        } else {
            Platform.runLater(() -> {
                showInfo("Decisione Avversario", "L'avversario ha " + (opponentAccepted ? "accettato" : "rifiutato") + " la rivincita.\nTorni alla lobby.");
            });
        }
        // Torna alla home
        if (returnToHomeCallback != null) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Returning home after OpponentRematchDecision.");
            Platform.runLater(() -> returnToHomeCallback.accept("Opponent decided rematch"));
        } else {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): returnToHomeCallback is null after OpponentRematchDecision!");
        }
    }


    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onError received *** Message: " + message + " | gameActive="+gameActive.get()+ ", waitingRematch="+gameFinishedWaitingRematch.get());

        if (!gameActive.get() && !gameFinishedWaitingRematch.get()) {
            System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Error received but game not active and not waiting rematch. Ignoring. Message: "+message);
            return;
        }

        Platform.runLater(()-> { // Esegui tutto nel thread UI
            if (message.contains("Not your turn")) {
                TextTurno.setText("Attendi il tuo turno!");
                myTurn = false;
                gridPane.setDisable(true);
            } else if (message.contains("Invalid move")) {
                TextTurno.setText("Mossa non valida! ("+ message +") Riprova.");
                if (myTurn && gameActive.get()) { // Riabilita solo se è il mio turno e il gioco è attivo
                    gridPane.setDisable(false);
                    // Riabilita bottoni vuoti
                    int enabledCount = 0;
                    for(int r=0; r<3; r++) {
                        for (int c=0; c<3; c++) {
                            if (buttons[r][c] == null) continue;
                            boolean isEmpty = buttons[r][c].getText().trim().isEmpty();
                            buttons[r][c].setDisable(!isEmpty);
                            if(isEmpty) enabledCount++;
                        }
                    }
                    System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Re-enabled grid and " + enabledCount + " empty buttons after invalid move.");
                } else {
                    gridPane.setDisable(true);
                    System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Invalid move error received, but myTurn was false or game inactive. Grid remains disabled.");
                }
            } else {
                // Errore generico: mostralo e torna alla lobby
                showError("Errore dal Server", message);
                TextTurno.setText("Errore: " + message);
                gridPane.setDisable(true);

                boolean wasActive = gameActive.getAndSet(false);
                gameFinishedWaitingRematch.set(false); // Resetta anche questo
                myTurn = false;

                if(wasActive) { // Se il gioco era attivo, significa errore grave
                    if (returnToHomeCallback != null) {
                        System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Returning home due to critical error: " + message);
                        returnToHomeCallback.accept("Server Error");
                    }
                } else { // Se il gioco era finito (waitingRematch=true), l'errore è post-partita
                    System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Post-game error received: " + message);
                    // Forse è un errore legato al rematch (es. non sei il vincitore). Torna alla lobby.
                    if (returnToHomeCallback != null) {
                        returnToHomeCallback.accept("Error after game");
                    }
                }

            }
        });

    }


    // --- MODIFICATO: handleLeaveGame ---
    @FXML
    private void handleLeaveGame() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Leave Game clicked. gameActive="+gameActive.get()+", waitingRematch="+gameFinishedWaitingRematch.get());
        NetworkService service = this.networkService;

        boolean wasActive = gameActive.getAndSet(false);
        boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false);
        myTurn = false; // Sicuramente non è più il mio turno

        gridPane.setDisable(true);
        if(buttonLeave != null) buttonLeave.setDisable(true); // Disabilita il bottone leave per evitare doppio click
        TextTurno.setText("Uscita dalla partita...");

        String alertTitle = "Partita Abbandonata";
        String alertContent;
        String callbackMsg;
        boolean sendQuitToServer = false;

        if(wasActive){
            // Uscita durante la partita attiva
            alertContent = "Hai abbandonato la partita (Hai perso).\nTorni alla lobby.";
            callbackMsg = "VOLUNTARY_LEAVE";
            sendQuitToServer = true;
        } else if(wasWaiting) {
            // Uscita dopo la fine ma prima della decisione rematch
            alertContent = "Hai lasciato dopo la fine della partita.\nTorni alla lobby.";
            callbackMsg = "LEFT_AFTER_GAME";
            // Invia QUIT al server (che gestirà se eri vincitore o perdente)
            // O potremmo inviare REMATCH NO se sappiamo di essere il vincitore?
            // QUIT è più sicuro e generale.
            sendQuitToServer = true;
        } else {
            // Uscita quando il gioco era già inattivo e non in attesa (stato inconsistente o già in uscita?)
            alertContent = "Stato partita non attivo. Torni alla lobby.";
            callbackMsg = "LEFT_INACTIVE_STATE";
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Leave game clicked but game already inactive/finished. Not sending QUIT.");
        }


        if (sendQuitToServer && service != null && service.isConnected()) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Sending QUIT to server.");
            service.sendQuit();
        } else if(sendQuitToServer) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Wanted to send QUIT, but not connected.");
        }

        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Showing leave alert.");
        // Mostra l'alert MA non usare showAndWait per non bloccare il ritorno alla home
        //showInfoNoWait(alertTitle, alertContent); // Idealmente avremmo una versione non bloccante

        // Chiama subito il ritorno alla home
        if (returnToHomeCallback != null) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Calling returnToHomeCallback with message: " + callbackMsg);
            final String finalCallbackMsg = callbackMsg;
            // Usa runLater per assicurare che l'UI venga aggiornata prima del cambio scena
            Platform.runLater(() -> {
                showInfo(alertTitle, alertContent); // Mostra l'alert nel thread UI PRIMA di cambiare
                returnToHomeCallback.accept(finalCallbackMsg);
            });

        } else {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): returnToHomeCallback is null when leaving game!");
            // Prova a mostrare l'errore comunque
            showError("Errore Uscita", "Impossibile tornare alla home.");
        }
    }


    // --- MODIFICATO: onDisconnected ---
    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onDisconnected event received *** Reason: " + reason + ". gameActive=" + gameActive.get() + ", waitingRematch=" + gameFinishedWaitingRematch.get());

        boolean wasActive = gameActive.getAndSet(false);
        boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false);
        myTurn = false;

        // Chiama returnToHomeCallback indipendentemente dallo stato precedente
        // L' HomePageController gestirà il messaggio di disconnessione
        if (returnToHomeCallback != null) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Calling returnToHomeCallback due to disconnection.");
            final String finalCallbackMessage = "Disconnected: " + reason;
            Platform.runLater(() -> {
                // Mostra un alert informativo SOLO se non era una disconnessione volontaria
                if (!"Disconnected by client".equals(reason)) {
                    showInfo("Disconnesso", "Connessione persa: " + reason + "\nTorni alla lobby.");
                }
                returnToHomeCallback.accept(finalCallbackMessage);
            });
        } else {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): returnToHomeCallback is null after disconnection!");
            // Mostra l'errore se il callback non è disponibile
            showError("Disconnesso", "Connessione persa: "+ reason+"\nErrore: Impossibile tornare alla lobby.");
        }
        // Disabilita UI in ogni caso
        gridPane.setDisable(true);
        if(buttonLeave != null) buttonLeave.setDisable(true);
        TextTurno.setText("Disconnesso");

    }

    // --- Metodi Non Implementati (Previsto comportamento: loggare errore) ---
    @Override public void onConnected() { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received onConnected UNEXPECTEDLY !!!"); }
    @Override public void onNameRequested() { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received NameRequested UNEXPECTEDLY !!!"); }
    @Override public void onNameAccepted() { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received NameAccepted UNEXPECTEDLY !!!"); }
    @Override public void onGamesList(List<NetworkService.GameInfo> games) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received GamesList UNEXPECTEDLY !!!"); }
    @Override public void onGameCreated(int gameId) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received GameCreated UNEXPECTEDLY for game "+gameId+" !!!"); }
    @Override public void onJoinRequestSent(int gameId) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received onJoinRequestSent UNEXPECTEDLY !!!"); }
    @Override public void onJoinRequestReceived(String requesterName) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received onJoinRequestReceived UNEXPECTEDLY !!!"); }
    @Override public void onJoinAccepted(int gameId, char symbol, String opponentName) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received JoinAccepted UNEXPECTEDLY for game "+gameId+" !!!"); }
    @Override public void onJoinRejected(int gameId, String creatorName) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received JoinRejected UNEXPECTEDLY for game "+gameId+" !!!"); }
    @Override public void onActionConfirmed(String message) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received ActionConfirmed UNEXPECTEDLY !!! Message: "+message); }
    @Override
    public void onGameStart(int recGameId, char recSymbol, String recOpponentName) {
        System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): *** onGameStart received *** | Target ID="+recGameId+", My ID="+this.gameId+", gameActive="+gameActive.get()+", waitingRematch="+gameFinishedWaitingRematch.get());

        if(this.gameId != recGameId){
            System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): ERROR Received GameStart for wrong game ID!");
            // Potrebbe essere un vecchio messaggio, ignoriamo? O torniamo alla home?
            return;
        }

        if (gameFinishedWaitingRematch.get()) {
            // --- CASO DRAW REMATCH ACCEPTED BY BOTH ---
            System.out.println(getCurrentTimestamp() + " - GC (" + this.hashCode() + "): Received GameStart while waiting after draw -> REMATCH STARTING!");
            gameFinishedWaitingRematch.set(false); // Non siamo più in attesa della decisione
            gameActive.set(true); // La nuova partita è attiva
            // Aggiorna UI per nuova partita (ma mantieni simbolo/avversario?)
            // I simboli potrebbero invertirsi? Il server attuale non lo fa (P1 X, P2 O).
            this.mySymbol = recSymbol; // Aggiorna per sicurezza
            this.opponentName = recOpponentName; // Aggiorna per sicurezza

            Platform.runLater(() -> {
                TextTurno.setText("Rematch! Partita vs " + this.opponentName + " (Sei " + this.mySymbol + "). In attesa...");
                // Resetta la board UI
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        if(buttons[r][c] != null) {
                            buttons[r][c].setText(" ");
                            // Non impostare lo stato disabilitato qui, lo gestiranno
                            // handleBoardUpdateInternal e handleYourTurnInternal
                            // buttons[r][c].setDisable(true); // RIMUOVERE/COMMENTARE
                        }
                    }
                }
                // Non disabilitare la griglia qui, ci penserà il primo handleBoardUpdateInternal
                // gridPane.setDisable(true); // RIMUOVERE/COMMENTARE
                if (buttonLeave != null) buttonLeave.setDisable(false);
                System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): UI text reset for draw rematch (grid/button state handled by subsequent events).");
            });

        } else if (isSetupComplete.get() && gameActive.get()){
            System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Ignoring redundant GameStart signal for active game.");
        } else if (!isSetupComplete.get()){
            System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received GameStart BEFORE setup complete ??? !!!");
            // Logica di cache / setup dovrebbe gestire questo
        } else {
            System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received GameStart in unexpected state (setup complete, but not active and not waiting rematch?) !!!");
            // Potrebbe succedere se l'utente ha premuto leave? O errore di stato?
            // Torniamo alla home per sicurezza.
            if(returnToHomeCallback != null) Platform.runLater(() -> returnToHomeCallback.accept("State Error"));
        }
    }
    @Override public void onMessageReceived(String rawMessage) { System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onMessageReceived (Unhandled) *** Message: " + rawMessage); }


    // --- Metodi Helper UI (showInfo, showError, getCurrentStage) ---
    private void showInfo(String title, String content) {
        showAlert(Alert.AlertType.INFORMATION, title, content);
    }

    private void showError(String title, String content) {
        showAlert(Alert.AlertType.ERROR, title, content);
    }

    private void showAlert(Alert.AlertType type, String title, String content){
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showAlert(type, title, content));
            return;
        }
        try {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);

            Stage owner = getCurrentStage();
            if(owner != null && owner.isShowing()){
                alert.initOwner(owner);
                alert.showAndWait(); // Mostra bloccante solo se abbiamo un owner valido e visibile
            } else {
                System.out.println(getCurrentTimestamp()+" - GC: Suppressing alert '"+title+"' as owner stage is "+(owner==null?"null":"not showing")+".");
                // alert.show(); // Potremmo mostrare non modale se non c'è owner? Meglio evitare.
            }

        } catch(IllegalStateException e){
            System.err.println(getCurrentTimestamp()+" - GC: Error showing alert (likely Toolkit not initialized or on wrong thread?): "+e.getMessage());
            // Non tentare Platform.runLater qui se l'errore è IllegalStateException
        } catch (Exception e) {
            System.err.println(getCurrentTimestamp()+" - GC: Generic error showing alert '"+title+"': "+e.getMessage());
        }
    }

    private Stage getCurrentStage() {
        // ... (come prima) ...
        try {
            if (gridPane != null && gridPane.getScene() != null && gridPane.getScene().getWindow() instanceof Stage) {
                Stage stage = (Stage) gridPane.getScene().getWindow();
                // Verifica aggiuntiva che sia ancora valida e mostrata
                if (stage != null && stage.isShowing()) {
                    return stage;
                }
            }
            // Fallback se il metodo sopra fallisce
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
} // Fine classe GameController