package org.trisclient.trisclient;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage; // Importa Stage

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean; // Usa AtomicBoolean per thread-safety
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
    // Rimosso volatile, myTurn sarà accessato solo dal thread JavaFX dopo setup
    private boolean myTurn = false;
    // Usa AtomicBoolean per questi flag modificati potenzialmente prima/durante setup da thread diversi
    private final AtomicBoolean isSetupComplete = new AtomicBoolean(false);
    private final AtomicBoolean gameActive = new AtomicBoolean(false);

    // Cache per messaggi anticipati
    private volatile String[] cachedBoard = null; // Volatile perché scritto da Network thread, letto da setupGame
    private final AtomicBoolean cachedTurn = new AtomicBoolean(false); // AtomicBoolean è thread-safe

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
                gridPane.add(btn, j, i);
            }
        }
        gridPane.setDisable(true);
        TextTurno.setText("Loading game...");
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): initialize FINISHED");
    }

    @Override
    public void onMessageReceived(String rawMessage) {
        // Questo metodo viene chiamato se NetworkService riceve un messaggio
        // che non sa come parsare o che non corrisponde a nessun comando noto.
        // In genere, durante il gioco, non dovremmo ricevere messaggi sconosciuti.
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onMessageReceived (Unhandled): " + rawMessage);
        // Potresti decidere di mostrare un errore o loggare in modo più dettagliato se necessario.
    }

    // METODO MANCANTE AGGIUNTO QUI:
    @Override
    public void onConnected() {
        // Questo di solito non viene chiamato mentre siamo già in gioco,
        // ma l'implementazione è richiesta dall'interfaccia.
        // Potrebbe essere chiamato se c'è una riconnessione gestita da NetworkService,
        // anche se la logica attuale non lo prevede esplicitamente.
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onConnected received (Listener registered or reconnected?).");
        // Potresti voler verificare lo stato del gioco qui se gestisci riconnessioni
    }

    public void setupGame(NetworkService serviceInstance, int gameId, char symbol, String opponentName, Consumer<String> returnCallback) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): setupGame CALLED. Service: " + serviceInstance + ", GameID: " + gameId + ", Symbol: " + symbol);
        this.networkService = serviceInstance;
        this.gameId = gameId;
        this.mySymbol = symbol;
        this.opponentName = opponentName;
        this.returnToHomeCallback = returnCallback;
        // Resetta stati all'inizio di setup
        this.isSetupComplete.set(false);
        this.gameActive.set(false); // Il gioco non è attivo FINCHÉ setup non è completo
        this.myTurn = false;
        // Non resettare cache qui, potrebbe essere stata popolata prima!

        if (this.networkService == null) {
            System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+") FATAL: NetworkService instance is null in setupGame!");
            // Gestione errore... (omessa per brevità)
            return;
        }

        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): --- About to set listener ---");
        this.networkService.setServerListener(this); // Imposta il listener
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): --- Listener set ---");

        // Imposta UI iniziale nel thread JavaFX
        Platform.runLater(() -> {
            TextTurno.setText("Partita " + gameId + " vs " + opponentName + ". Tu sei " + mySymbol + ". Attendendo...");
            gridPane.setDisable(true); // Assicura sia disabilitata
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in Platform.runLater): Initial text set and grid disabled.");

            // Marca setup completo e gioco attivo ALLA FINE del setup sul thread FX
            isSetupComplete.set(true);
            gameActive.set(true);
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in Platform.runLater): Setup COMPLETE. isSetupComplete=true, gameActive=true");

            // Processa messaggi cachati ORA che siamo pronti
            processCachedMessages();
        });

        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): setupGame method finished (Platform.runLater scheduled).");
    }

    // Eseguito nel thread JavaFX alla fine di setupGame
    private void processCachedMessages() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Processing cached messages START");
        boolean processedSomething = false;
        // Processa prima la board cachata, se esiste
        String[] boardToProcess = this.cachedBoard; // Leggi volatile una volta
        if (boardToProcess != null) {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Found cached board. Processing...");
            this.cachedBoard = null; // Invalida cache
            handleBoardUpdateInternal(boardToProcess); // Chiama la logica interna di aggiornamento
            processedSomething = true;
        }
        // Processa il turno cachato, se esiste
        if (this.cachedTurn.getAndSet(false)) { // Leggi e resetta atomicamente
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Found cached turn. Processing...");
            handleYourTurnInternal(); // Chiama la logica interna di gestione turno
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
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onBoardUpdate ENTRY *** | isSetupComplete=" + isSetupComplete.get() + ", gameActive=" + gameActive.get());
        if (!isSetupComplete.get()) { // Controlla se il setup è completo
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Caching board update (setup incomplete).");
            this.cachedBoard = boardCells; // Cache it (volatile write)
            return;
        }
        // Se setup è completo, processa nel thread JavaFX
        Platform.runLater(() -> {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onBoardUpdate (runLater) START processing board | gameActive="+gameActive.get());
            if (!gameActive.get()) { // Ricontrolla attività nel thread FX
                System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Ignoring board update (runLater) as game is not active.");
                return;
            }
            handleBoardUpdateInternal(boardCells); // Chiama la logica effettiva
        });
    }

    // Logica interna di aggiornamento UI board (da chiamare da thread FX)
    private void handleBoardUpdateInternal(String[] boardCells) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleBoardUpdateInternal processing board | myTurn="+myTurn);
        if (boardCells.length != 9) {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): ERROR Invalid board length: " + boardCells.length);
            return;
        }
        int emptyCells = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                String symbol = boardCells[i * 3 + j];
                boolean isEmpty = "EMPTY".equals(symbol);
                buttons[i][j].setText(isEmpty ? " " : symbol);
                // I bottoni singoli vengono abilitati/disabilitati da onYourTurn o handleCellClick
                // buttons[i][j].setDisable(!isEmpty); // Rimuovi questa riga forse?
                if(isEmpty) emptyCells++;
            }
        }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Board UI updated. Empty cells: " + emptyCells);
        // Aggiorna lo stato della griglia generale in base a myTurn
        gridPane.setDisable(!myTurn);
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleBoardUpdateInternal END | Grid disabled: " + (!myTurn));
    }


    @Override
    public void onYourTurn() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onYourTurn ENTRY *** | isSetupComplete=" + isSetupComplete.get() + ", gameActive=" + gameActive.get());
        if (!isSetupComplete.get()) { // Controlla se il setup è completo
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Caching turn notification (setup incomplete).");
            this.cachedTurn.set(true); // Cache it (atomic set)
            return;
        }
        // Se setup è completo, processa nel thread JavaFX
        Platform.runLater(() -> {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onYourTurn (runLater) START processing | gameActive="+gameActive.get());
            if (!gameActive.get()) { // Ricontrolla attività nel thread FX
                System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Ignoring turn notification (runLater) as game is not active.");
                return;
            }
            handleYourTurnInternal(); // Chiama la logica effettiva
        });
    }

    // Logica interna gestione turno (da chiamare da thread FX)
    private void handleYourTurnInternal() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleYourTurnInternal processing | Current myTurn state = "+myTurn);
        myTurn = true;
        TextTurno.setText("Il tuo turno! (" + mySymbol + ")");
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Enabling GridPane and buttons for your turn.");
        gridPane.setDisable(false); // Abilita griglia
        int enabledCount = 0;
        for(int r=0; r<3; r++) {
            for (int c=0; c<3; c++) {
                boolean isEmpty = buttons[r][c].getText().trim().isEmpty();
                buttons[r][c].setDisable(!isEmpty); // Abilita solo se vuota
                if(isEmpty) {
                    enabledCount++;
                }
            }
        }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Finished enabling buttons. Enabled count: " + enabledCount);
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): handleYourTurnInternal END | Grid disabled: " + gridPane.isDisabled());
    }


    private void handleCellClick(int row, int col) {
        // Usa gameActive.get() per leggere lo stato atomico
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Cell clicked: " + row + " " + col + " (myTurn=" + myTurn + ", gameActive="+gameActive.get()+")");
        if (myTurn && gameActive.get() && buttons[row][col].getText().trim().isEmpty()) {
            myTurn = false; // Diventa falso TEMPORANEAMENTE dopo aver cliccato
            Platform.runLater(() -> {
                gridPane.setDisable(true); // Disabilita tutta la griglia mentre si invia
                TextTurno.setText("Invio mossa ("+row+","+col+"). Attendi...");
            });
            if(networkService != null) {
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Sending MOVE "+row+" "+col);
                networkService.sendMove(row, col);
            } else {
                // Gestione errore networkService null...
            }
        } else {
            // Log per i casi di click ignorato...
        }
    }

    // --- Altri Metodi Listener (onGameOver, onOpponentLeft, onError, etc.) ---
    // Devono anche controllare gameActive.get() e usare Platform.runLater
    // e impostare gameActive.set(false) quando la partita finisce.

    @Override
    public void onGameOver(String result) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onGameOver! Result: " + result + ". Current gameActive=" + gameActive.get());
        if (!gameActive.compareAndSet(true, false)) { // Atomicamente imposta a false solo se era true
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): GameOver received, but game already inactive.");
            return;
        }
        myTurn = false; // Resetta anche il turno

        Platform.runLater(() -> {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in runLater): Updating UI for GameOver.");
            gridPane.setDisable(true);
            // ... (logica messaggio vittoria/sconfitta) ...
            String message = "..."; // Calcola messaggio
            TextTurno.setText(message);
            showInfo("Partita Terminata", message + "\nTorni alla lobby.");
            if (returnToHomeCallback != null) {
                returnToHomeCallback.accept(message);
            }
        });
    }

    @Override
    public void onOpponentLeft() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onOpponentLeft received. Current gameActive=" + gameActive.get());
        if (!gameActive.compareAndSet(true, false)) { // Atomicamente imposta a false solo se era true
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): OpponentLeft received, but game already inactive.");
            return;
        }
        myTurn = false;

        Platform.runLater(() -> {
            gridPane.setDisable(true);
            // ... (logica messaggio opponent left) ...
            TextTurno.setText("L'avversario ha abbandonato.");
            showInfo("Avversario Disconnesso", "...");
            if (returnToHomeCallback != null) {
                returnToHomeCallback.accept("Opponent left");
            }
        });
    }

    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onError received: " + message + " | gameActive="+gameActive.get());
        // Processa solo se il gioco è attivo
        if (!gameActive.get()) {
            System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Error received but game not active. Ignoring.");
            return;
        }
        Platform.runLater(() -> {
            // Logica gestione errore (Not your turn, Invalid move, etc.)
            // ...
            if (message.contains("Not your turn")) {
                TextTurno.setText("Attendi il tuo turno!");
                myTurn = false;
                gridPane.setDisable(true);
            } else if (message.contains("Invalid move")) {
                TextTurno.setText("Mossa non valida! Riprova.");
                // Qui dobbiamo riabilitare il turno se era stato messo a false in handleCellClick
                myTurn = true; // Ri-abilita il turno
                gridPane.setDisable(false);
                // Riabilita solo le celle vuote
                for(int r=0; r<3; r++) {
                    for (int c=0; c<3; c++) {
                        buttons[r][c].setDisable(!buttons[r][c].getText().trim().isEmpty());
                    }
                }
            } else {
                showError("Errore dal Server", message);
                TextTurno.setText("Errore: " + message);
            }
        });
    }


    @FXML
    private void handleLeaveGame() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Leave Game button clicked. gameActive="+gameActive.get());
        NetworkService service = this.networkService;

        // Usa compareAndSet per assicurarti di inviare QUIT solo una volta se il gioco è attivo
        if (gameActive.compareAndSet(true, false)) {
            myTurn = false; // Imposta subito a false
            if (service != null && service.isConnected()) {
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Sending QUIT to server.");
                service.sendQuit();
                Platform.runLater(() -> {
                    gridPane.setDisable(true);
                    TextTurno.setText("Leaving game...");
                });
                // NON chiamare il callback qui, aspetta onDisconnected
            } else {
                // Se non connesso, ma era attivo (improbabile), torna subito
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Game was active but not connected. Returning home directly.");
                Platform.runLater(() -> {
                    if (returnToHomeCallback != null) {
                        returnToHomeCallback.accept("Left game (not connected)");
                    }
                });
            }
        } else {
            // Se il gioco non era attivo (o il click è doppio), non fare nulla o torna alla home se serve
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Leave game clicked but game already inactive.");
            // Potresti voler tornare alla home anche in questo caso se l'utente è bloccato
            Platform.runLater(() -> {
                if (returnToHomeCallback != null) {
                    // Verifica se siamo effettivamente ancora nella schermata di gioco
                    // Se sì, forza il ritorno
                    if (gridPane != null && gridPane.getScene() != null && gridPane.getScene().getRoot() == gridPane.getParent()) { // Controllo approssimativo
                        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Forcing return home as game is inactive but still visible.");
                        returnToHomeCallback.accept("Left game (already inactive)");
                    }
                }
            });
        }
    }

    // --- Metodi Listener non usati qui (onConnected, onDisconnected, etc.) ---
    // Vengono gestiti principalmente da HomePageController
    // Ma aggiungiamo onDisconnected per sicurezza, se avviene mentre siamo qui
    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onDisconnected event received in GAME SCREEN. Reason: " + reason + ". Current gameActive=" + gameActive.get());
        // Se il gioco era attivo, gestisci come se l'avversario fosse uscito o ci fosse un errore
        if (gameActive.compareAndSet(true, false)) { // Atomicamente imposta a false solo se era true
            myTurn = false;
            Platform.runLater(() -> {
                System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+") (in runLater): Updating UI for disconnection during game.");
                gridPane.setDisable(true);
                TextTurno.setText("Disconnesso: " + reason);
                showInfo("Disconnesso", "Connessione persa durante la partita: " + reason + "\nTorni alla lobby.");
                if (returnToHomeCallback != null) {
                    returnToHomeCallback.accept("Disconnected during game: " + reason);
                }
            });
        } else {
            System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Disconnected received, but game already inactive.");
        }
    }

    // --- Metodi non previsti qui ---
    @Override public void onNameRequested() { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received NameRequested UNEXPECTEDLY !!!"); }
    @Override public void onNameAccepted() { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received NameAccepted UNEXPECTEDLY !!!"); }
    @Override public void onGamesList(List<NetworkService.GameInfo> games) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received GamesList UNEXPECTEDLY !!!"); }
    @Override public void onGameCreated(int gameId) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received GameCreated UNEXPECTEDLY for game "+gameId+" !!!"); }
    @Override public void onJoinOk(int gameId, char symbol, String opponentName) { System.err.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): !!! Received JoinOk UNEXPECTEDLY for game "+gameId+" !!!"); }
    @Override public void onGameStart(int gameId, char symbol, String opponentName) {
        // Questo potrebbe arrivare di nuovo se c'è ritardo nella navigazione.
        // Dovrebbe essere innocuo se gameActive è già true.
        System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): Received GameStart again? (gameId "+gameId+"). Current gameActive="+gameActive.get()+", myTurn="+myTurn+". Current gameId="+this.gameId);
        if (this.gameId == gameId && !gameActive.get() && isSetupComplete.get()) {
            // Se il setup è completo ma il gioco è stato disattivato per qualche motivo,
            // e riceviamo di nuovo GameStart, potremmo riattivarlo? Rischioso.
            System.out.println(getCurrentTimestamp()+" - GC ("+this.hashCode()+"): GameStart received while inactive but setup complete. Attempting reactivation.");
            gameActive.set(true);
            Platform.runLater(() -> {
                TextTurno.setText("Partita " + gameId + " vs " + opponentName + ". Tu sei " + mySymbol + ". (Riattivato)");
                // Potrebbe essere necessario richiedere di nuovo la board?
                // networkService.sendMessage("GET_BOARD "+gameId); // Comando ipotetico
            });
        }
    }

    // Metodi ausiliari showInfo/showError (omessi per brevità, usa quelli precedenti)
    private void showInfo(String title, String content) { /* ... implementazione ... */ }
    private void showError(String title, String content) { /* ... implementazione ... */ }
}