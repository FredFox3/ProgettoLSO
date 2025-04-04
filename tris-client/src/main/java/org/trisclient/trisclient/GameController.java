package org.trisclient.trisclient;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer; // Per il callback di ritorno

public class GameController implements NetworkService.ServerListener { // Implementa direttamente il listener

    @FXML private Label TextTurno;
    @FXML private GridPane gridPane;

    // Riferimenti passati da HomePageController
    private NetworkService networkService;
    private int gameId;
    private char mySymbol;
    private String opponentName;
    private Consumer<String> returnToHomeCallback; // Callback per tornare alla home

    private Button[][] buttons = new Button[3][3];
    private volatile boolean myTurn = false;
    private volatile boolean gameActive = false;

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    // NON USARE initialize() per la logica dipendente dai dati passati
    @FXML
    public void initialize() {
        // Crea i bottoni della griglia
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                Button btn = new Button(" ");
                //btn.setPrefSize(100, 100); // Aumenta dimensione
                btn.setMinSize(95, 95);
                btn.setMaxSize(100,100);
                btn.setStyle("-fx-font-size: 36px; -fx-font-weight: bold;"); // Font più grande
                final int row = i;
                final int col = j;
                btn.setOnAction(e -> handleCellClick(row, col));
                buttons[i][j] = btn;
                gridPane.add(btn, j, i); // Nota: GridPane usa (col, row)
            }
        }
        gridPane.setDisable(true); // Disabilita finché non inizia la partita
        TextTurno.setText("Loading game...");
    }

    // Metodo chiamato da HomePageController DOPO aver caricato l'FXML

    public void setupGame(NetworkService service, int gameId, char symbol, String opponentName, Consumer<String> returnCallback) {
        this.networkService = service;
        this.gameId = gameId;
        this.mySymbol = symbol;
        this.opponentName = opponentName;
        this.returnToHomeCallback = returnCallback;

        // *** CONTROLLO FONDAMENTALE ***
        if (this.networkService == null) {
            System.err.println(getCurrentTimestamp()+" - GC FATAL: NetworkService instance is null in setupGame!");
            showError("Errore Critico", "NetworkService non disponibile per GameController.");
            // Torna alla home page immediatamente se possibile
            if (returnToHomeCallback != null) {
                Platform.runLater(()-> returnToHomeCallback.accept("NetworkService null"));
            }
            return; // Non continuare setup se service è null
        }

        // *** CAMBIO LISTENER CORRETTO ***
        System.out.println(getCurrentTimestamp() + " - GC: Setting GameController as the active NetworkService listener.");
        this.networkService.setServerListener(this); // Imposta questo controller come listener ATTIVO

        TextTurno.setText("Partita " + gameId + " vs " + opponentName + ". Tu sei " + mySymbol + ". In attesa inizio...");
        gameActive = true; // La partita è considerata attiva ora
        // La griglia verrà abilitata da onYourTurn o all'inizio effettivo
        System.out.println(getCurrentTimestamp() + " - GameController setup complete for game " + gameId);
    }

    private void handleCellClick(int row, int col) {
        System.out.println(getCurrentTimestamp() + " - Cell clicked: " + row + " " + col + " (myTurn=" + myTurn + ")");
        if (myTurn && gameActive && buttons[row][col].getText().trim().isEmpty()) {
            // Disabilita subito la griglia per evitare doppio click
            myTurn = false;
            gridPane.setDisable(true);
            TextTurno.setText("Invio mossa ("+row+","+col+"). Attendi...");
            networkService.sendMove(row, col);
        } else if (!myTurn) {
            System.out.println(getCurrentTimestamp() + " - Click ignorato: non è il mio turno.");
        } else if (!gameActive) {
            System.out.println(getCurrentTimestamp() + " - Click ignorato: partita non attiva.");
        } else {
            System.out.println(getCurrentTimestamp() + " - Click ignorato: cella non vuota.");
        }
    }

    // --- Implementazione ServerListener ---

    @Override
    public void onConnected() {
        // Questo non dovrebbe essere chiamato di nuovo se la connessione è già attiva
        System.out.println(getCurrentTimestamp() + " - GC: Listener (re)registered.");
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp() + " - GC: Disconnected. Reason: " + reason);
        if (!gameActive) return; // Se siamo già tornati alla home, ignora
        gameActive = false;
        gridPane.setDisable(true);
        TextTurno.setText("Disconnesso: " + reason);
        showInfo("Disconnesso", "Connessione persa: " + reason + "\nTorni alla lobby.");
        if (returnToHomeCallback != null) returnToHomeCallback.accept("Disconnected: " + reason);
    }

    @Override
    public void onMessageReceived(String rawMessage) {
        System.out.println(getCurrentTimestamp() + " - GC: Unhandled message: " + rawMessage);
    }

    @Override
    public void onBoardUpdate(String[] boardCells) {
        System.out.println(getCurrentTimestamp() + " - GC: *** onBoardUpdate called! *** Processing board...");
        System.out.println(getCurrentTimestamp() + " - GC: Received board update: " + Arrays.toString(boardCells));
        if (boardCells.length != 9) {
            System.err.println("Errore: ricevuti dati board di lunghezza non valida: " + boardCells.length);
            return;
        }
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                String symbol = boardCells[i * 3 + j];
                if ("EMPTY".equals(symbol)) {
                    buttons[i][j].setText(" ");
                    buttons[i][j].setDisable(myTurn && gameActive ? false : true); // Abilita solo se vuota E il mio turno
                } else {
                    buttons[i][j].setText(symbol);
                    buttons[i][j].setDisable(true); // Disabilita se piena
                }
            }
        }
        // Ri-abilita la griglia se è il nostro turno (YourTurn viene dopo Board?)
        if (myTurn && gameActive) {
            gridPane.setDisable(false);
        }
    }

    @Override
    public void onYourTurn() {
        System.out.println(getCurrentTimestamp() + " - GC: *** onYourTurn called! *** Enabling turn..."); // LOG
        myTurn = true;
        if (gameActive) {
            TextTurno.setText("Il tuo turno! (" + mySymbol + ")");
            System.out.println(getCurrentTimestamp() + " - GC: Enabling GridPane in onYourTurn."); // LOG
            gridPane.setDisable(false); // Abilita la griglia
            // Ri-controlla i bottoni, abilita solo quelli vuoti
            int enabledCount = 0;
            for(int r=0; r<3; r++) {
                for (int c=0; c<3; c++) {
                    if(buttons[r][c].getText().trim().isEmpty()) {
                        buttons[r][c].setDisable(false);
                        enabledCount++;
                    } else {
                        buttons[r][c].setDisable(true);
                    }
                }
            }
            System.out.println(getCurrentTimestamp() + " - GC: Finished enabling buttons in onYourTurn. Enabled count: " + enabledCount); // LOG
        } else {
            System.out.println(getCurrentTimestamp() + " - GC: onYourTurn called but game is not active."); // LOG
        }
    }

    @Override
    public void onGameOver(String result) { // WIN, LOSE, DRAW
        System.out.println(getCurrentTimestamp() + " - GC: Game Over! Result: " + result);
        gameActive = false;
        myTurn = false;
        gridPane.setDisable(true); // Disabilita tutta la griglia
        String message;
        switch (result) {
            case "WIN": message = "Hai Vinto!"; break;
            case "LOSE": message = "Hai Perso!"; break;
            case "DRAW": message = "Pareggio!"; break;
            default: message = "Partita Terminata (" + result + ")";
        }
        TextTurno.setText(message);
        showInfo("Partita Terminata", message + "\nTorni alla lobby.");

        // Torna alla Home Page dopo un breve ritardo o su click
        // Qui usiamo il callback passato da HomePageController
        if (returnToHomeCallback != null) returnToHomeCallback.accept(message);

    }

    @Override
    public void onOpponentLeft() {
        System.out.println(getCurrentTimestamp() + " - GC: Opponent left the game.");
        if (!gameActive) return; // Se siamo già tornati alla home, ignora
        gameActive = false;
        myTurn = false;
        gridPane.setDisable(true);
        TextTurno.setText("L'avversario ha abbandonato.");
        showInfo("Avversario Disconnesso", opponentName + " ha lasciato la partita.\nHai vinto!\nTorni alla lobby.");
        if (returnToHomeCallback != null) returnToHomeCallback.accept("Opponent left");
    }

    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp() + " - GC: Received error: " + message);
        // Potrebbe essere un errore di mossa non valida, o altro
        if (message.contains("Not your turn")) {
            TextTurno.setText("Attendi il tuo turno!");
            myTurn = false; // Assicura che sia falso
            gridPane.setDisable(true);
        } else if (message.contains("Invalid move")) {
            TextTurno.setText("Mossa non valida! Riprova.");
            myTurn = true; // Permetti di riprovare
            gridPane.setDisable(false);
            // Riabilita solo le celle vuote
            for(int r=0; r<3; r++) for (int c=0; c<3; c++) buttons[r][c].setDisable(!buttons[r][c].getText().trim().isEmpty());
        } else {
            // Errore più grave?
            showError("Errore dal Server", message);
            TextTurno.setText("Errore: " + message);
            // Potrebbe essere necessario tornare alla lobby per errori gravi
            // if (returnToHomeCallback != null) returnToHomeCallback.accept("Error: " + message);
        }
    }

    // Metodi non usati in GameController ma necessari per l'interfaccia
    @Override public void onNameRequested() {}
    @Override public void onNameAccepted() {}
    @Override public void onGamesList(List<NetworkService.GameInfo> games) {}
    @Override public void onGameCreated(int gameId) {}
    @Override public void onJoinOk(int gameId, char symbol, String opponentName) {}
    @Override public void onGameStart(int gameId, char symbol, String opponentName) {
        // Questo potrebbe essere chiamato anche qui se la navigazione è lenta
        System.out.println(getCurrentTimestamp()+" - GC: Received GameStart again?");
    }

    // --- Metodi Ausiliari ---
    private void showInfo(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
    private void showError(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    // Aggiungi un metodo per gestire l'uscita manuale (es. un bottone "Abbandona")
    @FXML
    private void handleLeaveGame() {
        if (gameActive && networkService != null && networkService.isConnected()) {
            networkService.sendQuit(); // Invia QUIT al server
        }
        // Torna alla home indipendentemente dalla risposta del server (il server gestirà la disconnessione)
        gameActive = false;
        if (returnToHomeCallback != null) {
            returnToHomeCallback.accept("Left game manually");
        }
    }
}