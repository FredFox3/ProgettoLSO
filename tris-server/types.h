#ifndef TYPES_H
#define TYPES_H

#include <pthread.h>
#include <stdbool.h>
#include <netinet/in.h>
#include <signal.h>

#define PORT 12345
#define BUFFER_SIZE 1024
#define MAX_TOTAL_CLIENTS 10
#define MAX_GAMES 10
#define MAX_NAME_LEN 32

typedef enum
{
    CLIENT_STATE_CONNECTED,
    CLIENT_STATE_LOBBY,
    CLIENT_STATE_WAITING,
    CLIENT_STATE_PLAYING
} ClientState;

typedef enum
{
    GAME_STATE_EMPTY,
    GAME_STATE_WAITING,
    GAME_STATE_IN_PROGRESS,
    GAME_STATE_FINISHED
} GameState;

typedef enum
{
    CELL_EMPTY = 0,
    CELL_X,
    CELL_O
} Cell;

typedef enum
{
    REMATCH_CHOICE_PENDING = 0,
    REMATCH_CHOICE_YES,
    REMATCH_CHOICE_NO
} RematchChoice;

typedef struct
{
    int id;
    GameState state;
    Cell board[3][3];
    int player1_fd;
    int player2_fd;
    int current_turn_fd;
    char player1_name[MAX_NAME_LEN];
    char player2_name[MAX_NAME_LEN];
    int pending_joiner_fd;
    char pending_joiner_name[MAX_NAME_LEN];
    int winner_fd;
    RematchChoice player1_accepted_rematch;
    RematchChoice player2_accepted_rematch;
} GameInfo;

typedef struct
{
    int fd;
    ClientState state;
    char name[MAX_NAME_LEN];
    int game_id;
    bool active;
    pthread_t thread_id;
} ClientInfo;

extern GameInfo games[MAX_GAMES];
extern ClientInfo clients[MAX_TOTAL_CLIENTS];
extern int server_fd;
extern volatile sig_atomic_t keep_running;
extern int next_game_id;

extern pthread_mutex_t client_list_mutex;
extern pthread_mutex_t game_list_mutex;

#endif