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

    while (keep_running) {
        int num_clients = 0;
        LOG("In attesa di connessioni dei giocatori...\n");

        while (num_clients < MAX_CLIENTS && keep_running) {
            client_fd[num_clients] = accept(server_fd, (struct sockaddr *)&address, (socklen_t*)&addrlen);

            if (client_fd[num_clients] < 0) {
                if (errno == EINTR && !keep_running) {
                    LOG("Accept interrotto da segnale di arresto.\n");
                    break;
                }
                 if (errno != EINTR) {
                    LOG_PERROR("accept failed");
                 }
                continue;
            }

            LOG("Giocatore %d connesso (fd: %d)\n", num_clients + 1, client_fd[num_clients]);
            char msg[] = "Benvenuto! In attesa dell'altro giocatore...\n";
            if (send(client_fd[num_clients], msg, strlen(msg), MSG_NOSIGNAL) < 0) {
                 LOG_PERROR("send welcome msg failed");
                 close(client_fd[num_clients]);
                 client_fd[num_clients] = -1;
                 continue;
            }
            num_clients++;
        }

        if (!keep_running) break;

        if (num_clients < MAX_CLIENTS) {
             LOG("Non tutti i giocatori si sono connessi prima dell'arresto o errore. Riavvio attesa.\n");
             for (int i = 0; i < num_clients; i++) {
                 if (client_fd[i] != -1) close(client_fd[i]);
                 client_fd[i] = -1;
             }
             continue;
        }

        LOG("Entrambi i giocatori connessi. Inizio partita.\n");
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

            for (int i = 0; i < MAX_CLIENTS; i++) {
                 LOG("DEBUG: Invio board al client %d (fd: %d)\n", i+1, client_fd[i]);
                 ssize_t bytes_sent = send(client_fd[i], boardStr, strlen(boardStr), MSG_NOSIGNAL);
                 if (bytes_sent <= 0) {
                     LOG_PERROR("send board failed");
                     LOG("Errore invio board al giocatore %d, chiusura partita.\n", i+1);
                     game_over = true;
                     break;
                 }
            }
             if (game_over) {
                 LOG("DEBUG: game_over=true dopo invio board, esco dal loop di gioco.\n");
                 break;
             }

            char prompt[BUFFER_SIZE];
             snprintf(prompt, sizeof(prompt), "Giocatore %d (%c), inserisci la tua mossa (riga e colonna, es. 1 2):\n",
         currentPlayer+1, (currentPlayer == 0) ? 'X' : 'O');

            get_timestamp(timestamp, sizeof(timestamp));
            char debug_prompt_msg[BUFFER_SIZE + 100];
            snprintf(debug_prompt_msg, sizeof(debug_prompt_msg), "%s - DEBUG: Invio prompt al client %d (fd: %d): %s\n",
                     timestamp, currentPlayer+1, client_fd[currentPlayer], prompt);
            write(STDOUT_FILENO, debug_prompt_msg, strlen(debug_prompt_msg));


            ssize_t bytes_sent_prompt = send(client_fd[currentPlayer], prompt, strlen(prompt), MSG_NOSIGNAL);
            if (bytes_sent_prompt <= 0) {
                 LOG_PERROR("send prompt failed");
                 LOG("Errore invio prompt al giocatore %d, chiusura partita.\n", currentPlayer+1);
                 game_over = true;
                 LOG("DEBUG: game_over=true dopo invio prompt, esco dal loop di gioco.\n");
                 break;
            }

            LOG("DEBUG: In attesa di read dal client %d (fd: %d)\n", currentPlayer+1, client_fd[currentPlayer]);

            memset(buffer, 0, sizeof(buffer));
            int valread = read(client_fd[currentPlayer], buffer, BUFFER_SIZE - 1);

            if(valread < 0) {
                if (errno == EINTR && !keep_running) {
                    LOG("Read interrotto da segnale di arresto durante la partita.\n");
                    break;
                }
                if (errno != EINTR) {
                    LOG_PERROR("read failed");
                    LOG("Errore nella ricezione dal giocatore %d, chiusura partita.\n", currentPlayer+1);
                    game_over = true;
                    break;
                }
                LOG("Read interrotto ma server ancora attivo, richiedo mossa di nuovo.\n");
                continue;

            } else if (valread == 0) {
                LOG("Giocatore %d ha chiuso la connessione.\n", currentPlayer+1);
                game_over = true;
                 int otherPlayer = (currentPlayer + 1) % 2;
                 char disconnect_msg[] = "\nL'altro giocatore si è disconnesso. Partita terminata.\n";
                 send(client_fd[otherPlayer], disconnect_msg, strlen(disconnect_msg), MSG_NOSIGNAL);
                break;
            }

             buffer[strcspn(buffer, "\r\n")] = 0;
            LOG("DEBUG: Ricevuto da client %d: [%s]\n", currentPlayer+1, buffer);

            int row, col;
            if (sscanf(buffer, "%d %d", &row, &col) != 2) {
                 LOG("DEBUG: sscanf fallito per l'input [%s]\n", buffer);
                char errMsg[] = "Formato mossa non valido. Usa: riga colonna\n";
                send(client_fd[currentPlayer], errMsg, strlen(errMsg), MSG_NOSIGNAL);
                continue;
            }

            LOG("DEBUG: Mossa parsata: riga=%d, colonna=%d\n", row, col);

            if(row < 0 || row > 2 || col < 0 || col > 2 || board[row][col] != EMPTY) {
                LOG("DEBUG: Mossa [%d %d] non valida (out of bounds o cella non vuota: %d)\n", row, col, board[row][col]);
                char errMsg[] = "Mossa non valida. Riprova.\n";
                send(client_fd[currentPlayer], errMsg, strlen(errMsg), MSG_NOSIGNAL);
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
                    send(client_fd[i], finalBoardStr, strlen(finalBoardStr), MSG_NOSIGNAL);
                    char resultMsg[30];
                     if (won) {
                        snprintf(resultMsg, sizeof(resultMsg), "\n%s\n", (i == currentPlayer) ? "Hai vinto!" : "Hai perso!");
                    } else {
                        snprintf(resultMsg, sizeof(resultMsg), "\nPareggio!\n");
                    }
                     send(client_fd[i], resultMsg, strlen(resultMsg), MSG_NOSIGNAL);
                }
            }

            if (!game_over) {
                 currentPlayer = (currentPlayer + 1) % 2;
            }

             LOG("DEBUG: Fine iterazione loop di gioco (game_over=%d).\n", game_over);

        }

        LOG("DEBUG: Uscito dal loop di gioco.\n");

        LOG("Fine ciclo di gioco.\n");
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (client_fd[i] != -1) {
                close(client_fd[i]);
                client_fd[i] = -1;
            }
        }

        if (!keep_running) {
             break;
        } else {
            LOG("Partita completata. In attesa di nuovi giocatori...\n");
        }

    }

    cleanup();

    return 0;
}