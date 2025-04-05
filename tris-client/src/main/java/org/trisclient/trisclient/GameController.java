package org.trisclient.trisclient;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage; // Assicurati di importare Stage

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer; // Per il callback

public class GameController implements NetworkService.ServerListener {

    @FXML private GridPane gridPaneBoard;
    @FXML private Label labelGameInfo;
    @FXML private Label labelTurnInfo;
    @FXML private Button buttonBackToLobby; // Bottone opzionale per tornare indietro

    private NetworkService networkService;
    private int gameId;
    private char mySymbol; // 'X' o 'O'
    private String opponentName;
    private boolean myTurn = false;
    private boolean gameEnded = false; // Flag per evitare azioni dopo fine partita
    private Consumer<String> returnToHomeCallback; // Callback per tornare alla home

    private Button[][] buttons; // Array per riferimento ai bottoni della griglia

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    @FXML
    public void initialize() {
        System.out.println(getCurrentTimestamp() + " - GameController ("+this.hashCode()+"): initialize CALLED");
        // Inizializza l'array di bottoni
        buttons = new Button[3][3];
        for (Node node : gridPaneBoard.getChildren()) {
            if (node instanceof Button) {
                Button button = (Button) node;
                Integer row = GridPane.getRowIndex(node);
                Integer col = GridPane.getColumnIndex(node);
                if (row != null && col != null) {
                    buttons[row][col] = button;
                    // Associa l'azione del bottone qui
                    final int r = row; // Variabili final per lambda
                    final int c = col;
                    button.setOnAction(event -> handleCellClick(r, c));
                    button.setDisable(true); // Inizia con i bottoni disabilitati
                    button.setText(""); // Pulisci testo iniziale
                }
            }
        }
        buttonBackToLobby.setOnAction(this::handleBackToLobby);
        buttonBackToLobby.setVisible(false); // Nascondi inizialmente
    }

    // Metodo chiamato da HomePageController per passare i dati
    public void setupGame(NetworkService service, int gameId, char symbol, String opponentName, Consumer<String> returnCallback) {
        System.out.println(getCurrentTimestamp() + " - GameController ("+this.hashCode()+"): setupGame CALLED - GameID: " + gameId + ", Symbol: " + symbol + ", Opponent: " + opponentName);
        this.networkService = service;
        this.gameId = gameId;
        this.mySymbol = symbol;
        this.opponentName = opponentName;
        this.returnToHomeCallback = returnCallback;
        this.gameEnded = false; // Resetta flag
        this.myTurn = false;    // Inizia assumendo che non sia il mio turno (viene notificato)

        if (this.networkService == null) {
            System.err.println(getCurrentTimestamp() + " - GameController ("+this.hashCode()+"): FATAL ERROR - NetworkService is NULL in setupGame!");
            showError("Errore Critico", "Errore di comunicazione. Impossibile avviare la partita.");
            // Torna indietro subito se possibile
            Platform.runLater(() -> { // Esegui nel thread FX
                if(returnToHomeCallback != null) returnToHomeCallback.accept("Errore network service");
            });
            return;
        }

        // Imposta il listener su QUESTA istanza di GameController
        System.out.println(getCurrentTimestamp() + " - GameController ("+this.hashCode()+"): Setting Network Listener to THIS instance.");
        this.networkService.setServerListener(this);

        // Aggiorna UI iniziale
        Platform.runLater(() -> {
            labelGameInfo.setText("Partita " + gameId + " vs " + opponentName + " (Tu sei " + mySymbol + ")");
            labelTurnInfo.setText("In attesa del tuo turno...");
            // Pulisci la griglia da eventuali stati precedenti
            resetBoardUI();
        });
    }

    // Gestisce il click su una cella
    private void handleCellClick(int row, int col) {
        System.out.println(getCurrentTimestamp() + " - GameController ("+this.hashCode()+"): Cell clicked: " + row + "," + col);
        if (myTurn && !gameEnded && networkService != null && networkService.isConnected()) {
            System.out.println(getCurrentTimestamp() + " - GameController ("+this.hashCode()+"): Sending MOVE " + row + " " + col);
            networkService.sendMove(row, col);
            // Disabilita input finché il server non risponde o passa il turno
            setMyTurn(false);
            Platform.runLater(() -> labelTurnInfo.setText("Mossa inviata. Attendi..."));
        } else if (gameEnded) {
            System.out.println(getCurrentTimestamp() + " - GameController ("+this.hashCode()+"): Click ignored, game ended.");
        } else if (!myTurn) {
            System.out.println(getCurrentTimestamp() + " - GameController ("+this.hashCode()+"): Click ignored, not my turn.");
            Platform.runLater(() -> labelTurnInfo.setText("Non è il tuo turno!"));
        } else {
            System.err.println(getCurrentTimestamp() + " - GameController ("+this.hashCode()+"): Click ignored, not connected or service null.");
            showError("Errore", "Non connesso al server.");
        }
    }

