#include "types.h"

GameInfo games[MAX_GAMES];
ClientInfo clients[MAX_TOTAL_CLIENTS];
int server_fd = -1;
volatile sig_atomic_t keep_running = 1;
int next_game_id = 1;

pthread_mutex_t client_list_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t game_list_mutex = PTHREAD_MUTEX_INITIALIZER;