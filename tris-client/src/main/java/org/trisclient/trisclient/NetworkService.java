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
import java.util.concurrent.ExecutorService; // Importa
import java.util.concurrent.Executors;   // Importa
import java.util.concurrent.TimeUnit;    // Importa
import java.util.concurrent.atomic.AtomicReference; // Per listener thread-safe

public class NetworkService {

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private volatile boolean running = false;
    // Usa AtomicReference per impostare/leggere il listener in modo thread-safe
    private final AtomicReference<ServerListener> listenerRef = new AtomicReference<>();
    private String currentListenerName = "null"; // Per logging
    private ExecutorService networkExecutor; // Per gestire il thread del listener

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private String getCurrentTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMATTER);
    }

    // Interfaccia e GameInfo rimangono invariate...
    public interface ServerListener {
        void onConnected();
        void onDisconnected(String reason);
        void onMessageReceived(String rawMessage); // Messaggi non parsati
        // --- Lobby Callbacks ---
        void onNameRequested();
        void onNameAccepted();
        void onGamesList(List<GameInfo> games);
        void onGameCreated(int gameId);
        void onJoinOk(int gameId, char symbol, String opponentName); // Conferma join
        // --- Game Callbacks ---
        void onGameStart(int gameId, char symbol, String opponentName); // Notifica inizio
        void onBoardUpdate(String[] board); // Aggiornamento griglia
        void onYourTurn(); // Notifica turno
        void onGameOver(String result); // Fine partita (WIN, LOSE, DRAW)
        void onOpponentLeft(); // Avversario disconnesso
        // --- Error Callback ---
        void onError(String message); // Messaggio di errore dal server
    }

    public static class GameInfo {
        public final int id;
        public final String creatorName;
        public GameInfo(int id, String creatorName) { this.id = id; this.creatorName = creatorName; }
        @Override public String toString() { return "Partita " + id + " (creata da " + creatorName + ")"; }
    }
    // ----------------------------------------------------


    public void setServerListener(ServerListener newListener) {
        String oldListenerName = this.currentListenerName;
        String newListenerName = (newListener != null) ? newListener.getClass().getSimpleName() + " ("+newListener.hashCode()+")" : "null";
        // Imposta atomicamente il nuovo listener
        listenerRef.set(newListener);
        this.currentListenerName = newListenerName;
        System.out.println(getCurrentTimestamp() + " - NetworkService: *** setServerListener called *** | Old listener: " + oldListenerName + " | New listener: " + newListenerName);
    }

    public void connect(String host, int port, ServerListener initialListener) {
        if (running) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Already connected or connecting.");
            return;
        }
        // Imposta il listener iniziale atomicamente
        setServerListener(initialListener);
        running = true; // Imposta running a true PRIMA di avviare il thread

        // Crea l'executor se non esiste o è terminato
        if (networkExecutor == null || networkExecutor.isShutdown()) {
            networkExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "NetworkListenerThread");
                t.setDaemon(true); // Consente all'app di uscire anche se il thread è vivo
                return t;
            });
            System.out.println(getCurrentTimestamp()+" - NetworkService: Created new NetworkExecutor.");
        }

        // Sottopone il task di connessione e ascolto all'executor
        networkExecutor.submit(() -> {
            System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Starting connection task.");
            try {
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Connecting to " + host + ":" + port + "...");
                socket = new Socket(host, port);
                out = new PrintWriter(socket.getOutputStream(), true); // Auto-flush è importante
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Connection established.");

                // Notifica l'UI della connessione avvenuta usando Platform.runLater
                Platform.runLater(() -> {
                    ServerListener currentListener = listenerRef.get(); // Ottieni listener corrente
                    if (currentListener != null) {
                        currentListener.onConnected();
                    } else {
                        System.err.println(getCurrentTimestamp()+" - NetworkService: Listener is NULL in onConnected callback!");
                    }
                });

                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Listener thread starting loop. Active listener: " + currentListenerName);
                String serverMessage;
                // Ciclo di lettura messaggi dal server
                while (running && (serverMessage = in.readLine()) != null) {
                    final String message = serverMessage; // Copia finale per il lambda
                    System.out.println(getCurrentTimestamp() + " - RAW FROM SERVER: [" + message + "]");

                    // Esegui il parsing e la notifica al listener sul thread JavaFX
                    Platform.runLater(() -> {
                        ServerListener currentListener = listenerRef.get(); // Ottieni listener corrente
                        if (currentListener != null) {
                            // System.out.println(getCurrentTimestamp() + " - NetworkService (in runLater): Processing message with listener -> " + currentListener.getClass().getSimpleName()+" ("+currentListener.hashCode()+")");
                            parseServerMessage(message, currentListener); // Passa il listener a parse
                        } else {
                            System.err.println(getCurrentTimestamp() + " - NetworkService (in runLater): ERROR - No active listener to handle message: " + message);
                        }
                    });
                }
                // Uscito dal ciclo di lettura
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Read loop finished. Reason: running=" + running + " or readLine returned null.");

            } catch (SocketException e) {
                // Gestisce errori di socket (es. connessione chiusa, reset)
                final String errorMsg = "Connection error: " + e.getMessage();
                System.err.println(getCurrentTimestamp() + " - NetworkService (in executor): SocketException: " + e.getMessage() + " | running="+running);
                if (running) { // Se l'errore avviene mentre dovremmo essere attivi
                    handleDisconnection(errorMsg); // Gestisce la disconnessione inattesa
                } else {
                    System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Socket closed intentionally.");
                }
            } catch (IOException e) {
                // Gestisce altri errori di I/O
                final String errorMsg = "IO error: " + e.getMessage();
                System.err.println(getCurrentTimestamp() + " - NetworkService (in executor): IOException: " + e.getMessage() + " | running="+running);
                // e.printStackTrace(); // Decommenta per debug dettagliato
                if (running) {
                    handleDisconnection(errorMsg);
                }
            } finally {
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Entering finally block.");
                // Assicurati che running sia false e chiudi le risorse SEMPRE
                running = false; // Messo qui per sicurezza assoluta
                closeResources(); // Chiudi socket e stream
                System.out.println(getCurrentTimestamp() + " - NetworkService (in executor): Listener task finished.");
                // La notifica di disconnessione viene fatta da handleDisconnection o da disconnect()
            }
        }); // Fine submit
        System.out.println(getCurrentTimestamp()+" - NetworkService: Connection task submitted to executor.");
    }

    // Helper per gestire la notifica di disconnessione in caso di errore/chiusura inattesa
    private void handleDisconnection(String reason) {
        System.out.println(getCurrentTimestamp()+" - NetworkService: handleDisconnection called with reason: "+reason);
        // Controlla 'running' per evitare doppie notifiche se disconnect() è stato chiamato contemporaneamente
        if (!running) {
            System.out.println(getCurrentTimestamp()+" - NetworkService: handleDisconnection ignored as 'running' is already false.");
            return;
        }
        running = false; // Segnala che non siamo più attivi

        // Notifica il listener corrente (se esiste) sul thread JavaFX
        Platform.runLater(() -> {
            ServerListener currentListener = listenerRef.get(); // Ottieni listener corrente
            if (currentListener != null) {
                System.out.println(getCurrentTimestamp()+" - NetworkService (in runLater): Notifying listener "+currentListener.getClass().getSimpleName()+" ("+currentListener.hashCode()+") of disconnection: "+reason);
                currentListener.onDisconnected(reason);
            } else {
                System.err.println(getCurrentTimestamp()+" - NetworkService: Listener is NULL when handling disconnection!");
            }
        });
        // Le risorse sono già state (o verranno a breve) chiuse dal blocco finally del thread executor
    }


    // Modificato per ricevere il listener come argomento, garantendo che usiamo quello corretto
    private void parseServerMessage(String message, ServerListener currentListener) {
        if (message == null || message.trim().isEmpty()) return;
        if(currentListener == null){ // Doppio controllo
            System.err.println(getCurrentTimestamp()+" - NetworkService: parseServerMessage - Listener is NULL! Cannot process: "+message);
            return;
        }

        try {
            if (message.startsWith("CMD:GET_NAME")) {
                currentListener.onNameRequested();
            } else if (message.startsWith("RESP:NAME_OK")) {
                currentListener.onNameAccepted();
            } else if (message.startsWith("RESP:GAMES_LIST;")) {
                List<GameInfo> games = new ArrayList<>();
                String content = message.substring("RESP:GAMES_LIST;".length());
                if (!content.isEmpty()) {
                    String[] gameEntries = content.split("\\|"); // Usa \\ per escape |
                    for (String entry : gameEntries) {
                        String[] parts = entry.split(",");
                        if (parts.length >= 2) { // Almeno ID e Nome
                            try {
                                int id = Integer.parseInt(parts[0]);
                                String name = parts[1];
                                games.add(new GameInfo(id, name));
                            } catch (NumberFormatException e) {
                                System.err.println(getCurrentTimestamp() + " - Error parsing game ID in list: " + entry);
                            }
                        } else {
                            System.err.println(getCurrentTimestamp() + " - Malformed game entry in list: " + entry);
                        }
                    }
                }
                currentListener.onGamesList(games);
            } else if (message.startsWith("RESP:CREATED ")) {
                try {
                    int gameId = Integer.parseInt(message.substring("RESP:CREATED ".length()).trim());
                    currentListener.onGameCreated(gameId);
                } catch (NumberFormatException e){
                    System.err.println(getCurrentTimestamp() + " - Error parsing game ID in CREATED: " + message);
                    currentListener.onError("Invalid game ID format from server (CREATED)");
                }
            } else if (message.startsWith("RESP:JOIN_OK ")) {
                String[] parts = message.substring("RESP:JOIN_OK ".length()).split(" ");
                if (parts.length >= 3) {
                    try {
                        int gameId = Integer.parseInt(parts[0]);
                        char symbol = parts[1].charAt(0);
                        // Il nome può contenere spazi, prendi il resto
                        String opponentName = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
                        currentListener.onJoinOk(gameId, symbol, opponentName);
                    } catch(NumberFormatException | IndexOutOfBoundsException e){
                        System.err.println(getCurrentTimestamp() + " - Error parsing JOIN_OK: " + message);
                        currentListener.onError("Invalid format from server (JOIN_OK)");
                    }
                } else {
                    System.err.println(getCurrentTimestamp() + " - Malformed JOIN_OK message: " + message);
                    currentListener.onError("Malformed JOIN_OK message from server");
                }
            } else if (message.startsWith("NOTIFY:GAME_START ")) {
                String[] parts = message.substring("NOTIFY:GAME_START ".length()).split(" ");
                if (parts.length >= 3) {
                    try {
                        int gameId = Integer.parseInt(parts[0]);
                        char symbol = parts[1].charAt(0);
                        String opponentName = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
                        currentListener.onGameStart(gameId, symbol, opponentName);
                    } catch(NumberFormatException | IndexOutOfBoundsException e){
                        System.err.println(getCurrentTimestamp() + " - Error parsing GAME_START: " + message);
                        currentListener.onError("Invalid format from server (GAME_START)");
                    }
                } else {
                    System.err.println(getCurrentTimestamp() + " - Malformed GAME_START message: " + message);
                    currentListener.onError("Malformed GAME_START message from server");
                }
            } else if (message.startsWith("NOTIFY:BOARD ")) {
                String boardData = message.substring("NOTIFY:BOARD ".length());
                String[] boardCells = boardData.split(" ");
                if(boardCells.length == 9) { // Controllo di validità base
                    currentListener.onBoardUpdate(boardCells);
                } else {
                    System.err.println(getCurrentTimestamp() + " - Malformed BOARD message (length != 9): " + message);
                    currentListener.onError("Malformed BOARD message from server");
                }
            } else if (message.startsWith("NOTIFY:YOUR_TURN")) {
                currentListener.onYourTurn();
            } else if (message.startsWith("NOTIFY:GAMEOVER ")) {
                String result = message.substring("NOTIFY:GAMEOVER ".length()).trim();
                currentListener.onGameOver(result);
            } else if (message.startsWith("NOTIFY:OPPONENT_LEFT")) {
                currentListener.onOpponentLeft();
            } else if (message.startsWith("NOTIFY:SERVER_SHUTDOWN")) {
                System.out.println(getCurrentTimestamp()+" - NetworkService: Handling Server Shutdown message.");
                // Chiama handleDisconnection per notificare il listener e pulire
                handleDisconnection("Server is shutting down");
                // Chiudi il socket qui per interrompere il loop di lettura immediatamente
                closeResources();

            } else if (message.startsWith("ERROR:")) {
                String errorMsg = message.substring("ERROR:".length()).trim();
                currentListener.onError(errorMsg);
            } else {
                // Messaggio non riconosciuto
                System.out.println(getCurrentTimestamp()+" - NetworkService: Received unhandled message type.");
                currentListener.onMessageReceived(message);
            }
        } catch (Exception e) {
            // Errore generico durante il parsing
            System.err.println(getCurrentTimestamp() + " - CRITICAL Error PARSING server message: [" + message + "]");
            e.printStackTrace();
            try {
                currentListener.onError("Client error parsing message: " + e.getMessage());
            } catch (Exception innerE) {
                System.err.println(getCurrentTimestamp() + " - Error calling listener.onError after parsing exception!");
                innerE.printStackTrace();
            }
        }
    }

    // Invia un messaggio AL SERVER
    public void sendMessage(String message) {
        final String msgToSend = message; // Copia per logging

        // Controlla se possiamo inviare
        PrintWriter currentOut = this.out; // Copia locale per thread-safety
        Socket currentSocket = this.socket;

        // Verifica se siamo 'running', se out è valido e se il socket è connesso e non chiuso
        if (running && currentOut != null && currentSocket != null && currentSocket.isConnected() && !currentSocket.isClosed() && !currentOut.checkError()) {
            // --- MODIFICA CHIAVE: ESEGUI L'INVIO DIRETTAMENTE ---
            // Questo evita potenziali deadlock con l'executor usato per leggere.
            // L'invio avviene sul thread chiamante (es. il thread JavaFX).
            try {
                System.out.println(getCurrentTimestamp() + " - NetworkService (direct send): Sending: [" + msgToSend + "]");
                currentOut.println(msgToSend);
                // checkError() verifica se si sono verificati errori nello stream *dopo* l'ultimo flush (implicito con println)
                if (currentOut.checkError()) {
                    System.err.println(getCurrentTimestamp() + " - NetworkService (direct send): PrintWriter error detected AFTER sending. Likely disconnected.");
                    // Se c'è un errore qui, la connessione è probabilmente persa.
                    // handleDisconnection verrà chiamato dal loop di lettura quando fallirà.
                    // Evitiamo di chiamarlo qui per non avere doppie notifiche.
                    running = false; // Segnala comunque che non dovremmo più essere attivi
                } else {
                    // System.out.println(getCurrentTimestamp() + " - NetworkService (direct send): Message sent successfully.");
                }
            } catch (Exception e) {
                // Cattura eccezioni impreviste durante l'invio diretto
                System.err.println(getCurrentTimestamp() + " - NetworkService (direct send): Exception during send: "+e.getMessage());
                running = false; // Probabilmente disconnessi
                // Anche qui, handleDisconnection verrà chiamato dal read loop.
            }
            // --- FINE INVIO DIRETTO ---
        } else {
            // Log del motivo per cui non possiamo inviare
            System.err.println(getCurrentTimestamp() + " - Cannot send message, connection state invalid. Message: [" + msgToSend + "]");
            System.err.println(getCurrentTimestamp() + " - Send Check: running="+running+", socket="+(currentSocket != null)+", socket.isConnected="+(currentSocket != null ? currentSocket.isConnected():"N/A")+", socket.isClosed="+(currentSocket != null ? currentSocket.isClosed():"N/A")+", out="+(currentOut!=null)+", out.checkError="+(currentOut != null ? currentOut.checkError() : "N/A"));
            // Non notificare errore qui, potrebbe essere una disconnessione normale in corso
        }
    }

    // --- Metodi specifici per inviare comandi ---
    // Questi metodi usano sendMessage internamente
    public void sendName(String name) { sendMessage("NAME " + name); }
    public void sendListRequest() { sendMessage("LIST"); }
    public void sendCreateGame() { sendMessage("CREATE"); }
    public void sendJoinGame(int gameId) { sendMessage("JOIN " + gameId); }
    public void sendMove(int row, int col) { sendMessage("MOVE " + row + " " + col); }
    public void sendQuit() { sendMessage("QUIT"); } // Il client vuole uscire
    // --------------------------------------------


    // Chiamato dall'applicazione (es. alla chiusura della finestra) per chiudere la connessione
    public void disconnect() {
        System.out.println(getCurrentTimestamp() + " - NetworkService: disconnect() CALLED.");
        // Controlla se siamo effettivamente in esecuzione per evitare chiamate multiple
        if (!running) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: disconnect() ignored, already not running.");
            shutdownExecutor(); // Assicurati che l'executor sia chiuso comunque
            return;
        }

        running = false; // Segnala al thread di lettura di terminare

        // Chiudi le risorse di rete PRIMA di notificare il listener
        closeResources();

        // Ferma il thread pool in modo pulito
        shutdownExecutor();

        // Notifica l'UI della disconnessione intenzionale (sul thread FX)
        Platform.runLater(() -> {
            ServerListener currentListener = listenerRef.get(); // Ottieni listener corrente
            if(currentListener != null) {
                System.out.println(getCurrentTimestamp()+" - NetworkService (in runLater): Notifying listener "+currentListener.getClass().getSimpleName()+" ("+currentListener.hashCode()+") of intentional disconnect.");
                currentListener.onDisconnected("Disconnected by client");
            } else {
                System.err.println(getCurrentTimestamp()+" - NetworkService: Listener is NULL during intentional disconnect!");
            }
        });
        System.out.println(getCurrentTimestamp() + " - NetworkService: disconnect() finished.");
    }

    // Metodo Sincronizzato per chiudere le risorse di rete in modo sicuro
    private synchronized void closeResources() {
        System.out.println(getCurrentTimestamp() + " - NetworkService: closeResources() CALLED.");
        if (out != null) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Closing PrintWriter.");
            out.close(); // Chiudere PrintWriter chiude anche l'OutputStream sottostante
            out = null; // Imposta a null
        }
        // Non c'è bisogno di chiudere InputStreamReader esplicitamente se chiudiamo il socket
        if (in != null) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Closing BufferedReader.");
            try {
                in.close(); // Chiude anche l'InputStreamReader sottostante
            } catch (IOException e) {
                System.err.println(getCurrentTimestamp() + " - NetworkService: Error closing BufferedReader: " + e.getMessage());
            }
            in = null; // Imposta a null
        }
        if (socket != null && !socket.isClosed()) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Closing Socket.");
            try {
                socket.close(); // Chiude input e output stream e libera la porta
            } catch (IOException e) {
                System.err.println(getCurrentTimestamp() + " - NetworkService: Error closing socket: " + e.getMessage());
            }
            socket = null; // Imposta a null
        }
        System.out.println(getCurrentTimestamp() + " - NetworkService: Network resources closed.");
    }

    // Metodo per chiudere l'ExecutorService in modo pulito
    private void shutdownExecutor() {
        if (networkExecutor != null && !networkExecutor.isShutdown()) {
            System.out.println(getCurrentTimestamp() + " - NetworkService: Shutting down NetworkExecutor...");
            networkExecutor.shutdown(); // Disabilita l'accettazione di nuove task
            try {
                // Attendi un breve periodo per la terminazione delle task in corso
                if (!networkExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    System.err.println(getCurrentTimestamp() + " - NetworkService: Executor did not terminate gracefully, forcing shutdown.");
                    networkExecutor.shutdownNow(); // Tenta di interrompere le task attive
                } else {
                    System.out.println(getCurrentTimestamp() + " - NetworkService: NetworkExecutor terminated gracefully.");
                }
            } catch (InterruptedException ie) {
                System.err.println(getCurrentTimestamp() + " - NetworkService: Interrupted while waiting for executor termination.");
                networkExecutor.shutdownNow(); // Forza la chiusura se interrotto
                Thread.currentThread().interrupt(); // Re-imposta lo stato di interruzione
            }
        } else {
            System.out.println(getCurrentTimestamp() + " - NetworkService: shutdownExecutor - Executor was null or already shut down.");
        }
        networkExecutor = null; // Imposta a null dopo lo shutdown
    }


    // Metodo per verificare lo stato della connessione
    public boolean isConnected() {
        // Controlla 'running', il socket e lo stream di output
        return running && socket != null && socket.isConnected() && !socket.isClosed() && out != null && !out.checkError() ;
    }

    // Getter per il listener (utile per debug)
    public ServerListener getCurrentListener() {
        return listenerRef.get();
    }
}