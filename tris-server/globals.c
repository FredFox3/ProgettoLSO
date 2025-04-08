#include "types.h" // Include le definizioni e le dichiarazioni extern

// --- Definizione Variabili Globali ---

GameInfo games[MAX_GAMES]; // Verrà inizializzato in server.c
ClientInfo clients[MAX_TOTAL_CLIENTS]; // Verrà inizializzato in server.c
int server_fd = -1;
volatile sig_atomic_t keep_running = 1;
int next_game_id = 1;

// Inizializzazione statica dei mutex
pthread_mutex_t client_list_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t game_list_mutex = PTHREAD_MUTEX_INITIALIZER;

// Nota: L'inizializzazione effettiva degli stati degli array
// (es. games[i].state = GAME_STATE_EMPTY) avverrà all'avvio del server
// nella funzione main() o in una funzione init_server() dedicata.