#ifndef TYPES_H
#define TYPES_H

#include <pthread.h>
#include <stdbool.h>
#include <netinet/in.h> // Per sockaddr_in (anche se non usata direttamente qui)
#include <signal.h>     // Per sig_atomic_t

// --- Costanti Globali ---
#define PORT 12345
#define BUFFER_SIZE 1024
#define MAX_TOTAL_CLIENTS 10
#define MAX_GAMES 5
#define MAX_NAME_LEN 32

// --- Enumerazioni ---
typedef enum {
    CLIENT_STATE_CONNECTED, // Appena connesso, in attesa di nome
    CLIENT_STATE_LOBBY,     // Ha un nome, nella lobby
    CLIENT_STATE_WAITING,   // Creatore in attesa di P2 o Richiedente in attesa di risposta
    CLIENT_STATE_PLAYING    // In partita O **in stato post-partita** (inclusa attesa rematch)
} ClientState;

typedef enum {
    GAME_STATE_EMPTY,       // Slot libero
    GAME_STATE_WAITING,     // Creatore in attesa di P2
    GAME_STATE_IN_PROGRESS, // Partita in corso
    GAME_STATE_FINISHED     // Partita terminata (in attesa di decisione rematch)
} GameState;

typedef enum {
    CELL_EMPTY = 0, // Explicit 0 helps memset
    CELL_X,
    CELL_O
} Cell;

// NUOVO: Stati per la decisione di rematch nel caso di pareggio
typedef enum {
    REMATCH_CHOICE_PENDING = 0, // Non ha ancora scelto
    REMATCH_CHOICE_YES,
    REMATCH_CHOICE_NO
} RematchChoice;

// --- Strutture Dati ---
typedef struct {
    int id;                 // ID univoco della partita
    GameState state;
    Cell board[3][3];
    int player1_fd;         // -1 se non presente, -2 se disconnesso/uscito prima/durante la partita
    int player2_fd;         // -1 se non presente, -2 se disconnesso/uscito prima/durante la partita
    int current_turn_fd;    // fd del giocatore di turno, -1 se nessuno (es. finita)
    char player1_name[MAX_NAME_LEN];
    char player2_name[MAX_NAME_LEN];
    int pending_joiner_fd;  // fd del giocatore in attesa di join (-1 se nessuno)
    char pending_joiner_name[MAX_NAME_LEN];
    int winner_fd;          // fd del vincitore (-1 se pareggio/non finito/nessuno), -2 se il vincitore si è disconnesso *dopo* la fine ma prima della decisione rematch
    RematchChoice player1_accepted_rematch; // MODIFICATO: Ora usa enum per tracciare lo stato nel pareggio
    RematchChoice player2_accepted_rematch; // MODIFICATO: Ora usa enum per tracciare lo stato nel pareggio
} GameInfo;

typedef struct {
    int fd;                 // File descriptor del socket del client (-1 se non connesso)
    ClientState state;
    char name[MAX_NAME_LEN];
    int game_id;            // ID della partita a cui è associato (0 se nessuno)
    bool active;            // Questo slot client è in uso?
    pthread_t thread_id;    // ID del thread che gestisce questo client (opzionale, utile per join/debug)
} ClientInfo;

// --- Dichiarazioni Variabili Globali (definite in globals.c) ---
extern GameInfo games[MAX_GAMES];
extern ClientInfo clients[MAX_TOTAL_CLIENTS];
extern int server_fd;
extern volatile sig_atomic_t keep_running;
extern int next_game_id;

extern pthread_mutex_t client_list_mutex;
extern pthread_mutex_t game_list_mutex;

#endif // TYPES_H