    // Gestisce il click sul bottone "Torna alla Lobby"
    private void handleBackToLobby(ActionEvent event) {
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): Back to Lobby button clicked.");
        // Qui potresti voler inviare un messaggio di forfeit se la partita non è finita?
        // Per ora, assumiamo che sia visibile solo a fine partita.
        if (returnToHomeCallback != null) {
            returnToHomeCallback.accept("Ritorno alla lobby richiesto.");
        } else {
            System.err.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): returnToHomeCallback is null!");
            showError("Errore UI", "Impossibile tornare alla lobby.");
        }
    }

    // Imposta se è il turno del giocatore e aggiorna la UI
    private void setMyTurn(boolean turn) {
        this.myTurn = turn;
        Platform.runLater(() -> {
            if (gameEnded) return; // Non aggiornare se la partita è finita
            if (turn) {
                labelTurnInfo.setText("È il tuo turno (" + mySymbol + ")");
                // Abilita solo le celle vuote
                enableEmptyCells(true);
            } else {
                labelTurnInfo.setText("È il turno di " + opponentName + "...");
                // Disabilita tutta la griglia
                enableEmptyCells(false);
            }
        });
    }

    // Abilita/Disabilita le celle vuote sulla griglia
    private void enableEmptyCells(boolean enable) {
        if (buttons == null) return;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (buttons[r][c] != null) {
                    if (enable && buttons[r][c].getText().isEmpty()) {
                        buttons[r][c].setDisable(false);
                    } else {
                        buttons[r][c].setDisable(true);
                    }
                }
            }
        }
    }

    // Pulisce e resetta la UI della griglia
    private void resetBoardUI() {
        if (buttons == null) return;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (buttons[r][c] != null) {
                    buttons[r][c].setText("");
                    buttons[r][c].setDisable(true); // Inizia disabilitata
                    // Rimuovi eventuali stili specifici (es. colore vincitore)
                    buttons[r][c].getStyleClass().remove("winning-button"); // Esempio di classe CSS
                }
            }
        }
    }


    // --- Implementazione Metodi ServerListener ---

    @Override
    public void onConnected() {
        // Non dovrebbe essere chiamato qui se setupGame funziona correttamente
        System.err.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): !!! Received onConnected UNEXPECTEDLY !!!");
        // Potrebbe indicare un problema di riconnessione non gestito
        Platform.runLater(() -> showError("Errore", "Riconnessione inattesa durante la partita."));
        // Potrebbe essere necessario tornare alla lobby
        if (returnToHomeCallback != null) {
            returnToHomeCallback.accept("Errore: Riconnessione inattesa.");
        }
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): GUI: onDisconnected. Reason: " + reason);
        if (gameEnded) return; // Se la partita era già finita, non fare nulla
        gameEnded = true; // Marca come finita a causa della disconnessione

        Platform.runLater(() -> {
            showError("Disconnesso", "Connessione persa: " + reason + "\nVerrai riportato alla lobby.");
            labelTurnInfo.setText("Disconnesso.");
            enableEmptyCells(false); // Disabilita griglia
            buttonBackToLobby.setVisible(true); // Mostra bottone per tornare
            // Torna alla home automaticamente dopo l'alert?
            if (returnToHomeCallback != null) {
                returnToHomeCallback.accept("Disconnesso durante la partita.");
            }
        });
    }

    @Override
    public void onMessageReceived(String rawMessage) {
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): GUI: Unhandled message: " + rawMessage);
        // Potresti voler mostrare questi messaggi in un log/console di debug nella UI
    }

    @Override
    public void onBoardUpdate(String[] board) {
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): GUI: onBoardUpdate received.");
        if (board.length != 9 || gameEnded) return; // Ignora se malformato o partita finita

        Platform.runLater(() -> {
            int k = 0;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    if (buttons[r][c] != null) {
                        String cellState = board[k++];
                        if (cellState.equals("EMPTY")) {
                            buttons[r][c].setText("");
                            // Non cambiare disabilitazione qui, dipende da onYourTurn
                        } else { // "X" o "O"
                            buttons[r][c].setText(cellState);
                            buttons[r][c].setDisable(true); // Cella occupata è sempre disabilitata
                        }
                    }
                }
            }
            // Dopo l'aggiornamento, riabilita le celle vuote *solo se* è il mio turno
            if (myTurn) {
                enableEmptyCells(true);
            } else {
                enableEmptyCells(false); // Assicura che sia disabilitata se non è il mio turno
            }
        });
    }

    @Override
    public void onYourTurn() {
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): GUI: onYourTurn received.");
        if (gameEnded) return;
        setMyTurn(true);
    }

    // --- NUOVO METODO IMPLEMENTATO ---
    @Override
    public void onGameOver(String result) {
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): GUI: onGameOver received. Result: " + result);
        if (gameEnded) {
            System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): onGameOver ignored, game already marked as ended.");
            return; // Evita doppie azioni/alert se già gestito (es. da onOpponentLeft)
        }
        gameEnded = true;
        setMyTurn(false); // Nessuno gioca più

        Platform.runLater(() -> {
            String message;
            switch (result) {
                case "WIN":
                    message = "Hai Vinto!";
                    break;
                case "LOSE":
                    message = "Hai Perso.";
                    break;
                case "DRAW":
                    message = "Pareggio!";
                    break;
                default: // Caso imprevisto
                    message = "Partita terminata (Risultato: " + result + ")";
                    break;
            }
            labelTurnInfo.setText("Partita terminata.");
            enableEmptyCells(false); // Disabilita tutta la griglia
            buttonBackToLobby.setVisible(true); // Mostra bottone per tornare

            // Mostra l'alert
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Partita Terminata");
            alert.setHeaderText(message);
            alert.setContentText("Clicca OK per tornare alla lobby.");

            // Assicura che l'owner sia impostato se la finestra è disponibile
            try {
                Stage owner = (Stage) gridPaneBoard.getScene().getWindow();
                alert.initOwner(owner);
            } catch (Exception e) { System.err.println("Could not set owner for GameOver alert: "+e.getMessage()); }

            alert.showAndWait(); // Mostra l'alert e aspetta che venga chiuso

            // Dopo che l'alert è stato chiuso, torna alla lobby
            if (returnToHomeCallback != null) {
                returnToHomeCallback.accept("Partita terminata: " + result);
            } else {
                System.err.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): returnToHomeCallback is null after game over!");
            }
        });
    }

    @Override
    public void onOpponentLeft() {
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): GUI: onOpponentLeft received.");
        if (gameEnded) {
            System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): onOpponentLeft ignored, game already marked as ended.");
            return; // Evita doppi alert se GAMEOVER arriva subito dopo
        }
        gameEnded = true;
        setMyTurn(false); // Nessuno gioca più

        Platform.runLater(() -> {
            labelTurnInfo.setText("Avversario disconnesso.");
            enableEmptyCells(false); // Disabilita griglia
            buttonBackToLobby.setVisible(true); // Mostra bottone

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Partita Terminata");
            alert.setHeaderText("L'avversario ha abbandonato la partita.");
            alert.setContentText("Hai vinto!\nClicca OK per tornare alla lobby.");

            try {
                Stage owner = (Stage) gridPaneBoard.getScene().getWindow();
                alert.initOwner(owner);
            } catch (Exception e) { System.err.println("Could not set owner for OpponentLeft alert: "+e.getMessage()); }

            alert.showAndWait();

            // Torna alla lobby dopo l'alert
            if (returnToHomeCallback != null) {
                returnToHomeCallback.accept("Avversario disconnesso.");
            } else {
                System.err.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): returnToHomeCallback is null after opponent left!");
            }
        });
    }

    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): GUI: onError: " + message);
        // Mostra errori non fatali all'utente
        if (!gameEnded) { // Non mostrare errori se la partita è già finita o siamo disconnessi
            Platform.runLater(() -> {
                showError("Errore dal Server", message);
                // Potresti voler aggiornare labelTurnInfo a seconda dell'errore
                if (message.contains("Not your turn")) {
                    labelTurnInfo.setText("Errore: Non è il tuo turno!");
                    setMyTurn(false); // Sincronizza stato locale
                } else if (message.contains("Invalid move")) {
                    labelTurnInfo.setText("Errore: Mossa non valida!");
                    // Riabilita il turno se l'errore era sulla mossa
                    // Questo è delicato, il server dovrebbe ritrasmettere YOUR_TURN?
                    // Per ora, manteniamo il turno disabilitato e aspettiamo il server.
                    // setMyTurn(true); // Forse NON fare questo
                }
            });
        }
    }


    // Metodi non usati direttamente in GameController ma richiesti dall'interfaccia
    @Override public void onNameRequested() { System.err.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): !!! Received onNameRequested UNEXPECTEDLY !!!"); }
    @Override public void onNameAccepted() { System.err.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): !!! Received onNameAccepted UNEXPECTEDLY !!!"); }
    @Override public void onGamesList(List<NetworkService.GameInfo> games) { System.err.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): !!! Received onGamesList UNEXPECTEDLY !!!"); }
    @Override public void onGameCreated(int gameId) { System.err.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): !!! Received onGameCreated UNEXPECTEDLY !!!"); }
    @Override public void onJoinOk(int gameId, char symbol, String opponentName) { System.err.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): !!! Received onJoinOk UNEXPECTEDLY !!!"); }
    @Override public void onGameStart(int gameId, char symbol, String opponentName) { System.err.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): !!! Received onGameStart UNEXPECTEDLY !!!"); }
    //-------------------------------------------------------------------------------


    // Metodo helper per mostrare errori
    private void showError(String title, String content) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showError(title, content));
            return;
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        try { // Evita eccezioni se la scena non è pronta
            Stage owner = (Stage) gridPaneBoard.getScene().getWindow();
            alert.initOwner(owner);
        } catch (Exception e) { System.err.println("Could not set owner for Error alert: "+e.getMessage()); }
        alert.showAndWait();
    }
}