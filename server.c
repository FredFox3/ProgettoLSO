#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <netinet/in.h>
#include <sys/socket.h>

#define PORT 12345
#define BUFFER_SIZE 1024

typedef enum { EMPTY, X, O } Cell;
typedef enum { false, true } bool;

Cell board[3][3];

void init_board() {
    memset(board, 0, sizeof(board));
}

void print_board(char *out) {
    // Costruiamo una rappresentazione testuale della board
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
        if(i < 2) strcat(out, "\n-----------\n");
    }
}

bool check_winner(Cell player) {
    // Controlla righe, colonne e diagonali
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

int main() {
    int server_fd, client_fd[2];
    struct sockaddr_in address;
    int addrlen = sizeof(address);
    
    // Creazione del socket
    if ((server_fd = socket(AF_INET, SOCK_STREAM, 0)) == 0) {
        perror("socket failed");
        exit(EXIT_FAILURE);
    }
    
    // Configurazione dell'indirizzo
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons(PORT);
    
    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address))<0) {
        perror("bind failed");
        exit(EXIT_FAILURE);
    }
    
    if (listen(server_fd, 3) < 0) {
        perror("listen");
        exit(EXIT_FAILURE);
    }
    
    printf("Server in ascolto sulla porta %d...\n", PORT);
    
    // Accetta 2 connessioni (giocatori)
    for (int i = 0; i < 2; i++) {
        if ((client_fd[i] = accept(server_fd, (struct sockaddr *)&address, (socklen_t*)&addrlen))<0) {
            perror("accept");
            exit(EXIT_FAILURE);
        }
        printf("Giocatore %d connesso\n", i+1);
        char msg[] = "Benvenuto! In attesa dell'altro giocatore...\n";
        send(client_fd[i], msg, strlen(msg), 0);
    }
    
    // Inizializza la board
    init_board();
    
    int currentPlayer = 0;
    char buffer[BUFFER_SIZE];
    bool game_over = false;
    
    // Loop di gioco
    while(!game_over) {
        // Invia lo stato della board a entrambi i giocatori
        char boardStr[256];
        print_board(boardStr);
        for (int i = 0; i < 2; i++) {
            send(client_fd[i], boardStr, strlen(boardStr), 0);
        }
        
        // Invia messaggio al giocatore corrente per la mossa
        char prompt[BUFFER_SIZE];
        snprintf(prompt, sizeof(prompt), "\nGiocatore %d (%c), inserisci la tua mossa (riga e colonna, es. 1 2): ",
                 currentPlayer+1, (currentPlayer == 0) ? 'X' : 'O');
        send(client_fd[currentPlayer], prompt, strlen(prompt), 0);
        
        // Riceve la mossa
        memset(buffer, 0, sizeof(buffer));
        int valread = read(client_fd[currentPlayer], buffer, BUFFER_SIZE);
        if(valread <= 0) {
            printf("Errore nella ricezione dalla socket\n");
            break;
        }
        
        int row, col;
        if (sscanf(buffer, "%d %d", &row, &col) != 2) {
            char errMsg[] = "Formato mossa non valido. Usa: riga colonna\n";
            send(client_fd[currentPlayer], errMsg, strlen(errMsg), 0);
            continue;
        }
        
        // Verifica che la mossa sia valida
        if(row < 0 || row > 2 || col < 0 || col > 2 || board[row][col] != EMPTY) {
            char errMsg[] = "Mossa non valida. Riprova.\n";
            send(client_fd[currentPlayer], errMsg, strlen(errMsg), 0);
            continue;
        }
        
        // Applica la mossa
        board[row][col] = (currentPlayer == 0) ? X : O;
        
        // Verifica la vittoria o il pareggio
        if(check_winner(board[row][col])) {
            print_board(boardStr);
            for (int i = 0; i < 2; i++) {
                send(client_fd[i], boardStr, strlen(boardStr), 0);
                if(i == currentPlayer)
                    send(client_fd[i], "\nHai vinto!\n", 13, 0);
                else
                    send(client_fd[i], "\nHai perso!\n", 13, 0);
            }
            game_over = true;
        } else if (board_full()) {
            print_board(boardStr);
            for (int i = 0; i < 2; i++) {
                send(client_fd[i], boardStr, strlen(boardStr), 0);
                send(client_fd[i], "\nPareggio!\n", 11, 0);
            }
            game_over = true;
        }
        
        // Alterna il giocatore
        currentPlayer = (currentPlayer + 1) % 2;
    }
    
    // Chiusura connessioni
    for (int i = 0; i < 2; i++) {
        close(client_fd[i]);
    }
    close(server_fd);
    
    return 0;
}
