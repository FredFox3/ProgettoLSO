#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <signal.h>
#include <stdbool.h>
#include <time.h>
#include <sys/time.h>
#include <pthread.h> // Per i thread

#define PORT 12345
#define BUFFER_SIZE 1024
#define MAX_TOTAL_CLIENTS 10 // Max client connessi *contemporaneamente* al server
#define MAX_GAMES 5         // Max partite *attive* contemporaneamente
#define MAX_NAME_LEN 32

// Stati del Client
typedef enum {
    CLIENT_STATE_CONNECTED, // Appena connesso, prima di mandare il nome
    CLIENT_STATE_LOBBY,     // Connesso, ha mandato il nome, in attesa di comandi
    CLIENT_STATE_WAITING,   // Ha creato una partita, aspetta un avversario
    CLIENT_STATE_PLAYING    // Sta giocando una partita
} ClientState;

// Stati della Partita
typedef enum {
    GAME_STATE_EMPTY,        // Slot non usato
    GAME_STATE_WAITING,      // In attesa del secondo giocatore
    GAME_STATE_IN_PROGRESS,  // Partita in corso
    GAME_STATE_FINISHED      // Partita terminata (da implementare meglio stati specifici)
} GameState;

// Simboli Cella
typedef enum { CELL_EMPTY, CELL_X, CELL_O } Cell;

// Info Partita
typedef struct {
    int id;
    GameState state;
    Cell board[3][3];
    int player1_fd;
    int player2_fd;
    int current_turn_fd; // FD del giocatore di turno
    char player1_name[MAX_NAME_LEN];
    char player2_name[MAX_NAME_LEN];
    // Aggiungere qui info sul creatore se serve per Accetta/Rifiuta
} GameInfo;

// Info Client
typedef struct {
    int fd;
    ClientState state;
    char name[MAX_NAME_LEN];
    int game_id; // ID della partita a cui partecipa (0 se nessuna)
    bool active; // Flag per indicare se lo slot client è in uso
} ClientInfo;

// --- Variabili Globali ---
GameInfo games[MAX_GAMES];
ClientInfo clients[MAX_TOTAL_CLIENTS];
int server_fd = -1;
volatile sig_atomic_t keep_running = 1;
int next_game_id = 1; // Contatore per ID univoci partite

// Mutex per proteggere l'accesso alle liste globali
pthread_mutex_t client_list_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t game_list_mutex = PTHREAD_MUTEX_INITIALIZER;

// --- Funzioni Utilità Log ---
void get_timestamp(char *buffer, size_t len) {
    struct timeval tv;
    struct tm tm_info;
    gettimeofday(&tv, NULL);
    localtime_r(&tv.tv_sec, &tm_info);
    strftime(buffer, len, "%H:%M:%S", &tm_info);
    int ms = tv.tv_usec / 1000;
    snprintf(buffer + strlen(buffer), len - strlen(buffer), ".%03d", ms);
}

#define LOG(...) do { \
    char timestamp[30]; \
    get_timestamp(timestamp, sizeof(timestamp)); \
    printf("%s - ", timestamp); \
    printf(__VA_ARGS__); \
    fflush(stdout); \
} while(0)

#define LOG_PERROR(msg) do { \
    char timestamp[30]; \
    get_timestamp(timestamp, sizeof(timestamp)); \
    fprintf(stderr, "%s - ", timestamp); \
    fflush(stderr); \
    perror(msg); \
} while(0)

// --- Funzioni di Gestione Partita ---
void init_board(Cell board[3][3]) {
    memset(board, CELL_EMPTY, sizeof(Cell) * 3 * 3);
}

// Converte la board in una stringa per l'invio
void board_to_string(Cell board[3][3], char *out_str, size_t max_len) {
    // Formato semplice: "X O EMPTY..." separati da spazio
    out_str[0] = '\0';
    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 3; ++c) {
            char cell_char;
            switch (board[r][c]) {
                case CELL_X: cell_char = 'X'; break;
                case CELL_O: cell_char = 'O'; break;
                default:     strcpy(out_str + strlen(out_str), "EMPTY"); goto next_cell; // Usa strcpy per EMPTY
            }
            char cell_str[3]; // Abbastanza per "X " o "O "
            snprintf(cell_str, sizeof(cell_str), "%c", cell_char);
            strncat(out_str, cell_str, max_len - strlen(out_str) -1);

            next_cell:
            if (r < 2 || c < 2) {
                 strncat(out_str, " ", max_len - strlen(out_str) -1);
            }
        }
    }
}


