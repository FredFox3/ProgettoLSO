#ifndef GAME_LOGIC_H
#define GAME_LOGIC_H

#include "types.h"
#include <stddef.h>

extern const char* NOTIFY_OPPONENT_LEFT;
extern const char* NOTIFY_GAMEOVER_WIN;
extern const char* NOTIFY_GAMEOVER_LOSE;
extern const char* NOTIFY_GAMEOVER_DRAW;
extern const char* NOTIFY_YOUR_TURN;
extern const char* NOTIFY_BOARD_PREFIX;
extern const char* RESP_ERROR_PREFIX;

void init_board(Cell board[3][3]);
void board_to_string(const Cell board[3][3], char *out_str, size_t max_len);
bool check_winner(const Cell board[3][3], Cell player);
bool board_full(const Cell board[3][3]);

int find_game_index_unsafe(int game_id);
int find_client_index_unsafe(int fd);
int find_opponent_fd(const GameInfo* game, int player_fd);
void reset_game_slot_to_empty_unsafe(int game_idx);
bool handle_player_leaving_game(int game_idx, int leaving_client_fd, const char* leaving_client_name);
void broadcast_game_state(int game_idx);

#endif