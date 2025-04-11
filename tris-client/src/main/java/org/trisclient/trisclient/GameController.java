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
        System.out.println(getCurrentTimestamp()+" - GameController ("+this.hashCode()+"): FXML initialize CHIAMATO");
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
        TextTurno.setText("Caricamento partita..."); // Tradotto
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
        if (initialBoard != null) this.cachedBoard = initialBoard; else this.cachedBoard = null;
        this.cachedTurn.set(initialTurn);

        if (this.networkService == null) {
            System.err.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Errore critico in setupGame - NetworkService è null!");
            Platform.runLater(() -> {
                showError("Errore Critico", "Errore interno di rete. Impossibile avviare la partita."); // Tradotto
                if (returnToHomeCallback != null) returnToHomeCallback.accept("Errore di Rete Critico"); // Tradotto
            });
            return;
        }
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): Impostazione listener");
        this.networkService.setServerListener(this);

        Platform.runLater(() -> {
            TextTurno.setText("Partita " + gameId + " vs " + opponentName + ". Sei " + mySymbol + "."); // Tradotto
            gridPane.setDisable(true); if (buttonLeave != null) buttonLeave.setDisable(false);
            isSetupComplete.set(true); gameActive.set(true);
            System.out.println(getCurrentTimestamp() + " - GC (runLater): Setup COMPLETATO. gameActive=true");
            processCachedMessages();
        });
    }

    private void processCachedMessages() {
        System.out.println(getCurrentTimestamp() + " - GC: Elaborazione messaggi in cache..."); // Tradotto
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
            }
        }
        // Disabilita/Abilita la griglia INTERA basandosi sullo stato generale e myTurn
        gridPane.setDisable(!gameActive.get() || !myTurn);
    }


    @Override
    public void onYourTurn() {
        System.out.println(getCurrentTimestamp()+" - GC: onYourTurn ricevuto. isSetupComplete="+isSetupComplete.get()+" | gameActive="+gameActive.get());
        if (!isSetupComplete.get()) { this.cachedTurn.set(true); return; }
        if (!gameActive.get()) {
            System.out.println(getCurrentTimestamp()+" - GC: Ignoro onYourTurn perché gameActive è false."); // Tradotto
            return;
        }
        handleYourTurnInternal();
    }

    private void handleYourTurnInternal() {
        if (!gameActive.get()) return;
        myTurn = true;
        Platform.runLater(() -> {
            TextTurno.setText("È il tuo turno! (" + mySymbol + ")"); // Tradotto
            System.out.println(getCurrentTimestamp()+" - GC (UI): Abilitazione gridPane per il tuo turno."); // Tradotto
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
            TextTurno.setText("Invio mossa..."); // Tradotto
            if(networkService != null) networkService.sendMove(row, col);
            else {
                System.err.println(getCurrentTimestamp()+" - GC: Errore di rete durante l'invio della mossa!");
                if (returnToHomeCallback != null) Platform.runLater(()->returnToHomeCallback.accept("Errore di Rete")); // Tradotto
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
            String message = "L'avversario ha abbandonato. Hai vinto!"; // Tradotto
            TextTurno.setText(message);
            showInfo("Partita Terminata", message + "\nRitorno alla lobby."); // Tradotto
            if (returnToHomeCallback != null) returnToHomeCallback.accept("Avversario disconnesso - Hai Vinto!"); // Tradotto
        });
    }

    @Override
    public void onNameRejected(String reason) {
        System.err.println(getCurrentTimestamp() + " - GameController ("+this.hashCode()+"): !!! INASPETTATO onNameRejected ricevuto: " + reason + " !!!");
        gameActive.set(false);
        gameFinishedWaitingRematch.set(false);
        myTurn = false;
        Platform.runLater(() -> {
            showError("Errore Critico di Stato", "Ricevuto rifiuto nome durante la partita: " + reason + "\nRitorno alla lobby."); // Tradotto
            if (gridPane != null) gridPane.setDisable(true);
            if (buttonLeave != null) buttonLeave.setDisable(true);
            if (TextTurno != null) TextTurno.setText("Errore inaspettato..."); // Tradotto

            if (returnToHomeCallback != null) {
                returnToHomeCallback.accept("Rifiuto nome inaspettato: " + reason); // Tradotto
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
            alert.setTitle("Rivincita?"); // Tradotto
            String headerText = "Partita Terminata!"; // Tradotto
            String contentText = "Giocare ancora?"; // Tradotto
            boolean isDraw = "DRAW".equalsIgnoreCase(this.lastGameResult);
            boolean isWinner = "WIN".equalsIgnoreCase(this.lastGameResult);

            if(isDraw) { headerText = "È un Pareggio!"; contentText = "Giocare ancora?"; } // Tradotto
            else if (isWinner) { headerText = "Hai Vinto!"; contentText = "Ospitare una nuova partita contro questo avversario?"; } // Tradotto
            else { System.err.println(getCurrentTimestamp()+" - GC: ERRORE - Ricevuta offerta rivincita ma risultato era SCONFITTA? Non dovrebbe succedere."); alert.close(); if(returnToHomeCallback!=null) returnToHomeCallback.accept("Errore: Offerta rivincita inaspettata"); return; } // Tradotto

            alert.setHeaderText(headerText);
            alert.setContentText(contentText);

            ButtonType buttonTypeYes = new ButtonType("Sì, Gioca Ancora"); // Tradotto
            ButtonType buttonTypeNo = new ButtonType("No, Torna alla Lobby"); // Tradotto
            alert.getButtonTypes().setAll(buttonTypeYes, buttonTypeNo);
            try { Stage owner = getCurrentStage(); if(owner != null && owner.isShowing()) alert.initOwner(owner); } catch(Exception e) { System.err.println("GC: Errore impostazione owner per alert rivincita: "+e.getMessage()); }

            Optional<ButtonType> result = alert.showAndWait();

            if (opponentDeclinedWhileWaiting.getAndSet(false)) {
                System.out.println(getCurrentTimestamp()+" - GC: Avversario ha rifiutato (rilevato DOPO popup). Scelta ignorata. Ritorno home."); // Tradotto
                gameFinishedWaitingRematch.set(false);
                Platform.runLater(() -> {
                    showInfo("Rivincita Annullata", "L'avversario ha rifiutato la rivincita mentre stavi decidendo.\nRitorno alla lobby."); // Tradotto
                    if (returnToHomeCallback != null) {
                        returnToHomeCallback.accept("Avversario ha rifiutato (mentre decidevi)"); // Tradotto
                    } else {
                        System.err.println("GC: returnToHomeCallback è null dopo che l'avversario ha rifiutato mentre si aspettava!");
                    }
                });
                return;
            }

            boolean myChoiceIsYes = result.isPresent() && result.get() == buttonTypeYes;

            if (myChoiceIsYes) {
                System.out.println(getCurrentTimestamp()+" - GC: Utente ha scelto SÌ per rivincita. Invio REMATCH YES."); // Tradotto
                if(networkService != null){
                    TextTurno.setText("Invio scelta... Attesa avversario/server..."); // Tradotto
                    networkService.sendRematchChoice(true);
                    gameFinishedWaitingRematch.set(true);
                    System.out.println(getCurrentTimestamp()+" - GC: Inviato REMATCH YES, flag waitingRematch="+gameFinishedWaitingRematch.get());
                } else { gameFinishedWaitingRematch.set(false); TextTurno.setText("Errore di Rete!"); showError("Errore di Rete", "Impossibile inviare la scelta per la rivincita."); if (returnToHomeCallback != null) Platform.runLater(()->returnToHomeCallback.accept("Errore di Rete")); } // Tradotto
            } else {
                System.out.println(getCurrentTimestamp()+" - GC: Utente ha scelto NO per rivincita. Invio REMATCH NO."); // Tradotto
                if(networkService != null){
                    TextTurno.setText("Rifiuto rivincita..."); // Tradotto
                    networkService.sendRematchChoice(false);
                    System.out.println(getCurrentTimestamp()+" - GC: Inviato REMATCH NO, flag waitingRematch="+gameFinishedWaitingRematch.get());
                } else { gameFinishedWaitingRematch.set(false); TextTurno.setText("Errore di Rete!"); showError("Errore di Rete", "Impossibile inviare la scelta per la rivincita."); if (returnToHomeCallback != null) Platform.runLater(()->returnToHomeCallback.accept("Errore di Rete")); } // Tradotto
            }
        });
    }

    @Override
    public void onRematchAccepted(int receivedGameId) {
        System.out.println(getCurrentTimestamp() + " - GC: onRematchAccepted ricevuto (Partita " + receivedGameId + ")");
        gameFinishedWaitingRematch.set(false); gameActive.set(false); myTurn=false;
        Platform.runLater(() -> {
            showInfo("Rivincita Accettata (Host)", "Rivincita accettata! Ospito la partita " + receivedGameId + ".\nRitorno alla lobby in attesa."); // Tradotto
            if (returnToHomeCallback != null) returnToHomeCallback.accept("Rivincita accettata, in attesa"); // Tradotto
            else System.err.println("GC: Callback null su onRematchAccepted!");
        });
    }

    @Override
    public void onGameOver(String result) {
        System.out.println(getCurrentTimestamp() + " - GC: onGameOver - Risultato: " + result + " | gameActive attuale=" + gameActive.get());

        if (!gameActive.compareAndSet(true, false)) {
            System.out.println(getCurrentTimestamp()+" - GC: Ignoro onGameOver ridondante ("+result+"). Partita già inattiva."); // Tradotto
            return;
        }

        this.lastGameResult = result;
        gameFinishedWaitingRematch.set(true);
        myTurn = false;
        this.opponentDeclinedWhileWaiting.set(false);

        final String finalResult = result;

        Platform.runLater(() -> {
            gridPane.setDisable(true);

            String message = "Partita Terminata! "; // Tradotto
            if ("WIN".equalsIgnoreCase(finalResult)) { message += "Hai Vinto!"; message += "\nIn attesa di opzioni per la rivincita..."; } // Tradotto
            else if ("DRAW".equalsIgnoreCase(finalResult)) { message += "È un Pareggio!"; message += "\nIn attesa di opzioni per la rivincita..."; } // Tradotto
            else if ("LOSE".equalsIgnoreCase(finalResult)){ message += "Hai Perso."; message += "\nRitorno alla lobby..."; } // Tradotto
            else { message += "Risultato sconosciuto (" + finalResult + ")"; message += "\nRitorno alla lobby..."; } // Tradotto

            TextTurno.setText(message);
            System.out.println(getCurrentTimestamp()+" - GC (UI): UI Game Over aggiornata. Risultato: "+finalResult+". WaitingRematch="+gameFinishedWaitingRematch.get());
            if(buttonLeave!=null) buttonLeave.setDisable(false);
        });
    }

    @Override
    public void onRematchDeclined() {
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onRematchDeclined received. LastResult="+lastGameResult+" | WaitingFlag before:"+gameFinishedWaitingRematch.get());

        boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false); // Ottieni e resetta flag attesa
        gameActive.set(false); // Gioco non più attivo
        myTurn = false; // Non è il mio turno
        opponentDeclinedWhileWaiting.set(false); // Resetta questo flag

        // --- Logica Chiave ---
        // Se questo messaggio è arrivato PERCHE' ABBIAMO PERSO (lastGameResult == LOSE),
        // ignoriamo questo messaggio perché l'azione di ritorno alla lobby verrà gestita
        // da onOpponentRematchDecision quando il vincitore decide.
        if ("LOSE".equalsIgnoreCase(lastGameResult)) {
            System.out.println(getCurrentTimestamp()+" - GC: Ignoring initial REMATCH_DECLINED for LOSE case. Waiting for opponent decision.");
            // Resettiamo gameFinishedWaitingRematch qui perché abbiamo PERSO, non stiamo aspettando nulla
            // L'abbiamo già fatto con getAndSet sopra, ma per chiarezza
            gameFinishedWaitingRematch.set(false);
            return; // << USCIAMO QUI SE ERA UNA SCONFITTA
        }
        // ------------------

        // Se siamo qui, non abbiamo perso. Significa che:
        // 1. Abbiamo inviato REMATCH NO noi stessi.
        // 2. Abbiamo inviato REMATCH YES (in pareggio) ma l'avversario aveva già detto NO.

        String alertTitle = "Info Rivincita";
        String alertContent = "Returning to the lobby.";
        String returnReason = "Rematch declined or game ended";

        // Adatta i messaggi in base alla causa (se non era LOSE)
        if (wasWaiting) { // Caso 2: Avevamo detto YES ma l'altro NO (quindi stavamo aspettando)
            alertTitle = "Rivincita Annullata";
            alertContent = "L'avversario aveva già rifiutato la rivincita.\nRitorno alla lobby.";
            returnReason = "Avversario ha rifiutato prima";
        } else { // Caso 1: Abbiamo detto NO noi
            alertTitle = "Rivincita Rifiutata";
            alertContent = "Hai rifiutato la rivincita.\nRitorno alla lobby.";
            returnReason = "Rivincita rifiutata";
        }

        final String finalAlertContent = alertContent;
        final String finalReturnReason = returnReason;
        final String finalAlertTitle = alertTitle;

        Platform.runLater(() -> {
            // Mostra UN SOLO popup per questi casi
            showInfo(finalAlertTitle, finalAlertContent);
            if (returnToHomeCallback != null) {
                returnToHomeCallback.accept(finalReturnReason);
            } else {
                System.err.println("GC: returnToHomeCallback null after onRematchDeclined!");
                showError("Errore Critico", "Impossibile tornare alla lobby dopo fine partita/rifiuto rivincita. Callback mancante.");
            }
        });
    }
    // --- FINE onRematchDeclined() ---


//    @Override
//    public void onRematchDeclined() {
//        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): onRematchDeclined ricevuto. LastResult="+lastGameResult+" | WaitingFlag prima:"+gameFinishedWaitingRematch.get());
//
//        boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false);
//        gameActive.set(false); myTurn=false;
//        opponentDeclinedWhileWaiting.set(false);
//
//        String alertTitle = "Info Rivincita"; // Tradotto
//        String alertContent = "Ritorno alla lobby."; // Tradotto
//        String returnReason = "Rivincita rifiutata o partita terminata"; // Tradotto
//
//        if ("LOSE".equalsIgnoreCase(lastGameResult)) {
//            alertContent = "Partita persa. Ritorno alla lobby."; // Tradotto
//            returnReason = "Partita persa"; // Tradotto
//            // alertTitle = "Partita Terminata"; // Opzionale
//        } else if (wasWaiting) {
//            alertTitle = "Rivincita Annullata"; // Tradotto
//            alertContent = "L'avversario aveva già rifiutato la rivincita.\nRitorno alla lobby."; // Tradotto
//            returnReason = "Avversario ha rifiutato prima"; // Tradotto
//        } else {
//            alertTitle = "Rivincita Rifiutata"; // Tradotto
//            alertContent = "Hai rifiutato la rivincita.\nRitorno alla lobby."; // Tradotto
//            returnReason = "Rivincita rifiutata"; // Tradotto
//        }
//
//        final String finalAlertContent = alertContent;
//        final String finalReturnReason = returnReason;
//        final String finalAlertTitle = alertTitle;
//
//        Platform.runLater(() -> {
//            showInfo(finalAlertTitle, finalAlertContent);
//            if (returnToHomeCallback != null) {
//                returnToHomeCallback.accept(finalReturnReason);
//            } else {
//                System.err.println("GC: returnToHomeCallback null dopo onRematchDeclined!");
//                showError("Errore Critico", "Impossibile tornare alla lobby dopo fine partita/rifiuto rivincita. Callback mancante."); // Tradotto
//            }
//        });
//    }

    @Override
    public void onOpponentRematchDecision(boolean opponentAccepted) {
        final String decision = opponentAccepted ? "accettato" : "rifiutato"; // Tradotto
        System.out.println(getCurrentTimestamp() + " - GC ("+this.hashCode()+"): *** onOpponentRematchDecision ricevuto: "+decision+" *** | Flag attesa attuale: "+gameFinishedWaitingRematch.get() + " | isDraw: "+("DRAW".equalsIgnoreCase(lastGameResult)));

        if (!opponentAccepted) {
            System.out.println(getCurrentTimestamp()+" - GC: Avversario ha RIFIUTATO la rivincita. Ritorno home."); // Tradotto

            boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false);
            gameActive.set(false);
            myTurn = false;
            opponentDeclinedWhileWaiting.set(false);

            Platform.runLater(() -> {
                showInfo("Rivincita Rifiutata", "L'avversario ha rifiutato la rivincita.\nRitorno alla lobby."); // Tradotto
                if (returnToHomeCallback != null) {
                    returnToHomeCallback.accept("Avversario ha rifiutato la rivincita"); // Tradotto
                } else {
                    System.err.println("GC: Callback null sulla decisione RIFIUTO dell'avversario!");
                    showError("Errore Critico", "Impossibile tornare alla lobby dopo il rifiuto dell'avversario. Callback mancante."); // Tradotto
                    if(networkService != null) networkService.disconnect();
                }
            });

        } else { // opponentAccepted == true
            if ("LOSE".equalsIgnoreCase(lastGameResult)) {
                System.out.println(getCurrentTimestamp()+" - GC: Avversario (Vincitore) ha ACCETTATO la rivincita. Ritorno il perdente alla lobby."); // Tradotto
                boolean wasWaiting = gameFinishedWaitingRematch.getAndSet(false);
                gameActive.set(false); myTurn = false;
                Platform.runLater(() -> {
                    showInfo("Rivincita Accettata (dall'Avversario)", "Il vincitore ha accettato la rivincita e sta ospitando una nuova partita.\nRitorno alla lobby."); // Tradotto
                    if (returnToHomeCallback != null) { returnToHomeCallback.accept("Avversario ha accettato, torno alla lobby"); } // Tradotto
                    else { System.err.println("GC: Callback null su ACCETTAZIONE Avversario (caso Perdente)!"); }
                });
            } else if ("DRAW".equalsIgnoreCase(lastGameResult)) {
                System.out.println(getCurrentTimestamp()+" - GC: Avversario ha ACCETTATO (PAREGGIO). In attesa scelta locale o GameStart. Flag waitingRematch="+gameFinishedWaitingRematch.get()); // Tradotto
            } else { // WIN
                System.err.println(getCurrentTimestamp()+" - GC: Vincitore ha ricevuto Accettazione Avversario? Messaggio inaspettato. Ignoro."); // Tradotto
            }
        }
    }


    @Override
    public void onError(String message) {
        System.err.println(getCurrentTimestamp() + " - GC: onError ricevuto: " + message);

        if (message != null && message.contains("Unknown command or invalid state (1) for command: REMATCH NO")) {
            System.out.println(getCurrentTimestamp()+" - GC: IGNORO errore 'invalid state' atteso dopo invio REMATCH NO (perché avversario già rifiutato/uscito)."); // Tradotto
            return;
        }

        if (!gameActive.get() && !gameFinishedWaitingRematch.get()) {
            System.out.println(getCurrentTimestamp()+" - GC: Errore ricevuto ma partita non attiva/in attesa. Ignoro."); // Tradotto
            return;
        }

        Platform.runLater(()-> {
            if (message.contains("Not your turn") || message.contains("Invalid move")) {
                String userMessage = message.replace("ERROR:", "").trim();
                if (userMessage.contains("Not your turn")) userMessage = "Non è il tuo turno."; // Traduci specifici errori
                else if (userMessage.contains("Invalid move")) userMessage = "Mossa non valida."; // Traduci specifici errori
                TextTurno.setText(userMessage + " Riprova."); // Tradotto
                if (myTurn && gameActive.get()) {
                    gridPane.setDisable(false);
                } else { gridPane.setDisable(true); }
            } else {
                showError("Errore del Server", message); // Tradotto
                TextTurno.setText("Errore: " + message); // Tradotto
                gridPane.setDisable(true);
                gameActive.set(false); gameFinishedWaitingRematch.set(false); myTurn = false;
                if (returnToHomeCallback != null) returnToHomeCallback.accept("Errore del Server"); // Tradotto
            }
        });
    }

    @FXML
    private void handleLeaveGame() {
        System.out.println(getCurrentTimestamp() + " - GC: Cliccato Abbandona Partita."); // Tradotto
        gameActive.set(false); gameFinishedWaitingRematch.set(false); opponentDeclinedWhileWaiting.set(false);
        myTurn = false;
        Platform.runLater(() -> {
            gridPane.setDisable(true); if(buttonLeave != null) buttonLeave.setDisable(true);
            TextTurno.setText("Abbandono..."); // Tradotto
        });
        final String callbackMsg = "ABBANDONO_VOLONTARIO"; // Tradotto (o mantieni inglese se è una chiave)
        final String alertContent = "Hai abbandonato la partita.\nRitorno alla lobby."; // Tradotto
        if (networkService != null && networkService.isConnected()) networkService.sendQuit();
        if (returnToHomeCallback != null) Platform.runLater(() -> { showInfo("Partita Abbandonata", alertContent); returnToHomeCallback.accept(callbackMsg); }); // Tradotto
        else showError("Errore", "Impossibile tornare alla home!"); // Tradotto
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.println(getCurrentTimestamp() + " - GC: onDisconnected - Motivo: " + reason); // Tradotto
        gameActive.set(false); gameFinishedWaitingRematch.set(false); opponentDeclinedWhileWaiting.set(false); myTurn = false;
        final String finalReason = reason;
        Platform.runLater(() -> {
            gridPane.setDisable(true); if(buttonLeave != null) buttonLeave.setDisable(true);
            TextTurno.setText("Disconnesso"); // Tradotto
            String userFriendlyReason = finalReason;
            // Traduci motivi specifici se necessario per il popup
            if ("Disconnected by client".equals(finalReason)) userFriendlyReason = "Disconnessione richiesta dal client";
            else if ("Server closed connection".equals(finalReason)) userFriendlyReason = "Il server ha chiuso la connessione";
            else if (finalReason.startsWith("Connection error:")) userFriendlyReason = "Errore di connessione: " + finalReason.substring("Connection error:".length()).trim();
            else if (finalReason.startsWith("IO error:")) userFriendlyReason = "Errore I/O: " + finalReason.substring("IO error:".length()).trim();

            if (!"Disconnected by client".equals(finalReason) && !"ABBANDONO_VOLONTARIO".equals(finalReason)) // Usa la chiave originale se l'hai cambiata
                showInfo("Disconnesso", "Connessione persa: " + userFriendlyReason + "\nRitorno alla lobby."); // Tradotto
            if (returnToHomeCallback != null) returnToHomeCallback.accept("Disconnesso: " + finalReason); // Passa il motivo originale al callback
            else System.err.println("GC: Callback null sulla disconnessione!");
        });
    }

    // Listener non previsti qui - Messaggi tradotti
    @Override public void onConnected() { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onConnected"); }
    @Override public void onNameRequested() { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onNameRequested"); }
    @Override public void onNameAccepted() { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onNameAccepted"); }
    @Override public void onGamesList(List<NetworkService.GameInfo> g) { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onGamesList"); }
    @Override public void onGameCreated(int gid) { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onGameCreated"); }
    @Override public void onJoinRequestSent(int gid) { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onJoinRequestSent"); }
    @Override public void onJoinRequestReceived(String n) { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onJoinRequestReceived"); }
    @Override public void onJoinAccepted(int gid, char s, String on) { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onJoinAccepted"); }
    @Override public void onJoinRejected(int gid, String cn) { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onJoinRejected"); }
    @Override public void onActionConfirmed(String m) { if (m != null && m.startsWith("QUIT_OK")) { System.out.println(getCurrentTimestamp()+" - GC: Ricevuto ActionConfirmed (probabilmente QUIT_OK): "+m); } else { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato onActionConfirmed: "+m); } } // Tradotto
    @Override public void onMessageReceived(String rm) { System.err.println(getCurrentTimestamp()+" - GC: Inaspettato messaggio raw: "+rm); }

    @Override
    public void onGameStart(int recGameId, char recSymbol, String recOpponentName) {
        System.out.println(getCurrentTimestamp()+" - GC: onGameStart ricevuto per partita "+recGameId);
        if(this.gameId != recGameId){ System.err.println("GC: ERRORE GameStart per ID errato!"); return; } // Tradotto

        boolean isDrawRematch = gameFinishedWaitingRematch.compareAndSet(true,false);
        gameActive.set(true);
        this.mySymbol = recSymbol; this.opponentName = recOpponentName;
        this.myTurn = false;

        if (isDrawRematch) {
            System.out.println(getCurrentTimestamp() + " - GC: GameStart -> RIVINCITA PAREGGIO INIZIA!"); // Tradotto
            Platform.runLater(() -> {
                TextTurno.setText("Rivincita! vs " + this.opponentName + " (Sei " + this.mySymbol + ")"); // Tradotto
                for (Button[] row : buttons) for (Button btn : row) if(btn!=null) btn.setText(" ");
                if (buttonLeave != null) buttonLeave.setDisable(false);
                System.out.println(getCurrentTimestamp()+" - GC (UI Rivincita): Griglia pulita. Stato griglia dipende dal prossimo YOUR_TURN."); // Tradotto
            });
        } else {
            System.out.println(getCurrentTimestamp() + " - GC: GameStart -> Inizio partita NORMALE."); // Tradotto
            if (isSetupComplete.get()) {
                System.out.println(getCurrentTimestamp()+" - GC: Elaborazione messaggi cache immediatamente dopo GameStart normale (Setup completo)."); // Tradotto
                processCachedMessages();
            } else {
                System.out.println(getCurrentTimestamp()+" - GC: Rimando elaborazione messaggi cache, setup non ancora completo."); // Tradotto
            }
        }
    }

    // Metodi Helper UI
    private void showInfo(String title, String content) { showAlert(Alert.AlertType.INFORMATION, title, content); }
    private void showError(String title, String content) { showAlert(Alert.AlertType.ERROR, title, content); }
    private void showAlert(Alert.AlertType type, String title, String content){
        if (!Platform.isFxApplicationThread()) { Platform.runLater(() -> showAlert(type, title, content)); return; }
        try {
            Alert alert = new Alert(type); alert.setTitle(title); alert.setHeaderText(null); alert.setContentText(content);
            Stage owner = getCurrentStage();
            if(owner != null && owner.isShowing()){ alert.initOwner(owner); alert.showAndWait(); }
            else { System.out.println("GC: Sopprimo alert '"+title+"' - nessuno stage proprietario valido."); } // Tradotto
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

} // Fine classe GameController