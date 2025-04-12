/* ======== protocol.h ======== */
#ifndef PROTOCOL_H
#define PROTOCOL_H

#include "types.h"

extern const char* CMD_NAME_PREFIX;
extern const char* CMD_LIST;
extern const char* CMD_CREATE;
extern const char* CMD_JOIN_REQUEST_PREFIX;
extern const char* CMD_ACCEPT_PREFIX;
extern const char* CMD_REJECT_PREFIX;
extern const char* CMD_MOVE_PREFIX;
extern const char* CMD_QUIT;
extern const char* CMD_REMATCH_YES;
extern const char* CMD_REMATCH_NO;

extern const char* CMD_GET_NAME;
extern const char* CMD_REMATCH_OFFER;

extern const char* RESP_NAME_OK;
extern const char* RESP_CREATED_FMT;
extern const char* RESP_GAMES_LIST_PREFIX;
extern const char* RESP_REQUEST_SENT_FMT;
extern const char* RESP_JOIN_ACCEPTED_FMT;
extern const char* RESP_REJECT_OK_FMT;
extern const char* RESP_JOIN_REJECTED_FMT;
extern const char* RESP_QUIT_OK;
extern const char* RESP_REMATCH_ACCEPTED_FMT;
extern const char* RESP_REMATCH_DECLINED;

extern const char* NOTIFY_JOIN_REQUEST_FMT;
extern const char* NOTIFY_GAME_START_FMT;
extern const char* NOTIFY_REQUEST_CANCELLED_FMT;
extern const char* NOTIFY_OPPONENT_ACCEPTED_REMATCH;
extern const char* NOTIFY_OPPONENT_DECLINED;

extern const char* ERR_NAME_TAKEN;
extern const char* ERR_SERVER_FULL_GAMES;
extern const char* ERR_SERVER_FULL_SLOTS;
extern const char* ERR_INVALID_MOVE_FORMAT;
extern const char* ERR_INVALID_MOVE_BOUNDS;
extern const char* ERR_INVALID_MOVE_OCCUPIED;
extern const char* ERR_NOT_YOUR_TURN;
extern const char* ERR_GAME_NOT_FOUND;
extern const char* ERR_GAME_NOT_IN_PROGRESS;
extern const char* ERR_GAME_NOT_WAITING;
extern const char* ERR_GAME_ALREADY_STARTED;
extern const char* ERR_GAME_FINISHED;
extern const char* ERR_CANNOT_JOIN_OWN_GAME;
extern const char* ERR_ALREADY_PENDING;
extern const char* ERR_NO_PENDING_REQUEST;
extern const char* ERR_JOINER_LEFT;
extern const char* ERR_CREATOR_LEFT;
extern const char* ERR_NOT_IN_LOBBY;
extern const char* ERR_NOT_WAITING;
extern const char* ERR_NOT_PLAYING;
extern const char* ERR_UNKNOWN_COMMAND_FMT;
extern const char* ERR_NOT_FINISHED_GAME;
extern const char* ERR_NOT_THE_WINNER;
extern const char* ERR_NOT_IN_FINISHED_OR_DRAW_GAME;
extern const char* ERR_INVALID_REMATCH_CHOICE;
extern const char* ERR_DRAW_REMATCH_ONLY_PLAYER;
extern const char* ERR_GENERIC;

void process_name_command(int client_idx, const char* name_arg);
void process_list_command(int client_idx);
void process_create_command(int client_idx);
void process_join_request_command(int client_idx, const char* game_id_str);
void process_accept_command(int client_idx, const char* accepted_player_name);
void process_reject_command(int client_idx, const char* rejected_player_name);
void process_move_command(int client_idx, const char* move_args);
bool process_quit_command(int client_idx);
void process_rematch_command(int client_idx, const char* choice);
void send_unknown_command_error(int client_idx, const char* received_command, ClientState current_state);

#endif