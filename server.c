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

#define PORT 12345
#define BUFFER_SIZE 1024
#define MAX_CLIENTS 2

typedef enum { EMPTY, X, O } Cell;

Cell board[3][3];
int server_fd = -1;
int client_fd[MAX_CLIENTS] = {-1, -1};
volatile sig_atomic_t keep_running = 1;
int current_client_count = 0;

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

void handle_signal(int signal) {
    keep_running = 0;
    char msg[] = "!!! Segnale ricevuto, avvio arresto server (timestamp approssimativo) !!!\n";
    write(STDOUT_FILENO, msg, strlen(msg));
}

void init_board() {
    memset(board, 0, sizeof(board));
}

void print_board(char *out) {
    strcpy(out, "");
    for (int i = 0; i < 3; i++) {
        char line[50] = "";
        for (int j = 0; j < 3; j++) {
            char c;
            if(board[i][j] == X) c = 'X';
            else if(board[i][j] == O) c = 'O';
            else c = ' ';
            char cell[4];
            snprintf(cell, sizeof(cell), " %c ", c);
            strcat(line, cell);
            if(j < 2) strcat(line, "|");
        }
        strcat(out, line);
        if(i < 2) {
            strcat(out, "\n-----------\n");
        } else {
            strcat(out, "\n");
        }
    }
}

bool check_winner(Cell player) {
    for (int i = 0; i < 3; i++) {
        if (board[i][0] == player && board[i][1] == player && board[i][2] == player)
            return true;
        if (board[0][i] == player && board[1][i] == player && board[2][i] == player)
            return true;
    }
    if (board[0][0] == player && board[1][1] == player && board[2][2] == player)
        return true;
    if (board[0][2] == player && board[1][1] == player && board[2][0] == player)
        return true;
    return false;
}

bool board_full() {
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++)
            if (board[i][j] == EMPTY)
                return false;
    return true;
}

void notify_opponent_of_win(int winner_slot, const char* reason) {
     if (client_fd[winner_slot] != -1) {
        char win_msg[100];
        snprintf(win_msg, sizeof(win_msg), "\nL'avversario si è disconnesso (%s). Hai vinto!\n", reason);
        send(client_fd[winner_slot], win_msg, strlen(win_msg), MSG_NOSIGNAL);
        LOG("DEBUG: Notifica vittoria per disconnessione (%s) inviata a slot %d.\n", reason, winner_slot);
    }
}

void cleanup() {
    LOG("Pulizia risorse...\n");
    char shutdown_msg[] = "SERVER_SHUTDOWN: Il server si sta arrestando.\n";
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (client_fd[i] != -1) {
            send(client_fd[i], shutdown_msg, strlen(shutdown_msg), MSG_NOSIGNAL);
            close(client_fd[i]);
            client_fd[i] = -1;
        }
    }
    if (server_fd != -1) {
        close(server_fd);
        server_fd = -1;
    }
    LOG("Server arrestato.\n");
}