bool check_winner(Cell board[3][3], Cell player) {
    // (Codice check_winner originale adattato per CELL_*)
     for (int i = 0; i < 3; i++) {
        if (board[i][0] == player && board[i][1] == player && board[i][2] == player) return true;
        if (board[0][i] == player && board[1][i] == player && board[2][i] == player) return true;
    }
    if (board[0][0] == player && board[1][1] == player && board[2][2] == player) return true;
    if (board[0][2] == player && board[1][1] == player && board[2][0] == player) return true;
    return false;
}

bool board_full(Cell board[3][3]) {
    // (Codice board_full originale adattato per CELL_*)
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++)
            if (board[i][j] == CELL_EMPTY) return false;
    return true;
}

// Invia un messaggio a un client specifico (thread-safe se fd è valido)
// Ritorna false in caso di errore di invio
bool send_to_client(int client_fd, const char* message) {
    if (client_fd < 0) return false;
    LOG("DEBUG: Sending to fd %d: [%s]\n", client_fd, message);
    ssize_t bytes_sent = send(client_fd, message, strlen(message), MSG_NOSIGNAL);
    if (bytes_sent <= 0) {
        if (bytes_sent == 0 || errno == EPIPE || errno == ECONNRESET) {
            LOG("Send failed (client fd %d likely disconnected): %s\n", client_fd, strerror(errno));
        } else {
            LOG_PERROR("Send failed");
        }
        return false;
    }
    return true;
}

// Trova un client tramite fd (necessita mutex esternamente se si modifica la lista)
int find_client_index(int fd) {
    for (int i = 0; i < MAX_TOTAL_CLIENTS; ++i) {
        if (clients[i].active && clients[i].fd == fd) {
            return i;
        }
    }
    return -1;
}

// Trova una partita tramite id (necessita mutex esternamente se si modifica la lista)
int find_game_index(int game_id) {
     if (game_id <= 0) return -1;
    for (int i = 0; i < MAX_GAMES; ++i) {
        if (games[i].state != GAME_STATE_EMPTY && games[i].id == game_id) {
            return i;
        }
    }
    return -1;
}


