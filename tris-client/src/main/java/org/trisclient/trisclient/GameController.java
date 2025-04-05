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
    // Accesso solo da thread JavaFX dopo setup
    private boolean myTurn = false;
    // Flags atomici per stato condiviso tra network thread e FX thread
    private final AtomicBoolean isSetupComplete = new AtomicBoolean(false);
    private final AtomicBoolean gameActive = new AtomicBoolean(false);

    // Cache interna - ora popolata anche da setupGame con i dati passati da HomePageController
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
        isSetupComplete.set(false); // Assicura sia false all'inizio
        gameActive.set(false);
        cachedBoard = null;
        cachedTurn.set(false);
        myTurn = false;
        // Crea i bottoni della griglia
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
                gridPane.add(btn, j, i); // j è la colonna (indice orizzontale), i è la riga (indice verticale)
            }
        }
        gridPane.setDisable(true); // Inizia disabilitata
        TextTurno.setText("Loading game...");
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): initialize FINISHED");
    }

    // Accetta board e turn iniziali cachati da HomePageController
    public void setupGame(NetworkService serviceInstance, int gameId, char symbol, String opponentName,
                          Consumer<String> returnCallback,
                          String[] initialBoard, boolean initialTurn) { // Nuovi parametri
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): setupGame CALLED. Service: " + serviceInstance + ", GameID: " + gameId + ", Symbol: " + symbol + ", Opponent: " + opponentName);
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): setupGame received initialBoard=" + (initialBoard != null ? Arrays.toString(initialBoard) : "null") + ", initialTurn=" + initialTurn);

        this.networkService = serviceInstance;
        this.gameId = gameId;
        this.mySymbol = symbol;
        this.opponentName = opponentName;
        this.returnToHomeCallback = returnCallback;

        // Resetta stati logici
        this.isSetupComplete.set(false);
        this.gameActive.set(false);
        this.myTurn = false; // Importante resettare myTurn

        // Popola la cache interna con i valori passati (se presenti)
        if (initialBoard != null) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Populating internal cache with initialBoard.");
            this.cachedBoard = initialBoard;
        } else {
            this.cachedBoard = null;
        }
        if (initialTurn) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Populating internal cache with initialTurn=true.");
            this.cachedTurn.set(true);
        } else {
            this.cachedTurn.set(false);
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

        // Pianifica il completamento del setup e l'elaborazione della cache sul thread JavaFX
        Platform.runLater(() -> {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in Platform.runLater): Starting final setup steps.");
            TextTurno.setText("Partita " + gameId + " vs " + opponentName + ". Tu sei " + mySymbol + ". Attendendo...");
            gridPane.setDisable(true); // Assicura che sia disabilitata prima di processare cache/eventi
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in Platform.runLater): Initial text set and grid disabled.");

            isSetupComplete.set(true);
            gameActive.set(true);
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in Platform.runLater): Setup COMPLETE. isSetupComplete=true, gameActive=true");

            // Elabora la cache interna
            processCachedMessages();
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in Platform.runLater): Finished final setup steps.");
        });

        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): setupGame method finished (Platform.runLater scheduled).");
    }

    // Eseguito nel thread JavaFX alla fine di setupGame
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

    // --- Implementazione ServerListener ---

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

    // --- MODIFICATO ---
    // Logica interna di aggiornamento UI board (chiamata solo da thread FX)
    private void handleBoardUpdateInternal(String[] boardCells) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleBoardUpdateInternal processing board | Current myTurn="+myTurn);
        if (boardCells.length != 9) {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): ERROR Invalid board length received: " + boardCells.length);
            return;
        }
        // 1. Update button texts and disable NON-EMPTY buttons
        int emptyCells = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                // Assicurati che buttons[i][j] non sia null (dovrebbe essere creato in initialize)
                if (buttons[i][j] == null) {
                    System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): ERROR Button at ["+i+"]["+j+"] is null!");
                    continue;
                }
                String symbol = boardCells[i * 3 + j];
                boolean isEmpty = "EMPTY".equals(symbol);
                // Imposta il testo del bottone (' ' se vuoto, altrimenti il simbolo)
                buttons[i][j].setText(isEmpty ? " " : symbol);
                // Disabilita il bottone SOLO se NON è vuoto.
                // Non abilitare esplicitamente quelli vuoti qui.
                buttons[i][j].setDisable(!isEmpty);
                if(isEmpty) emptyCells++;
            }
        }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Board UI updated (texts and disabled non-empty). Empty cells: " + emptyCells);

        // 2. Manage grid enabled state based ONLY on myTurn
        // Se NON è il mio turno, disabilita l'intera griglia.
        // Se È il mio turno, lo stato della griglia è già stato gestito da handleYourTurnInternal,
        // quindi non facciamo nulla qui per evitare conflitti.
        if (!myTurn) {
            gridPane.setDisable(true);
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleBoardUpdateInternal - Grid disabled (not my turn).");
        } else {
            // Se è il mio turno, la griglia dovrebbe essere abilitata.
            // I bottoni non vuoti sono stati disabilitati dal ciclo sopra.
            // Non serve toccare lo stato della griglia qui.
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleBoardUpdateInternal - Grid state untouched (my turn).");
        }
        // Log finale dello stato della griglia per debug
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

    // Logica interna gestione turno (chiamata solo da thread FX)
    private void handleYourTurnInternal() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleYourTurnInternal processing | Current myTurn state = "+myTurn);
        if (!gameActive.get()) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleYourTurnInternal - Game not active, ignoring.");
            return;
        }
        myTurn = true; // Imposta che è il mio turno
        TextTurno.setText("Il tuo turno! (" + mySymbol + ")");
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Enabling GridPane and empty buttons for your turn.");
        gridPane.setDisable(false); // Abilita l'intera griglia
        int enabledCount = 0;
        // Itera sui bottoni e abilita SOLO quelli vuoti
        for(int r=0; r<3; r++) {
            for (int c=0; c<3; c++) {
                // Assicurati che buttons[r][c] non sia null
                if (buttons[r][c] == null) {
                    System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): ERROR Button at ["+r+"]["+c+"] is null!");
                    continue;
                }
                boolean isEmpty = buttons[r][c].getText().trim().isEmpty();
                // Abilita il bottone se è vuoto, disabilitalo altrimenti
                buttons[r][c].setDisable(!isEmpty);
                if(isEmpty) {
                    enabledCount++;
                }
            }
        }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Finished enabling buttons. Enabled count: " + enabledCount);
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleYourTurnInternal END | Grid disabled: " + gridPane.isDisabled());
    }

    // Chiamato quando si clicca una cella (dal thread JavaFX)
    private void handleCellClick(int row, int col) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Cell clicked: " + row + "," + col + " (myTurn=" + myTurn + ", gameActive="+gameActive.get()+")");

        // Assicurati che buttons[row][col] non sia null
        if (buttons[row][col] == null) {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): ERROR Clicked button at ["+row+"]["+col+"] is null!");
            return;
        }

        // Controlla se è il mio turno, se il gioco è attivo e se la cella cliccata è vuota
        if (myTurn && gameActive.get() && buttons[row][col].getText().trim().isEmpty()) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Valid click detected.");
            myTurn = false; // Disabilita subito il turno per evitare doppi click

            // Aggiorna immediatamente l'interfaccia (sempre su thread FX)
            buttons[row][col].setText(String.valueOf(mySymbol));
            gridPane.setDisable(true); // Disabilita tutta la griglia mentre si attende la risposta
            TextTurno.setText("Invio mossa ("+row+","+col+"). Attendi...");
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): UI updated for pending move.");

            // Invia il comando MOVE al server
            if(networkService != null) {
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Sending MOVE "+row+" "+col);
                networkService.sendMove(row, col);
            } else {
                System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Cannot send MOVE, networkService is null!");
                showError("Errore di Rete", "Impossibile inviare la mossa.");
                TextTurno.setText("Errore di connessione.");
                // Potrebbe essere necessario un meccanismo per tornare alla home qui
                if (gameActive.compareAndSet(true, false)) { // Marca gioco inattivo
                    if (returnToHomeCallback != null) Platform.runLater(() -> returnToHomeCallback.accept("Network Error"));
                }
            }
        } else {
            // Log perché il click è stato ignorato
            if (!myTurn) System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Click ignored (not my turn).");
            else if (!gameActive.get()) System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Click ignored (game not active).");
            else System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Click ignored (cell not empty).");
        }
    }

    // --- Altri Metodi Listener (GameOver, OpponentLeft, Error, etc.) ---
    // --- E altri metodi (handleLeaveGame, onDisconnected, showInfo, showError) ---
    // --- RESTANO INVARIATI (non li ripeto per brevità, ma assicurati di averli) ---
    // ... (Includi qui il resto del codice di GameController.java che hai già) ...

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
            returnToHomeCallback.accept(message);
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
        String message = "L'avversario ha abbandonato la partita.";
        TextTurno.setText(message);
        showInfo("Avversario Disconnesso", message + "\nTorni alla lobby.");
        if (returnToHomeCallback != null) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Calling returnToHomeCallback.");
            returnToHomeCallback.accept("Opponent left");
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
            myTurn = false; // Assicurati che myTurn sia false
            gridPane.setDisable(true); // Disabilita la griglia
            // Non serve ciclare sui bottoni, l'intera griglia è disabilitata
        } else if (message.contains("Invalid move")) {
            TextTurno.setText("Mossa non valida! Riprova.");
            // Non reimpostare myTurn a true qui, potrebbe causare problemi se l'errore
            // è arrivato per un altro motivo. Lascia che sia il server a ridare il turno.
            // Invece, riabilita la griglia e i bottoni vuoti se myTurn è ancora true.
            if (myTurn) { // Solo se pensavamo fosse ancora il nostro turno
                gridPane.setDisable(false);
                int enabledCount = 0;
                for(int r=0; r<3; r++) {
                    for (int c=0; c<3; c++) {
                        // Assicurati che buttons[r][c] non sia null
                        if (buttons[r][c] == null) continue;
                        boolean isEmpty = buttons[r][c].getText().trim().isEmpty();
                        buttons[r][c].setDisable(!isEmpty);
                        if(isEmpty) enabledCount++;
                    }
                }
                System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Re-enabled grid and " + enabledCount + " empty buttons after invalid move (myTurn was true).");
            } else {
                System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Invalid move error received, but myTurn was already false. Grid remains disabled.");
                gridPane.setDisable(true); // Assicura che sia disabilitata
            }
        } else {
            // Errore generico dal server
            showError("Errore dal Server", message);
            TextTurno.setText("Errore: " + message);
            // Disabilita la griglia per sicurezza in caso di errore sconosciuto
            gridPane.setDisable(true);
            // Considera se tornare alla lobby in caso di errori gravi
            // if (gameActive.compareAndSet(true, false)) {
            //    if (returnToHomeCallback != null) Platform.runLater(() -> returnToHomeCallback.accept("Server Error: "+message));
            // }
        }
    }


    @FXML
    private void handleLeaveGame() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Leave Game button clicked. gameActive="+gameActive.get());
        NetworkService service = this.networkService;

        // Usa compareAndSet per assicurarsi che l'azione venga eseguita solo una volta
        if (gameActive.compareAndSet(true, false)) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Game deactivated due to Leave Game button.");
            myTurn = false;

            gridPane.setDisable(true); // Disabilita UI
            TextTurno.setText("Uscita dalla partita...");

            if (service != null && service.isConnected()) {
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Sending QUIT to server.");
                service.sendQuit();
                // La gestione del ritorno alla home avverrà in onDisconnected
            } else {
                // Se non siamo connessi ma il gioco era segnato come attivo (stato inconsistente?)
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Game was marked active but not connected or service null. Returning home directly.");
                if (returnToHomeCallback != null) {
                    // Chiamata deve essere fatta da Platform.runLater se non siamo sicuri del thread
                    Platform.runLater(() -> returnToHomeCallback.accept("Left game (not connected)"));
                } else {
                    System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): returnToHomeCallback is null when leaving game without connection!");
                }
            }
        } else {
            // Se il gioco era già inattivo, ma siamo ancora su questa schermata
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Leave game clicked but game already inactive.");
            // Forziamo il ritorno a casa se il callback esiste
            if (returnToHomeCallback != null) {
                Platform.runLater(() -> returnToHomeCallback.accept("Left game (already inactive)"));
            }
        }
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onDisconnected event received on FX Thread *** Reason: " + reason + ". Current gameActive=" + gameActive.get());

        // Controlla se il gioco era attivo e marcalo come inattivo
        if (gameActive.compareAndSet(true, false)) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Game deactivated due to Disconnection.");
            myTurn = false;

            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Updating UI for disconnection during game.");
            gridPane.setDisable(true);
            TextTurno.setText("Disconnesso: " + reason);
            // Mostra notifica solo se la disconnessione non è stata intenzionale (QUIT)
            if (!"Disconnected by client".equals(reason)) {
                showInfo("Disconnesso", "Connessione persa durante la partita: " + reason + "\nTorni alla lobby.");
            }
            if (returnToHomeCallback != null) {
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Calling returnToHomeCallback.");
                returnToHomeCallback.accept("Disconnected: " + reason);
            } else {
                System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): returnToHomeCallback is null after disconnection!");
            }
        } else {
            // Il gioco era già inattivo, ma abbiamo ricevuto onDisconnected
            // Potrebbe essere la conferma dopo aver premuto "Leave Game"
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Disconnected received, but game already inactive. Reason: " + reason);
            // Se siamo ancora su questa schermata, torniamo alla home
            if (returnToHomeCallback != null) {
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Calling returnToHomeCallback for inactive game disconnection.");
                // Usiamo Platform.runLater per sicurezza, anche se dovremmo essere già su FX thread
                Platform.runLater(() -> returnToHomeCallback.accept("Disconnected: " + reason));
            }
        }
    }

    // --- Metodi Listener non previsti qui (lascia i log di errore) ---
    @Override public void onConnected() { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received onConnected UNEXPECTEDLY !!!"); }
    @Override public void onNameRequested() { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received NameRequested UNEXPECTEDLY !!!"); }
    @Override public void onNameAccepted() { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received NameAccepted UNEXPECTEDLY !!!"); }
    @Override public void onGamesList(List<NetworkService.GameInfo> games) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received GamesList UNEXPECTEDLY !!!"); }
    @Override public void onGameCreated(int gameId) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received GameCreated UNEXPECTEDLY for game "+gameId+" !!!"); }
    @Override public void onJoinOk(int gameId, char symbol, String opponentName) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received JoinOk UNEXPECTEDLY for game "+gameId+" !!!"); }

    // Gestione più robusta per GameStart ridondante
    @Override public void onGameStart(int gameId, char symbol, String opponentName) {
        System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Received GameStart. Target GameID="+gameId+", My GameID="+this.gameId+". Current gameActive="+gameActive.get()+", myTurn="+myTurn);
        // Ignora se è per la partita corrente e il gioco è già attivo
        if (this.gameId == gameId && gameActive.get()) {
            System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Ignoring redundant GameStart signal for active game.");
        } else {
            // Se arriva per un'altra partita o se il gioco non è attivo, è un errore
            System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received GameStart UNEXPECTEDLY or for wrong/inactive game !!!");
            // Potrebbe indicare uno stato inconsistente, magari forzare ritorno alla lobby?
            // if (returnToHomeCallback != null) Platform.runLater(() -> returnToHomeCallback.accept("Unexpected Game Start Signal"));
        }
    }

    @Override
    public void onMessageReceived(String rawMessage) {
        // Logga messaggi non gestiti che arrivano qui
        System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onMessageReceived (Unhandled by NetworkService parser) on FX Thread *** Message: " + rawMessage);
        // Non mostrare popup all'utente per non interrompere, ma logga l'errore.
    }

    // Metodi ausiliari per mostrare dialoghi (invariati)
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
        alert.showAndWait();
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
        alert.showAndWait();
    }

    // Helper per ottenere lo stage corrente in modo più sicuro
    private Stage getCurrentStage() {
        try {
            if (gridPane != null && gridPane.getScene() != null && gridPane.getScene().getWindow() instanceof Stage) {
                return (Stage) gridPane.getScene().getWindow();
            }
            // Fallback: cerca la prima finestra visibile
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