int main() {
    struct sockaddr_in address;
    int addrlen = sizeof(address);
    char timestamp[30];

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = handle_signal;
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGTERM, &action, NULL);

    if ((server_fd = socket(AF_INET, SOCK_STREAM, 0)) == 0) {
        get_timestamp(timestamp, sizeof(timestamp));
        fprintf(stderr, "%s - ", timestamp);
        perror("socket failed");
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
        get_timestamp(timestamp, sizeof(timestamp));
        fprintf(stderr, "%s - ", timestamp);
        perror("bind failed");
        close(server_fd);
        exit(EXIT_FAILURE);
    }

    if (listen(server_fd, 3) < 0) {
        get_timestamp(timestamp, sizeof(timestamp));
        fprintf(stderr, "%s - ", timestamp);
        perror("listen failed");
        close(server_fd);
        exit(EXIT_FAILURE);
    }

    LOG("Server in ascolto sulla porta %d... (Premi Ctrl+C per fermare)\n", PORT);

    current_client_count = 0;

    while (keep_running) {

        while (current_client_count < MAX_CLIENTS && keep_running) {
            LOG("In attesa di connessioni... (%d/%d giocatori connessi)\n", current_client_count, MAX_CLIENTS);

            int client_slot = -1;
            for(int i = 0; i < MAX_CLIENTS; ++i) {
                if (client_fd[i] == -1) {
                    client_slot = i;
                    break;
                }
            }
            if (client_slot == -1 && current_client_count < MAX_CLIENTS) {
                 LOG("Errore logico: Non ci sono slot liberi ma current_client_count < MAX_CLIENTS.\n");
                 sleep(1);
                 continue;
            }
            if (client_slot == -1) continue;

            int new_fd = accept(server_fd, (struct sockaddr *)&address, (socklen_t*)&addrlen);

            if (new_fd < 0) {
                if (errno == EINTR && !keep_running) {
                    LOG("Accept interrotto da segnale di arresto.\n");
                    break;
                }
                 if (errno != EINTR) {
                    LOG_PERROR("accept failed");
                 }
                continue;
            }

            client_fd[client_slot] = new_fd;
            current_client_count++;

            LOG("Giocatore %d/%d assegnato allo slot %d connesso (fd: %d)\n", current_client_count, MAX_CLIENTS, client_slot, client_fd[client_slot]);
            char msg[] = "Benvenuto! In attesa dell'altro giocatore...\n";
            ssize_t sent_bytes = send(client_fd[client_slot], msg, strlen(msg), MSG_NOSIGNAL);

            if (sent_bytes <= 0) {
                 if (sent_bytes == 0) {
                     LOG("Client (fd: %d) ha chiuso la connessione immediatamente dopo send benvenuto.\n", client_fd[client_slot]);
                 } else {
                    LOG_PERROR("send welcome msg failed");
                 }
                 close(client_fd[client_slot]);
                 client_fd[client_slot] = -1;
                 current_client_count--;
                 LOG("Slot %d liberato. Clienti attuali: %d\n", client_slot, current_client_count);
                 continue;
            }

             char dummy_buffer;
             ssize_t check_recv_immediate = recv(client_fd[client_slot], &dummy_buffer, 1, MSG_PEEK | MSG_DONTWAIT | MSG_NOSIGNAL);
             if (check_recv_immediate == 0) {
                 LOG("Client (fd: %d) ha chiuso la connessione subito dopo accept (recv=0).\n", client_fd[client_slot]);
                 close(client_fd[client_slot]);
                 client_fd[client_slot] = -1;
                 current_client_count--;
                 LOG("Slot %d liberato. Clienti attuali: %d\n", client_slot, current_client_count);
                 continue;
             } else if (check_recv_immediate == -1 && !(errno == EAGAIN || errno == EWOULDBLOCK)) {
                 LOG_PERROR("Errore recv check immediato");
                 LOG("Chiudo giocatore slot %d (fd: %d) per errore recv immediato: %s.\n", client_slot, client_fd[client_slot], strerror(errno));
                 close(client_fd[client_slot]);
                 client_fd[client_slot] = -1;
                 current_client_count--;
                 LOG("Slot %d liberato. Clienti attuali: %d\n", client_slot, current_client_count);
                 continue;
             }
        }

        if (!keep_running) break;

        bool connection_lost_before_game = false;
        if (current_client_count == MAX_CLIENTS) {
             LOG("Verifica connessioni prima di iniziare la partita...\n");
             for (int i = 0; i < MAX_CLIENTS; i++) {
                 if (client_fd[i] != -1) {
                     char dummy_buffer;
                     ssize_t check_recv = recv(client_fd[i], &dummy_buffer, 1, MSG_PEEK | MSG_DONTWAIT | MSG_NOSIGNAL);

                     if (check_recv == 0) {
                         LOG("Giocatore nello slot %d (fd: %d) disconnesso (recv=0) prima dell'inizio della partita.\n", i, client_fd[i]);
                         close(client_fd[i]);
                         client_fd[i] = -1;
                         connection_lost_before_game = true;
                     } else if (check_recv == -1) {
                         if (errno == EAGAIN || errno == EWOULDBLOCK) {
                             LOG("DEBUG: Verifica slot %d (fd: %d) OK (EAGAIN/EWOULDBLOCK).\n", i, client_fd[i]);
                         } else {
                             LOG_PERROR("Errore durante la verifica della connessione (recv check)");
                             LOG("Chiudo giocatore nello slot %d (fd: %d) per errore recv: %s.\n", i, client_fd[i], strerror(errno));
                             close(client_fd[i]);
                             client_fd[i] = -1;
                             connection_lost_before_game = true;
                         }
                     } else {
                         LOG("DEBUG: Verifica slot %d (fd: %d) OK (recv != 0).\n", i, client_fd[i]);
                     }
                 } else {
                      LOG("Errore logico: Slot %d vuoto durante la verifica pre-partita ma contatore era %d.\n", i, current_client_count);
                      connection_lost_before_game = true;
                 }
             }

             if (connection_lost_before_game) {
                LOG("Una o più connessioni perse prima dell'inizio. Ritorno in attesa di giocatori.\n");
                int actual_connected = 0;
                for(int i = 0; i < MAX_CLIENTS; ++i) {
                    if(client_fd[i] != -1) {
                        actual_connected++;
                        char wait_again_msg[] = "L'altro giocatore si è disconnesso prima dell'inizio. In attesa di un nuovo avversario...\n";
                        send(client_fd[i], wait_again_msg, strlen(wait_again_msg), MSG_NOSIGNAL);
                        LOG("Notificato client slot %d (fd: %d) di attendere ancora.\n", i, client_fd[i]);
                    }
                }
                current_client_count = actual_connected;
                LOG("Clienti rimanenti: %d. Ritorno ad attendere.\n", current_client_count);
                continue;
             }
        } else if (keep_running) {
             LOG("Situazione inattesa: non abbastanza client (%d/%d) e server ancora attivo. Riavvio attesa.\n", current_client_count, MAX_CLIENTS);
             for (int i = 0; i < MAX_CLIENTS; i++) {
                 if (client_fd[i] != -1) {
                     close(client_fd[i]);
                     client_fd[i] = -1;
                 }
             }
             current_client_count = 0;
             continue;
        }

        if (!keep_running) break;

        LOG("Entrambi i giocatori connessi e verificati. Inizio partita.\n");
        init_board();
        int currentPlayer = 0;
        char buffer[BUFFER_SIZE];
        bool game_over = false;

        LOG("DEBUG: Sto per entrare nel loop di gioco (game_over=%d, keep_running=%d)\n", game_over, keep_running);

        while(!game_over && keep_running) {
            LOG("DEBUG: Inizio iterazione loop di gioco. CurrentPlayer=%d\n", currentPlayer);

            char boardStr[256];
            print_board(boardStr);
            LOG("DEBUG: Board da inviare:\n%s", boardStr);

            bool send_failed = false;
            int disconnected_slot = -1;
            for (int i = 0; i < MAX_CLIENTS; i++) {
                if (client_fd[i] != -1) {
                    LOG("DEBUG: Invio board al client %d (slot %d, fd: %d)\n", (i == 0 ? 1 : 2), i, client_fd[i]);
                    ssize_t bytes_sent = send(client_fd[i], boardStr, strlen(boardStr), MSG_NOSIGNAL);
                    if (bytes_sent <= 0) {
                        if (errno == EPIPE || errno == ECONNRESET || bytes_sent == 0) {
                             LOG("Client nello slot %d (fd: %d) disconnesso durante invio board.\n", i, client_fd[i]);
                        } else {
                            LOG_PERROR("send board failed");
                        }
                        LOG("Chiudo giocatore nello slot %d (fd: %d), chiusura partita.\n", i, client_fd[i]);
                        close(client_fd[i]);
                        client_fd[i] = -1;
                        disconnected_slot = i;
                        game_over = true;
                        send_failed = true;
                    }
                } else if (disconnected_slot == -1 && i == (currentPlayer + 1) % 2) {
                    game_over = true;
                    send_failed = true;
                    LOG("L'altro giocatore (slot %d) non era connesso all'inizio del turno.\n", i);
                }
            }

            if (send_failed) {
                 LOG("DEBUG: game_over=true a causa di errore invio board o disconnessione avversario.\n");
                 if(disconnected_slot != -1) {
                     notify_opponent_of_win((disconnected_slot + 1) % 2, "errore invio board");
                 }
                 break;
             }
             if (!keep_running) break;

             if (client_fd[currentPlayer] == -1) {
                 LOG("Errore logico: Il giocatore corrente (slot %d) è -1 prima del prompt. Partita già terminata?\n", currentPlayer);
                 game_over = true;
                 break;
            }

            char prompt[BUFFER_SIZE];
            snprintf(prompt, sizeof(prompt), "Giocatore %d (%c), inserisci la tua mossa (riga e colonna, es. 1 2):\n",
                     currentPlayer + 1, (currentPlayer == 0) ? 'X' : 'O');

            get_timestamp(timestamp, sizeof(timestamp));
            char debug_prompt_msg[BUFFER_SIZE + 100];
            snprintf(debug_prompt_msg, sizeof(debug_prompt_msg), "%s - DEBUG: Invio prompt al client %d (slot %d, fd: %d): %s\n",
                     timestamp, currentPlayer + 1, currentPlayer, client_fd[currentPlayer], prompt);
            write(STDOUT_FILENO, debug_prompt_msg, strlen(debug_prompt_msg));

            ssize_t bytes_sent_prompt = send(client_fd[currentPlayer], prompt, strlen(prompt), MSG_NOSIGNAL);
            if (bytes_sent_prompt <= 0) {
                  if (errno == EPIPE || errno == ECONNRESET || bytes_sent_prompt == 0) {
                     LOG("Client (slot %d, fd: %d) disconnesso durante invio prompt.\n", currentPlayer, client_fd[currentPlayer]);
                  } else {
                     LOG_PERROR("send prompt failed");
                  }
                 LOG("Chiudo giocatore %d (slot %d, fd: %d), chiusura partita.\n", currentPlayer + 1, currentPlayer, client_fd[currentPlayer]);
                 int disconnected_player = currentPlayer;
                 close(client_fd[disconnected_player]);
                 client_fd[disconnected_player] = -1;
                 game_over = true;
                 notify_opponent_of_win((disconnected_player + 1) % 2, "errore invio prompt");
                 LOG("DEBUG: game_over=true dopo invio prompt, esco dal loop di gioco.\n");
                 break;
            }

            LOG("DEBUG: In attesa di read dal client %d (slot %d, fd: %d)\n", currentPlayer + 1, currentPlayer, client_fd[currentPlayer]);

            memset(buffer, 0, sizeof(buffer));
            int valread = read(client_fd[currentPlayer], buffer, BUFFER_SIZE - 1);

            if(valread < 0) {
                if (errno == EINTR && !keep_running) {
                    LOG("Read interrotto da segnale di arresto durante la partita.\n");
                    break;
                }
                if (errno != EINTR) {
                     int disconnected_player = currentPlayer;
                     if (errno == EPIPE || errno == ECONNRESET || errno == EBADF) {
                         LOG("Client (slot %d, fd: %d) disconnesso (errore read %d: %s).\n", disconnected_player, client_fd[disconnected_player], errno, strerror(errno));
                     } else {
                        LOG_PERROR("read failed");
                     }
                    LOG("Errore nella ricezione dal giocatore %d (slot %d, fd: %d), chiusura partita.\n", disconnected_player + 1, disconnected_player, client_fd[disconnected_player]);
                    close(client_fd[disconnected_player]);
                    client_fd[disconnected_player] = -1;
                    game_over = true;
                    notify_opponent_of_win((disconnected_player + 1) % 2, "errore ricezione");
                    break;
                }
                LOG("Read interrotto (EINTR) ma server ancora attivo, richiedo mossa di nuovo.\n");
                continue;

            } else if (valread == 0) {
                int disconnected_player = currentPlayer;
                LOG("Giocatore %d (slot %d, fd: %d) ha chiuso la connessione (read=0).\n", disconnected_player + 1, disconnected_player, client_fd[disconnected_player]);
                close(client_fd[disconnected_player]);
                client_fd[disconnected_player] = -1;
                game_over = true;
                notify_opponent_of_win((disconnected_player + 1) % 2, "read=0");
                break;
            }

            buffer[strcspn(buffer, "\r\n")] = 0;
            LOG("DEBUG: Ricevuto da client %d (slot %d): [%s]\n", currentPlayer + 1, currentPlayer, buffer);

            int row, col;
            if (sscanf(buffer, "%d %d", &row, &col) != 2) {
                 LOG("DEBUG: sscanf fallito per l'input [%s]\n", buffer);
                 if(client_fd[currentPlayer] != -1) {
                    char errMsg[] = "Formato mossa non valido. Usa: riga colonna\n";
                    send(client_fd[currentPlayer], errMsg, strlen(errMsg), MSG_NOSIGNAL);
                 }
                 continue;
            }

            LOG("DEBUG: Mossa parsata: riga=%d, colonna=%d\n", row, col);

            if(row < 0 || row > 2 || col < 0 || col > 2 || board[row][col] != EMPTY) {
                LOG("DEBUG: Mossa [%d %d] non valida (out of bounds o cella non vuota: %d)\n", row, col, board[row][col]);
                if(client_fd[currentPlayer] != -1) {
                    char errMsg[] = "Mossa non valida. Riprova.\n";
                    send(client_fd[currentPlayer], errMsg, strlen(errMsg), MSG_NOSIGNAL);
                }
                continue;
            }

            board[row][col] = (currentPlayer == 0) ? X : O;
            LOG("DEBUG: Mossa applicata. Board aggiornata.\n");

            char finalBoardStr[256];
            print_board(finalBoardStr);
            bool won = check_winner(board[row][col]);
            bool draw = !won && board_full();

            if(won || draw) {
                 game_over = true;
                 LOG("Partita terminata: %s\n", won ? "Vittoria!" : "Pareggio!");
                 LOG("DEBUG: Invio risultato e board finale ai client.\n");
                for (int i = 0; i < MAX_CLIENTS; i++) {
                     if (client_fd[i] != -1) {
                        send(client_fd[i], finalBoardStr, strlen(finalBoardStr), MSG_NOSIGNAL);
                        char resultMsg[50];
                        if (won) {
                            snprintf(resultMsg, sizeof(resultMsg), "\n%s\n", (i == currentPlayer) ? "Hai vinto!" : "Hai perso!");
                        } else {
                            snprintf(resultMsg, sizeof(resultMsg), "\nPareggio!\n");
                        }
                        send(client_fd[i], resultMsg, strlen(resultMsg), MSG_NOSIGNAL);
                    }
                }
            }

            if (!game_over && keep_running) {
                 currentPlayer = (currentPlayer + 1) % 2;
            } else if (!keep_running) {
                 LOG("Segnale ricevuto durante l'elaborazione della mossa, uscita dal loop di gioco.\n");
            }

             LOG("DEBUG: Fine iterazione loop di gioco (game_over=%d, keep_running=%d).\n", game_over, keep_running);

        }

        LOG("DEBUG: Uscito dal loop di gioco.\n");
        LOG("Fine ciclo di gioco.\n");

        current_client_count = 0;
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (client_fd[i] != -1) {
                 LOG("Chiudo client residuo slot %d (fd: %d) dopo fine partita/interruzione.\n", i, client_fd[i]);
                close(client_fd[i]);
                client_fd[i] = -1;
            }
        }

        if (!keep_running) {
             break;
        } else {
            LOG("Partita completata o interrotta. In attesa di nuovi giocatori...\n");
        }
    }

    cleanup();
    return 0;
}