// --- Logica di Gestione Client (Eseguita in un Thread Separato) ---
void *handle_client(void *arg) {
    int client_index = *((int*)arg);
    free(arg); // Libera memoria allocata per passare l'indice

    int client_fd = clients[client_index].fd;
    char buffer[BUFFER_SIZE];
    char response[BUFFER_SIZE];
    bool client_connected = true;

    LOG("Thread started for client fd %d (index %d)\n", client_fd, client_index);

    // 1. Richiedi il nome al client
    if (!send_to_client(client_fd, "CMD:GET_NAME\n")) {
         client_connected = false; // Errore invio, chiudi subito
    }

    while (client_connected && keep_running) {
        memset(buffer, 0, BUFFER_SIZE);
        ssize_t bytes_read = read(client_fd, buffer, BUFFER_SIZE - 1);

        if (bytes_read <= 0) {
            if (bytes_read == 0) {
                LOG("Client fd %d (index %d, name %s) disconnected gracefully.\n", client_fd, client_index, clients[client_index].name);
            } else if (errno == EINTR && !keep_running) {
                LOG("Read interrupted by shutdown signal for client fd %d.\n", client_fd);
            } else if (errno != EINTR) {
                LOG("Read error from client fd %d (index %d, name %s): %s\n", client_fd, client_index, clients[client_index].name, strerror(errno));
            }
            client_connected = false;
            break; // Esce dal ciclo while
        }

        // Rimuovi newline finale se presente
        buffer[strcspn(buffer, "\r\n")] = 0;
        LOG("Received from fd %d (index %d, name '%s', state %d): [%s]\n",
            client_fd, client_index, clients[client_index].name, clients[client_index].state, buffer);

        // --- Parsing del Comando ---
        // Blocco i mutex solo quando necessario per accedere/modificare dati condivisi

        // Comando: NAME <nome> (inviato solo una volta all'inizio)
        if (strncmp(buffer, "NAME ", 5) == 0 && clients[client_index].state == CLIENT_STATE_CONNECTED) {
            pthread_mutex_lock(&client_list_mutex);
            strncpy(clients[client_index].name, buffer + 5, MAX_NAME_LEN - 1);
            clients[client_index].name[MAX_NAME_LEN - 1] = '\0'; // Assicura null termination
            clients[client_index].state = CLIENT_STATE_LOBBY;
            LOG("Client fd %d registered name: %s\n", client_fd, clients[client_index].name);
             send_to_client(client_fd, "RESP:NAME_OK\n"); // Conferma ricezione nome
            pthread_mutex_unlock(&client_list_mutex);
        }
        // Comando: LIST (richiede lista partite in attesa)
        else if (strcmp(buffer, "LIST") == 0 && clients[client_index].state == CLIENT_STATE_LOBBY) {
            response[0] = '\0';
            strcat(response, "RESP:GAMES_LIST;"); // Inizia la risposta
            bool first = true;

            pthread_mutex_lock(&game_list_mutex);
            for (int i = 0; i < MAX_GAMES; ++i) {
                if (games[i].state == GAME_STATE_WAITING) {
                    if (!first) strcat(response, "|"); // Separatore tra partite
                    char game_info[100];
                    snprintf(game_info, sizeof(game_info), "%d,%s", games[i].id, games[i].player1_name);
                    strcat(response, game_info);
                    first = false;
                }
            }
            pthread_mutex_unlock(&game_list_mutex);

            strcat(response, "\n"); // Fine messaggio
            if (!send_to_client(client_fd, response)) client_connected = false;
        }
        // Comando: CREATE (crea una nuova partita)
        else if (strcmp(buffer, "CREATE") == 0 && clients[client_index].state == CLIENT_STATE_LOBBY) {
            int game_idx = -1;
            pthread_mutex_lock(&game_list_mutex);
            // Trova uno slot libero
            for (int i = 0; i < MAX_GAMES; ++i) {
                if (games[i].state == GAME_STATE_EMPTY) {
                    game_idx = i;
                    break;
                }
            }

            if (game_idx != -1) {
                games[game_idx].id = next_game_id++;
                games[game_idx].state = GAME_STATE_WAITING;
                init_board(games[game_idx].board);
                games[game_idx].player1_fd = client_fd;
                games[game_idx].player2_fd = -1;
                games[game_idx].current_turn_fd = -1; // Nessuno gioca ancora
                strncpy(games[game_idx].player1_name, clients[client_index].name, MAX_NAME_LEN);
                games[game_idx].player2_name[0] = '\0';

                // Aggiorna lo stato del client
                pthread_mutex_lock(&client_list_mutex);
                clients[client_index].state = CLIENT_STATE_WAITING;
                clients[client_index].game_id = games[game_idx].id;
                pthread_mutex_unlock(&client_list_mutex);

                snprintf(response, sizeof(response), "RESP:CREATED %d\n", games[game_idx].id);
                LOG("Client %s (fd %d) created game %d (index %d)\n", clients[client_index].name, client_fd, games[game_idx].id, game_idx);
            } else {
                strcpy(response, "ERROR:Server full, cannot create game\n");
                LOG("Cannot create game, server full (MAX_GAMES reached)\n");
            }
            pthread_mutex_unlock(&game_list_mutex);
            if (!send_to_client(client_fd, response)) client_connected = false;

             // TODO: Notificare gli altri client in LOBBY della nuova partita (opzionale)

        }
        // Comando: JOIN <id> (unisciti a una partita)
        else if (strncmp(buffer, "JOIN ", 5) == 0 && clients[client_index].state == CLIENT_STATE_LOBBY) {
            int game_id_to_join = atoi(buffer + 5);
            int game_idx = -1;
            int player1_idx = -1;

            pthread_mutex_lock(&game_list_mutex);
            pthread_mutex_lock(&client_list_mutex); // Blocca entrambe le liste

            game_idx = find_game_index(game_id_to_join);

            if (game_idx != -1 && games[game_idx].state == GAME_STATE_WAITING && games[game_idx].player1_fd != client_fd) {
                // Partita trovata e in attesa, e non è quella creata da me stesso
                player1_idx = find_client_index(games[game_idx].player1_fd);

                if (player1_idx != -1) {
                    // Aggiorna la partita
                    games[game_idx].player2_fd = client_fd;
                    strncpy(games[game_idx].player2_name, clients[client_index].name, MAX_NAME_LEN);
                    games[game_idx].state = GAME_STATE_IN_PROGRESS;
                    games[game_idx].current_turn_fd = games[game_idx].player1_fd; // Creatore inizia (X)

                    // Aggiorna stato dei client
                    clients[client_index].state = CLIENT_STATE_PLAYING;
                    clients[client_index].game_id = game_id_to_join;
                    clients[player1_idx].state = CLIENT_STATE_PLAYING; // Anche il creatore passa a PLAYING

                    LOG("Client %s (fd %d) joined game %d (index %d) created by %s (fd %d)\n",
                        clients[client_index].name, client_fd, game_id_to_join, game_idx,
                        games[game_idx].player1_name, games[game_idx].player1_fd);

                    // Invia notifiche di inizio partita
                    char board_str[BUFFER_SIZE];
                    board_to_string(games[game_idx].board, board_str, sizeof(board_str));

                    // A chi si è unito (O)
                    snprintf(response, sizeof(response), "RESP:JOIN_OK %d O %s\n", game_id_to_join, games[game_idx].player1_name);
                    if (!send_to_client(client_fd, response)) client_connected = false;
                    snprintf(response, sizeof(response), "NOTIFY:GAME_START %d O %s\n", game_id_to_join, games[game_idx].player1_name);
                     if (!send_to_client(client_fd, response)) client_connected = false;
                     snprintf(response, sizeof(response), "NOTIFY:BOARD %s\n", board_str);
                     if (!send_to_client(client_fd, response)) client_connected = false;


                    // Al creatore (X)
                    snprintf(response, sizeof(response), "NOTIFY:GAME_START %d X %s\n", game_id_to_join, clients[client_index].name);
                     if (!send_to_client(games[game_idx].player1_fd, response)) { /* Gestire errore invio? */ }
                     snprintf(response, sizeof(response), "NOTIFY:BOARD %s\n", board_str);
                     if (!send_to_client(games[game_idx].player1_fd, response)) { /* Gestire errore invio? */ }
                     if (!send_to_client(games[game_idx].player1_fd, "NOTIFY:YOUR_TURN\n")) { /* Gestire errore invio? */ }


                } else {
                     // Errore: creatore non trovato (incongruenza dati)
                    snprintf(response, sizeof(response), "ERROR:Game creator not found for game %d\n", game_id_to_join);
                     if (!send_to_client(client_fd, response)) client_connected = false;
                     LOG("ERROR: Creator (fd %d) for game %d not found in client list!\n", games[game_idx].player1_fd, game_id_to_join);
                }

            } else {
                // Partita non trovata o non disponibile
                 if (game_idx != -1 && games[game_idx].player1_fd == client_fd) {
                     snprintf(response, sizeof(response), "ERROR:Cannot join your own game %d\n", game_id_to_join);
                 } else if (game_idx != -1) { // Trovata ma non in attesa
                     snprintf(response, sizeof(response), "ERROR:Game %d is not waiting for players\n", game_id_to_join);
                 } else { // Non trovata
                    snprintf(response, sizeof(response), "ERROR:Game %d not found\n", game_id_to_join);
                 }
                if (!send_to_client(client_fd, response)) client_connected = false;
            }

            pthread_mutex_unlock(&client_list_mutex);
            pthread_mutex_unlock(&game_list_mutex);
        }
         // Comando: MOVE <r> <c> (effettua una mossa)
        else if (strncmp(buffer, "MOVE ", 5) == 0 && clients[client_index].state == CLIENT_STATE_PLAYING) {
            int r, c;
            if (sscanf(buffer + 5, "%d %d", &r, &c) == 2) {
                int game_idx = -1;
                int opponent_fd = -1;
                Cell my_symbol = CELL_EMPTY;
                bool move_ok = false;
                bool game_ended = false;
                char end_reason[20] = ""; // "WIN", "LOSE", "DRAW"

                pthread_mutex_lock(&game_list_mutex);
                game_idx = find_game_index(clients[client_index].game_id);

                if (game_idx != -1 && games[game_idx].state == GAME_STATE_IN_PROGRESS) {
                    if (games[game_idx].current_turn_fd == client_fd) {
                        if (r >= 0 && r < 3 && c >= 0 && c < 3 && games[game_idx].board[r][c] == CELL_EMPTY) {
                            // Mossa valida
                            my_symbol = (client_fd == games[game_idx].player1_fd) ? CELL_X : CELL_O;
                            games[game_idx].board[r][c] = my_symbol;
                            move_ok = true;
                            LOG("Player %s (fd %d) made move %d,%d in game %d\n", clients[client_index].name, client_fd, r, c, clients[client_index].game_id);

                            // Controlla fine partita
                            if (check_winner(games[game_idx].board, my_symbol)) {
                                game_ended = true;
                                strcpy(end_reason, "WIN");
                                games[game_idx].state = GAME_STATE_FINISHED;
                                LOG("Game %d finished. Winner: %s\n", clients[client_index].game_id, clients[client_index].name);
                            } else if (board_full(games[game_idx].board)) {
                                game_ended = true;
                                strcpy(end_reason, "DRAW");
                                games[game_idx].state = GAME_STATE_FINISHED;
                                LOG("Game %d finished. Draw.\n", clients[client_index].game_id);
                            }

                            // Determina avversario e cambia turno se la partita continua
                             opponent_fd = (client_fd == games[game_idx].player1_fd) ? games[game_idx].player2_fd : games[game_idx].player1_fd;
                            if (!game_ended) {
                                games[game_idx].current_turn_fd = opponent_fd;
                            } else {
                                games[game_idx].current_turn_fd = -1; // Nessuno gioca più
                            }

                        } else {
                             strcpy(response, "ERROR:Invalid move (occupied or out of bounds)\n");
                             if (!send_to_client(client_fd, response)) client_connected = false;
                        }
                    } else {
                         strcpy(response, "ERROR:Not your turn\n");
                         if (!send_to_client(client_fd, response)) client_connected = false;
                    }
                } else {
                     strcpy(response, "ERROR:Game not found or not in progress\n");
                     if (!send_to_client(client_fd, response)) client_connected = false;
                }

                // Se la mossa è stata valida, invia aggiornamenti
                if (move_ok) {
                    char board_str[BUFFER_SIZE];
                    board_to_string(games[game_idx].board, board_str, sizeof(board_str));

                    // Invia board aggiornata a entrambi
                    snprintf(response, sizeof(response), "NOTIFY:BOARD %s\n", board_str);
                    if (!send_to_client(client_fd, response)) client_connected = false;
                    if (opponent_fd != -1) send_to_client(opponent_fd, response);

                    if (game_ended) {
                         // Invia risultato partita a entrambi
                         // A chi ha fatto la mossa
                         snprintf(response, sizeof(response), "NOTIFY:GAMEOVER %s\n", (strcmp(end_reason,"WIN")==0) ? "WIN" : "DRAW");
                         if (!send_to_client(client_fd, response)) client_connected = false;
                         // All'avversario
                         snprintf(response, sizeof(response), "NOTIFY:GAMEOVER %s\n", (strcmp(end_reason,"WIN")==0) ? "LOSE" : "DRAW");
                          if (opponent_fd != -1) send_to_client(opponent_fd, response);

                        // Riporta i client alla Lobby
                        pthread_mutex_lock(&client_list_mutex);
                         clients[client_index].state = CLIENT_STATE_LOBBY;
                         clients[client_index].game_id = 0;
                         int opponent_idx = find_client_index(opponent_fd);
                         if (opponent_idx != -1) {
                              clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                              clients[opponent_idx].game_id = 0;
                         }
                        pthread_mutex_unlock(&client_list_mutex);

                        // Resetta lo slot partita (opzionale, potrebbe essere riutilizzato per rematch)
                         games[game_idx].state = GAME_STATE_EMPTY; // Semplice reset per ora

                    } else {
                        // Invia notifica di turno all'avversario
                         if (opponent_fd != -1) send_to_client(opponent_fd, "NOTIFY:YOUR_TURN\n");
                    }
                }

                pthread_mutex_unlock(&game_list_mutex);

            } else {
                 strcpy(response, "ERROR:Invalid move format. Use: MOVE <row> <col>\n");
                 if (!send_to_client(client_fd, response)) client_connected = false;
            }
        }
         // Comando: QUIT (abbandona partita/lobby)
         else if (strcmp(buffer, "QUIT") == 0) {
             LOG("Client %s (fd %d) requested QUIT.\n", clients[client_index].name, client_fd);
             client_connected = false; // Forza uscita dal loop e cleanup
         }
        // Comando non riconosciuto
        else {
            if (clients[client_index].state != CLIENT_STATE_CONNECTED) { // Ignora se non ha ancora mandato il nome
                 snprintf(response, sizeof(response), "ERROR:Unknown command or invalid state for command: %s\n", buffer);
                 if (!send_to_client(client_fd, response)) client_connected = false;
            }
        }
    } // End while(client_connected)

    // --- Cleanup del Client ---
    LOG("Cleaning up client fd %d (index %d, name '%s')\n", client_fd, client_index, clients[client_index].name);
    close(client_fd); // Chiudi socket

    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex); // Blocca entrambe per sicurezza

    int game_id = clients[client_index].game_id;
    clients[client_index].active = false; // Libera lo slot client
    clients[client_index].fd = -1;
    clients[client_index].game_id = 0;
    clients[client_index].state = CLIENT_STATE_CONNECTED; // Reset state
    clients[client_index].name[0] = '\0';

    // Se il client era in una partita, gestisci l'abbandono
    if (game_id > 0) {
        int game_idx = find_game_index(game_id);
        if (game_idx != -1 && (games[game_idx].state == GAME_STATE_WAITING || games[game_idx].state == GAME_STATE_IN_PROGRESS)) {
            LOG("Client was in game %d (index %d). Notifying opponent and cleaning up game.\n", game_id, game_idx);
            int opponent_fd = -1;
            if (games[game_idx].player1_fd == client_fd && games[game_idx].player2_fd != -1) {
                opponent_fd = games[game_idx].player2_fd;
            } else if (games[game_idx].player2_fd == client_fd && games[game_idx].player1_fd != -1) {
                opponent_fd = games[game_idx].player1_fd;
            }

             // Notifica l'avversario (se esiste ed è ancora connesso)
             if (opponent_fd != -1) {
                 send_to_client(opponent_fd, "NOTIFY:OPPONENT_LEFT\n");
                 // Riporta l'avversario alla lobby
                 int opponent_idx = find_client_index(opponent_fd);
                 if (opponent_idx != -1) {
                     clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                     clients[opponent_idx].game_id = 0;
                     LOG("Opponent %s (fd %d) moved back to LOBBY.\n", clients[opponent_idx].name, opponent_fd);
                 }
             }

            // Pulisci/Resetta la partita
             games[game_idx].state = GAME_STATE_EMPTY; // O potresti metterla in uno stato "ABBANDONATA"
             games[game_idx].player1_fd = -1;
             games[game_idx].player2_fd = -1;
             // ... reset altri campi ...
             LOG("Game %d (index %d) reset due to player leaving.\n", game_id, game_idx);
        }
    }

    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);

    LOG("Thread finished for client fd %d.\n", client_fd);
    return NULL;
}


