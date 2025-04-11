package org.trisclient.trisclient;

import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class NetworkService {

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean running = false;
    private final AtomicReference<ServerListener> listenerRef = new AtomicReference<>();
    private String currentListenerName = "null";
    private ExecutorService networkExecutor;

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    // Interfaccia ServerListener (nomi metodi non tradotti)
    public interface ServerListener {
        void onConnected();
        void onDisconnected(String reason);
        void onMessageReceived(String rawMessage);
        void onError(String message);
        void onNameRequested();
        void onNameAccepted();
        void onNameRejected(String reason);
        void onGamesList(List<GameInfo> games);
        void onActionConfirmed(String message);
        void onGameCreated(int gameId);
        void onJoinRequestSent(int gameId);
        void onJoinRequestReceived(String requesterName);
        void onJoinAccepted(int gameId, char symbol, String opponentName);
        void onJoinRejected(int gameId, String creatorName);
        void onGameStart(int gameId, char symbol, String opponentName);
        void onBoardUpdate(String[] board);
        void onYourTurn();
        void onGameOver(String result);
        void onOpponentLeft();
        void onRematchOffer();
        void onRematchAccepted(int gameId);
        void onRematchDeclined();
        void onOpponentRematchDecision(boolean opponentAccepted);
    }

    // GameInfo (toString tradotto)
    public static class GameInfo {
        public final int id;
        public final String creatorName;
        public final String state; // Lo stato viene dal server, NON tradurlo qui

        public GameInfo(int id, String creatorName, String state) {
            this.id = id;
            this.creatorName = creatorName;
            this.state = state;
        }
        @Override public String toString() {
            // Traduce la rappresentazione testuale, ma usa 'state' com'è
            return "Partita " + id + " (di " + creatorName + ") - " + state; // Tradotto
        }
    }

    public void setServerListener(ServerListener newListener) {
        String oldListenerName = this.currentListenerName;
        String newListenerName = (newListener != null) ? newListener.getClass().getSimpleName() + " ("+newListener.hashCode()+")" : "null";
        listenerRef.set(newListener);
        this.currentListenerName = newListenerName;
        System.out.println(getCurrentTimestamp() + " - NetworkService: *** setServerListener chiamato *** | Vecchio listener: " + oldListenerName + " | Nuovo listener: " + newListenerName); // Tradotto
    }

    public void connect(String host, int port, ServerListener initialListener) {
        if (!canAttemptConnect()) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: connect() chiamato ma impossibile tentare la connessione (già in esecuzione o executor attivo?)."); // Tradotto
            return;
        }

        setServerListener(initialListener);
        running = true;

        if (networkExecutor == null || networkExecutor.isShutdown()) {
            networkExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "NetworkListenerThread");
                t.setDaemon(true);
                return t;
            });
            System.out.println(getCurrentTimestamp()+" - NetworkService: Creato nuovo NetworkExecutor."); // Tradotto
        }

        networkExecutor.submit(() -> {
            System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Avvio task di connessione."); // Tradotto
            try {
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Connessione a " + host + ":" + port + "..."); // Tradotto
                socket = new Socket(host, port);
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Connessione stabilita."); // Tradotto

                Platform.runLater(() -> {
                    ServerListener currentListener = listenerRef.get();
                    if (currentListener != null) {
                        currentListener.onConnected();
                    } else {
                        System.err.println(getCurrentTimestamp()+" - NetworkService: Listener è NULL nel callback onConnected!"); // Tradotto
                    }
                });

                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Thread listener avvia ciclo. Listener attivo: " + currentListenerName); // Tradotto
                String serverMessage;
                while (running && (serverMessage = in.readLine()) != null) {
                    final String message = serverMessage;
                    System.out.println(getCurrentTimestamp() + " - RAW DAL SERVER: [" + message + "]"); // Tradotto

                    Platform.runLater(() -> {
                        ServerListener currentListener = listenerRef.get();
                        if (currentListener != null) {
                            parseServerMessage(message, currentListener);
                        } else {
                            System.err.println(getCurrentTimestamp() + " - NetworkService (in runLater): ERRORE - Nessun listener attivo per gestire messaggio: " + message); // Tradotto
                        }
                    });
                }
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Ciclo di lettura terminato. Motivo: running=" + running + " o readLine ha restituito null."); // Tradotto

                if (running) {
                    System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Rilevata chiusura inattesa connessione server (readLine ha restituito null mentre running)."); // Tradotto
                    handleDisconnection("Il server ha chiuso la connessione"); // Tradotto
                }

            } catch (SocketException e) {
                final String errorMsg = "Errore di connessione: " + e.getMessage(); // Tradotto
                System.err.println(getCurrentTimestamp() + " - NetworkService (in executor): SocketException: " + e.getMessage() + " | running="+running);
                if (running) {
                    handleDisconnection(errorMsg);
                } else {
                    System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Socket chiuso intenzionalmente o durante lo spegnimento."); // Tradotto
                }
            } catch (IOException e) {
                final String errorMsg = "Errore IO: " + e.getMessage(); // Tradotto
                System.err.println(getCurrentTimestamp() + " - NetworkService (in executor): IOException: " + e.getMessage() + " | running="+running);
                if (running) {
                    handleDisconnection(errorMsg);
                }
            } finally {
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Ingresso nel blocco finally."); // Tradotto
                closeResources();
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Task del listener terminato."); // Tradotto
            }
        });
        System.out.println(getCurrentTimestamp()+" - NetworkService: Task di connessione inviato all'executor."); // Tradotto
    }

    private void handleDisconnection(String reason) {
        System.out.println(getCurrentTimestamp()+" - NetworkService: handleDisconnection chiamato con motivo: "+reason); // Tradotto
        if (!running) {
            System.out.println(getCurrentTimestamp()+" - NetworkService: handleDisconnection ignorato perchè 'running' è già false."); // Tradotto
            return;
        }
        running = false;

        Platform.runLater(() -> {
            ServerListener currentListener = listenerRef.get();
            if (currentListener != null) {
                System.out.println(getCurrentTimestamp()+" - NetworkService (in runLater): Notifica al listener "+currentListener.getClass().getSimpleName()+" ("+currentListener.hashCode()+") della disconnessione: "+reason); // Tradotto
                currentListener.onDisconnected(reason); // Passa il motivo originale o tradotto
            } else {
                System.err.println(getCurrentTimestamp()+" - NetworkService: Listener è NULL durante la gestione della disconnessione!"); // Tradotto
            }
        });
    }


    private void parseServerMessage(String message, ServerListener currentListener) {
        if (message == null || message.trim().isEmpty()) return;
        if(currentListener == null){
            System.err.println(getCurrentTimestamp()+" - NetworkService: parseServerMessage - Listener è NULL! Impossibile processare: "+message); // Tradotto
            return;
        }

        try {
            if (message.startsWith("CMD:GET_NAME")) {
                currentListener.onNameRequested();
            } else if (message.startsWith("RESP:NAME_OK")) {
                currentListener.onNameAccepted();
            } else if (message.startsWith("ERROR:NAME_TAKEN")) {
                currentListener.onNameRejected("Nome già preso."); // Tradotto
            } else if (message.startsWith("RESP:GAMES_LIST;")) {
                List<GameInfo> games = new ArrayList<>();
                String content = message.substring("RESP:GAMES_LIST;".length());
                if (!content.isEmpty()) {
                    String[] gameEntries = content.split("\\|");
                    for (String entry : gameEntries) {
                        String[] parts = entry.split(",");
                        if (parts.length >= 3) {
                            try {
                                int id = Integer.parseInt(parts[0]);
                                String name = parts[1];
                                String state = parts[2]; // Non tradurre lo stato qui
                                games.add(new GameInfo(id, name, state));
                            } catch (NumberFormatException | IndexOutOfBoundsException e) {
                                System.err.println(getCurrentTimestamp() + " - Errore parsing voce partita nella lista: " + entry); // Tradotto
                            }
                        } else {
                            System.err.println(getCurrentTimestamp() + " - Voce partita malformata nella lista: " + entry); // Tradotto
                        }
                    }
                }
                currentListener.onGamesList(games);
            } else if (message.startsWith("RESP:CREATED ")) {
                try {
                    int gameId = Integer.parseInt(message.substring("RESP:CREATED ".length()).trim());
                    currentListener.onGameCreated(gameId);
                } catch (NumberFormatException e){
                    System.err.println(getCurrentTimestamp() + " - Errore parsing ID partita in CREATED: " + message); // Tradotto
                    currentListener.onError("Formato ID partita non valido dal server (CREATED)"); // Tradotto
                }
            } else if (message.startsWith("RESP:REQUEST_SENT ")) {
                try {
                    int gameId = Integer.parseInt(message.substring("RESP:REQUEST_SENT ".length()).trim());
                    currentListener.onJoinRequestSent(gameId);
                } catch (NumberFormatException e) {
                    System.err.println(getCurrentTimestamp() + " - Errore parsing ID partita REQUEST_SENT: " + message); // Tradotto
                    currentListener.onError("Formato non valido dal server (REQUEST_SENT)"); // Tradotto
                }
            } else if (message.startsWith("NOTIFY:JOIN_REQUEST ")) {
                String requesterName = message.substring("NOTIFY:JOIN_REQUEST ".length()).trim();
                if (!requesterName.isEmpty()) {
                    currentListener.onJoinRequestReceived(requesterName);
                } else {
                    System.err.println(getCurrentTimestamp() + " - JOIN_REQUEST malformato (nome vuoto): " + message); // Tradotto
                    currentListener.onError("Messaggio JOIN_REQUEST malformato dal server (nome vuoto)"); // Tradotto
                }
            } else if (message.startsWith("RESP:JOIN_ACCEPTED ")) {
                String[] parts = message.substring("RESP:JOIN_ACCEPTED ".length()).split(" ");
                if (parts.length >= 3) {
                    try {
                        int gameId = Integer.parseInt(parts[0]);
                        char symbol = parts[1].charAt(0);
                        String opponentName = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
                        currentListener.onJoinAccepted(gameId, symbol, opponentName);
                    } catch(NumberFormatException | IndexOutOfBoundsException e){
                        System.err.println(getCurrentTimestamp() + " - Errore parsing JOIN_ACCEPTED: " + message); // Tradotto
                        currentListener.onError("Formato non valido dal server (JOIN_ACCEPTED)"); // Tradotto
                    }
                } else {
                    System.err.println(getCurrentTimestamp() + " - Messaggio JOIN_ACCEPTED malformato: " + message); // Tradotto
                    currentListener.onError("Messaggio JOIN_ACCEPTED malformato dal server"); // Tradotto
                }
            } else if (message.startsWith("RESP:JOIN_REJECTED ")) {
                String[] parts = message.substring("RESP:JOIN_REJECTED ".length()).split(" ");
                if (parts.length >= 2) {
                    try {
                        int gameId = Integer.parseInt(parts[0]);
                        String creatorName = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
                        currentListener.onJoinRejected(gameId, creatorName);
                    } catch (NumberFormatException e) {
                        System.err.println(getCurrentTimestamp() + " - Errore parsing ID partita JOIN_REJECTED: " + message); // Tradotto
                        currentListener.onError("Formato non valido dal server (JOIN_REJECTED ID)"); // Tradotto
                    }
                } else {
                    System.err.println(getCurrentTimestamp() + " - Messaggio JOIN_REJECTED malformato: " + message); // Tradotto
                    currentListener.onError("Messaggio JOIN_REJECTED malformato dal server"); // Tradotto
                }
            } else if (message.startsWith("RESP:REJECT_OK ")) {
                String rejectedName = message.substring("RESP:REJECT_OK ".length()).trim();
                // Traduci il messaggio passato al listener
                currentListener.onActionConfirmed("Richiesta rifiutata da " + rejectedName); // Tradotto
            } else if (message.startsWith("NOTIFY:GAME_START ")) {
                String[] parts = message.substring("NOTIFY:GAME_START ".length()).split(" ");
                if (parts.length >= 3) {
                    try {
                        int gameId = Integer.parseInt(parts[0]);
                        char symbol = parts[1].charAt(0);
                        String opponentName = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
                        currentListener.onGameStart(gameId, symbol, opponentName);
                    } catch(NumberFormatException | IndexOutOfBoundsException e){
                        System.err.println(getCurrentTimestamp() + " - Errore parsing GAME_START: " + message); // Tradotto
                        currentListener.onError("Formato non valido dal server (GAME_START)"); // Tradotto
                    }
                } else {
                    System.err.println(getCurrentTimestamp() + " - Messaggio GAME_START malformato: " + message); // Tradotto
                    currentListener.onError("Messaggio GAME_START malformato dal server"); // Tradotto
                }
            } else if (message.startsWith("NOTIFY:BOARD ")) {
                String boardData = message.substring("NOTIFY:BOARD ".length());
                String[] boardCells = boardData.split(" ");
                if(boardCells.length == 9) {
                    currentListener.onBoardUpdate(boardCells);
                } else {
                    System.err.println(getCurrentTimestamp() + " - Messaggio BOARD malformato (lunghezza != 9): " + message); // Tradotto
                    currentListener.onError("Messaggio BOARD malformato dal server"); // Tradotto
                }
            } else if (message.startsWith("NOTIFY:YOUR_TURN")) {
                currentListener.onYourTurn();
            } else if (message.startsWith("NOTIFY:GAMEOVER ")) {
                String result = message.substring("NOTIFY:GAMEOVER ".length()).trim(); // Non tradurre WIN/LOSE/DRAW qui
                currentListener.onGameOver(result);
            } else if (message.startsWith("NOTIFY:OPPONENT_LEFT")) {
                currentListener.onOpponentLeft();
            } else if (message.startsWith("CMD:REMATCH_OFFER")) {
                currentListener.onRematchOffer();
            } else if (message.startsWith("RESP:REMATCH_ACCEPTED ")) {
                String content = message.substring("RESP:REMATCH_ACCEPTED ".length());
                String[] parts = content.split(" ");
                if(parts.length > 0) {
                    try {
                        int gameId = Integer.parseInt(parts[0]);
                        currentListener.onRematchAccepted(gameId);
                    } catch (NumberFormatException e) {
                        System.err.println(getCurrentTimestamp() + " - Errore parsing ID partita REMATCH_ACCEPTED: " + message); // Tradotto
                        currentListener.onError("Formato non valido dal server (REMATCH_ACCEPTED ID)"); // Tradotto
                    }
                } else {
                    System.err.println(getCurrentTimestamp() + " - Messaggio REMATCH_ACCEPTED malformato: " + message); // Tradotto
                    currentListener.onError("Messaggio REMATCH_ACCEPTED malformato dal server"); // Tradotto
                }
            } else if (message.startsWith("RESP:REMATCH_DECLINED")) {
                currentListener.onRematchDeclined();
            } else if (message.startsWith("NOTIFY:OPPONENT_ACCEPTED_REMATCH")) {
                currentListener.onOpponentRematchDecision(true);
            } else if (message.startsWith("NOTIFY:OPPONENT_DECLINED")) {
                currentListener.onOpponentRematchDecision(false);
            } else if (message.startsWith("NOTIFY:SERVER_SHUTDOWN")) {
                System.out.println(getCurrentTimestamp()+" - NetworkService: Gestione messaggio Spegnimento Server."); // Tradotto
                handleDisconnection("Il server si sta spegnendo"); // Tradotto
                closeResources();
            } else if (message.startsWith("ERROR:")) {
                String errorMsg = message.substring("ERROR:".length()).trim();
                // Prova a tradurre errori comuni, altrimenti passa l'originale
                String translatedError = errorMsg;
                if (errorMsg.contains("Unknown command or invalid state")) translatedError = "Comando sconosciuto o stato non valido";
                else if (errorMsg.contains("Not your turn")) translatedError = "Non è il tuo turno";
                else if (errorMsg.contains("Invalid move")) translatedError = "Mossa non valida";
                else if (errorMsg.contains("Game not found")) translatedError = "Partita non trovata";
                else if (errorMsg.contains("Game is full")) translatedError = "Partita piena";
                else if (errorMsg.contains("Invalid request")) translatedError = "Richiesta non valida";
                // Passa il messaggio (originale o tradotto)
                currentListener.onError(translatedError + " (" + errorMsg + ")"); // Aggiungi l'originale per debug? O solo tradotto
            } else {
                System.out.println(getCurrentTimestamp()+" - NetworkService: Ricevuto tipo di messaggio non gestito."); // Tradotto
                currentListener.onMessageReceived(message);
            }
        } catch (Exception e) {
            System.err.println(getCurrentTimestamp() + " - ERRORE CRITICO PARSING messaggio server: [" + message + "]"); // Tradotto
            e.printStackTrace();
            try {
                currentListener.onError("Errore client nel parsing del messaggio: " + e.getMessage()); // Tradotto
            } catch (Exception innerE) {
                System.err.println(getCurrentTimestamp() + " - Errore chiamata listener.onError dopo eccezione parsing!"); // Tradotto
                innerE.printStackTrace();
            }
        }
    }


    public void sendMessage(String message) {
        final String msgToSend = message;
        PrintWriter currentOut = this.out;
        Socket currentSocket = this.socket;

        if (running && currentOut != null && currentSocket != null && currentSocket.isConnected() && !currentSocket.isClosed() && !currentOut.checkError()) {
            try {
                System.out.println(getCurrentTimestamp() + " - NetworkService (invio diretto): Invio: [" + msgToSend + "]"); // Tradotto
                currentOut.println(msgToSend);
                if (currentOut.checkError()) {
                    System.err.println(getCurrentTimestamp() + " - NetworkService (invio diretto): Errore PrintWriter rilevato DOPO invio. Probabilmente disconnesso."); // Tradotto
                    if(running) {
                        handleDisconnection("Invio fallito (errore PrintWriter)"); // Tradotto
                    }
                }
            } catch (Exception e) {
                System.err.println(getCurrentTimestamp() + " - NetworkService (invio diretto): Eccezione durante invio: "+e.getMessage()); // Tradotto
                if(running) {
                    handleDisconnection("Invio fallito (Eccezione)"); // Tradotto
                }
            }
        } else {
            System.err.println(getCurrentTimestamp() + " - Impossibile inviare messaggio, stato connessione non valido. Messaggio: [" + msgToSend + "]"); // Tradotto
            System.err.println(getCurrentTimestamp() + " - Controllo Invio: running="+running+", socket="+(currentSocket != null)+", socket.isConnected="+(currentSocket != null ? currentSocket.isConnected():"N/D")+", socket.isClosed="+(currentSocket != null ? currentSocket.isClosed():"N/D")+", out="+(currentOut!=null)+", out.checkError="+(currentOut != null ? currentOut.checkError() : "N/D"));
            if(!running){
                Platform.runLater(() -> {
                    ServerListener l = listenerRef.get();
                    if(l != null) l.onDisconnected("Tentativo di invio mentre disconnesso"); // Tradotto
                });
            }
        }
    }
    // Metodi send* non modificano stringhe utente

    public void sendName(String name) { sendMessage("NAME " + name); }
    public void sendListRequest() { sendMessage("LIST"); }
    public void sendCreateGame() { sendMessage("CREATE"); }
    public void sendJoinRequest(int gameId) { sendMessage("JOIN_REQUEST " + gameId); }
    public void sendAcceptRequest(String playerName) { sendMessage("ACCEPT " + playerName); }
    public void sendRejectRequest(String playerName) { sendMessage("REJECT " + playerName); }
    public void sendMove(int row, int col) { sendMessage("MOVE " + row + " " + col); }
    public void sendQuit() { sendMessage("QUIT"); }
    public void sendRematchChoice(boolean accept) {
        sendMessage(accept ? "REMATCH YES" : "REMATCH NO");
    }

    public void disconnect() {
        System.out.println(getCurrentTimestamp() + " - NetworkService: disconnect() CHIAMATO."); // Tradotto
        if (!running) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: disconnect() ignorato, già non in esecuzione."); // Tradotto
            shutdownExecutor();
            return;
        }

        handleDisconnection("Disconnesso dal client"); // Tradotto
        closeResources();
        shutdownExecutor();

        System.out.println(getCurrentTimestamp() + " - NetworkService: disconnect() terminato."); // Tradotto
    }

    private synchronized void closeResources() {
        System.out.println(getCurrentTimestamp() + " - NetworkService: closeResources() CHIAMATO."); // Tradotto
        if (out != null) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Chiusura PrintWriter."); // Tradotto
            out.close();
            out = null;
        }
        if (socket != null && !socket.isClosed()) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Chiusura Socket."); // Tradotto
            try {
                socket.close();
            } catch (IOException e) {
                System.err.println(getCurrentTimestamp() + " - NetworkService: Errore chiusura socket: " + e.getMessage()); // Tradotto
            }
            socket = null;
        }
        if (in != null) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Chiusura BufferedReader."); // Tradotto
            try {
                in.close();
            } catch (IOException e) {
                System.err.println(getCurrentTimestamp() + " - NetworkService: Errore chiusura BufferedReader (può essere atteso dopo chiusura socket): " + e.getMessage()); // Tradotto
            }
            in = null;
        }
        System.out.println(getCurrentTimestamp() + " - NetworkService: Risorse di rete chiuse."); // Tradotto
    }

    private void shutdownExecutor() {
        if (networkExecutor != null && !networkExecutor.isShutdown()) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Spegnimento NetworkExecutor..."); // Tradotto
            networkExecutor.shutdown();
            try {
                if (!networkExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    System.err.println(getCurrentTimestamp() + " - NetworkService: Executor non terminato correttamente, forzatura spegnimento."); // Tradotto
                    networkExecutor.shutdownNow();
                } else {
                    System.out.println(getCurrentTimestamp() + " - NetworkService: NetworkExecutor terminato correttamente."); // Tradotto
                }
            } catch (InterruptedException ie) {
                System.err.println(getCurrentTimestamp() + " - NetworkService: Interrotto durante attesa terminazione executor."); // Tradotto
                networkExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isConnected() {
        return running && socket != null && socket.isConnected() && !socket.isClosed() && out != null && !out.checkError() ;
    }
    public ServerListener getCurrentListener() {
        return listenerRef.get();
    }
    public boolean canAttemptConnect() {
        return !running || (networkExecutor == null || networkExecutor.isShutdown() || networkExecutor.isTerminated());
    }
    public void cleanupExecutor() {
        System.out.println(getCurrentTimestamp() + " - NetworkService: cleanupExecutor() chiamato."); // Tradotto
        shutdownExecutor();
        networkExecutor = null;
    }

} // Fine classe NetworkService