// --- Funzione Gestione Segnali ---
void handle_signal(int signal) {
    keep_running = 0;
    // Scrittura sicura da signal handler (evita printf)
    char msg[] = "\n!!! Signal received, initiating server shutdown... !!!\n";
    write(STDOUT_FILENO, msg, strlen(msg));
    // Potrebbe essere necessario svegliare il thread principale se bloccato su accept
    // inviando un segnale a se stesso o chiudendo il server_fd da qui (con cautela).
    // Per semplicità, confidiamo che accept si sblocchi o che il loop principale termini.
     if (server_fd != -1) {
         shutdown(server_fd, SHUT_RDWR); // Tenta di sbloccare accept
         close(server_fd); // Chiude il socket di ascolto
         server_fd = -1;   // Impedisce riutilizzo in cleanup
     }
}


// --- Funzione Cleanup Globale ---
void cleanup() {
    LOG("Cleaning up resources...\n");

    // Invia messaggio di shutdown ai client attivi e chiudi connessioni
    pthread_mutex_lock(&client_list_mutex);
    for (int i = 0; i < MAX_TOTAL_CLIENTS; i++) {
        if (clients[i].active && clients[i].fd != -1) {
            send_to_client(clients[i].fd, "NOTIFY:SERVER_SHUTDOWN\n");
            close(clients[i].fd);
            clients[i].fd = -1; // Marca come chiuso
             LOG("Closed connection for client %d (name: %s)\n", i , clients[i].name);
        }
        clients[i].active = false; // Resetta comunque lo stato
    }
     pthread_mutex_unlock(&client_list_mutex);

    // Il server_fd potrebbe essere già stato chiuso dall'handler del segnale
    if (server_fd != -1) {
        close(server_fd);
        server_fd = -1;
    }

     // Distruggi i mutex
     pthread_mutex_destroy(&client_list_mutex);
     pthread_mutex_destroy(&game_list_mutex);


    LOG("Server stopped.\n");
}

// --- Main ---
int main() {
    struct sockaddr_in address;
    int addrlen = sizeof(address);

    // Imposta gestione segnali
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = handle_signal;
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGTERM, &action, NULL);
    // Ignora SIGPIPE per gestire errori di send/write nel codice
    signal(SIGPIPE, SIG_IGN);


    // Inizializza liste client e partite
    pthread_mutex_lock(&client_list_mutex);
    for(int i=0; i<MAX_TOTAL_CLIENTS; ++i) clients[i].active = false;
    pthread_mutex_unlock(&client_list_mutex);

    pthread_mutex_lock(&game_list_mutex);
    for(int i=0; i<MAX_GAMES; ++i) games[i].state = GAME_STATE_EMPTY;
    pthread_mutex_unlock(&game_list_mutex);


    // --- Setup Socket Server ---
    if ((server_fd = socket(AF_INET, SOCK_STREAM, 0)) == 0) {
        LOG_PERROR("socket failed");
        exit(EXIT_FAILURE);
    }
    int opt = 1;
    if (setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt))) {
        LOG_PERROR("setsockopt SO_REUSEADDR failed");
    }
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons(PORT);
    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address))<0) {
        LOG_PERROR("bind failed");
        close(server_fd);
        exit(EXIT_FAILURE);
    }
    if (listen(server_fd, MAX_TOTAL_CLIENTS) < 0) { // Metti in coda fino a MAX_TOTAL_CLIENTS
        LOG_PERROR("listen failed");
        close(server_fd);
        exit(EXIT_FAILURE);
    }

    LOG("Server listening on port %d... (Max Clients: %d, Max Games: %d)\n", PORT, MAX_TOTAL_CLIENTS, MAX_GAMES);

    // --- Ciclo Accettazione Connessioni ---
    while (keep_running) {
        int new_socket = accept(server_fd, (struct sockaddr *)&address, (socklen_t*)&addrlen);

        if (new_socket < 0) {
            if (!keep_running && (errno == EINTR || errno == EBADF)) {
                LOG("Accept interrupted by shutdown signal or closed socket.\n");
            } else if (errno != EINTR) {
                 LOG_PERROR("accept failed");
            }
             if (!keep_running) break; // Esce se il server si sta fermando
            continue; // Prova ad accettare di nuovo se non è errore grave o shutdown
        }

        LOG("New connection accepted, assigning fd %d\n", new_socket);

        // Trova uno slot client libero e avvia il thread handler
        pthread_mutex_lock(&client_list_mutex);
        int client_index = -1;
        for (int i = 0; i < MAX_TOTAL_CLIENTS; ++i) {
            if (!clients[i].active) {
                client_index = i;
                break;
            }
        }

        if (client_index != -1) {
            clients[client_index].active = true;
            clients[client_index].fd = new_socket;
            clients[client_index].state = CLIENT_STATE_CONNECTED;
            clients[client_index].game_id = 0;
            clients[client_index].name[0] = '\0';

            pthread_t thread_id;
            int *p_client_index = malloc(sizeof(int)); // Alloca memoria per passare l'indice al thread
             if (p_client_index == NULL) {
                 LOG_PERROR("Failed to allocate memory for thread arg");
                 close(new_socket);
                 clients[client_index].active = false; // Libera lo slot
                 pthread_mutex_unlock(&client_list_mutex);
                 continue;
             }
            *p_client_index = client_index;

            if (pthread_create(&thread_id, NULL, handle_client, p_client_index) != 0) {
                LOG_PERROR("pthread_create failed");
                close(new_socket);
                clients[client_index].active = false; // Libera slot
                 free(p_client_index); // Libera memoria se create fallisce
            } else {
                pthread_detach(thread_id); // Non aspettiamo la terminazione del thread qui
                 LOG("Client assigned to index %d, handler thread created.\n", client_index);
            }
        } else {
            LOG("Server full (MAX_TOTAL_CLIENTS reached), rejecting connection fd %d\n", new_socket);
            char reject_msg[] = "ERROR:Server is full. Try again later.\n";
            send(new_socket, reject_msg, strlen(reject_msg), MSG_NOSIGNAL);
            close(new_socket);
        }
        pthread_mutex_unlock(&client_list_mutex);

    } // End while(keep_running)

    cleanup();
    return 0;
}