#include "client_handler.h"
#include "types.h"
#include "utils.h"
#include "protocol.h"      // Include dichiarazioni
#include "game_logic.h"
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>

// --- NO DEFINIZIONE DI process_rematch_command QUI ---

void *handle_client(void *arg) {
    if (!arg) { /*...*/ return NULL; }
    int client_index = *((int*)arg); free(arg);
    if (client_index < 0 || client_index >= MAX_TOTAL_CLIENTS) { /*...*/ return NULL; }

    int client_fd = -1;
    bool client_needs_name = false;
    pthread_mutex_lock(&client_list_mutex);
    if (clients[client_index].active) {
        client_fd = clients[client_index].fd;
        client_needs_name = (clients[client_index].state == CLIENT_STATE_CONNECTED);
    } else { /*...*/ pthread_mutex_unlock(&client_list_mutex); return NULL; }
    pthread_mutex_unlock(&client_list_mutex);
    if (client_fd < 0) { /*...*/ return NULL; }

    LOG("Thread started for client fd %d (index %d)\n", client_fd, client_index);
    if (client_needs_name) { if (!send_to_client(client_fd, CMD_GET_NAME)) goto cleanup_connection; }

    char buffer[BUFFER_SIZE];
    bool client_connected = true;

    while (client_connected && keep_running) {
        memset(buffer, 0, BUFFER_SIZE);
        ssize_t bytes_read = read(client_fd, buffer, BUFFER_SIZE - 1);

        if (bytes_read <= 0) { /* gestione disconn/error read */ client_connected = false; break; }
        buffer[strcspn(buffer, "\r\n")] = 0;

        ClientState current_state;
        char current_name[MAX_NAME_LEN];
        pthread_mutex_lock(&client_list_mutex);
        if (clients[client_index].active) {
            current_state = clients[client_index].state;
            strncpy(current_name, clients[client_index].name, MAX_NAME_LEN-1); current_name[MAX_NAME_LEN-1]='\0';
        } else { pthread_mutex_unlock(&client_list_mutex); client_connected=false; break; }
        pthread_mutex_unlock(&client_list_mutex);

        LOG("Received from fd %d (idx %d, name '%s', state %d): [%s]\n", client_fd, client_index, current_name[0]?current_name:"(no name yet)", current_state, buffer);

        // Parsing comandi
        if (strncmp(buffer, CMD_NAME_PREFIX, strlen(CMD_NAME_PREFIX)) == 0 && current_state == CLIENT_STATE_CONNECTED) {
            process_name_command(client_index, buffer + strlen(CMD_NAME_PREFIX));
        } else if (strcmp(buffer, CMD_LIST) == 0 && current_state == CLIENT_STATE_LOBBY) {
            process_list_command(client_index);
        } else if (strcmp(buffer, CMD_CREATE) == 0 && current_state == CLIENT_STATE_LOBBY) {
            process_create_command(client_index);
        } else if (strncmp(buffer, CMD_JOIN_REQUEST_PREFIX, strlen(CMD_JOIN_REQUEST_PREFIX)) == 0 && current_state == CLIENT_STATE_LOBBY) {
             process_join_request_command(client_index, buffer + strlen(CMD_JOIN_REQUEST_PREFIX));
        } else if (strncmp(buffer, CMD_ACCEPT_PREFIX, strlen(CMD_ACCEPT_PREFIX)) == 0 && current_state == CLIENT_STATE_WAITING) {
             process_accept_command(client_index, buffer + strlen(CMD_ACCEPT_PREFIX));
        } else if (strncmp(buffer, CMD_REJECT_PREFIX, strlen(CMD_REJECT_PREFIX)) == 0 && current_state == CLIENT_STATE_WAITING) {
             process_reject_command(client_index, buffer + strlen(CMD_REJECT_PREFIX));
        } else if (strncmp(buffer, CMD_MOVE_PREFIX, strlen(CMD_MOVE_PREFIX)) == 0 && current_state == CLIENT_STATE_PLAYING) {
             process_move_command(client_index, buffer + strlen(CMD_MOVE_PREFIX));
        } else if ((strcmp(buffer, CMD_REMATCH_YES) == 0 || strcmp(buffer, CMD_REMATCH_NO) == 0)) { // REMATCH
            if (current_state == CLIENT_STATE_PLAYING) { // Stato PLAYING è usato anche post-partita
                process_rematch_command(client_index, buffer); // Chiama la funzione definita altrove
            } else {
                send_unknown_command_error(client_index, buffer, current_state);
            }
        } else if (strcmp(buffer, CMD_QUIT) == 0) { // QUIT
            if (process_quit_command(client_index)) { client_connected = false; }
        } else { // Sconosciuto
            if (current_state != CLIENT_STATE_CONNECTED) send_unknown_command_error(client_index, buffer, current_state);
        }
    } // fine while

cleanup_connection:
    LOG("Cleaning up client connection (fd %d, index %d)\n", client_fd, client_index);
    if (client_fd >= 0) { close(client_fd); }
    pthread_mutex_lock(&client_list_mutex); pthread_mutex_lock(&game_list_mutex);
    int game_id_leaving = clients[client_index].active ? clients[client_index].game_id : 0;
    char disconnecting_client_name[MAX_NAME_LEN];
    int leaving_fd_copy = clients[client_index].active ? clients[client_index].fd : -1;
    if (clients[client_index].active) {
        strncpy(disconnecting_client_name, clients[client_index].name, MAX_NAME_LEN-1); disconnecting_client_name[MAX_NAME_LEN - 1] = '\0';
        // Resetta slot
        clients[client_index].active = false; clients[client_index].fd = -1;
        clients[client_index].game_id = 0; clients[client_index].state = CLIENT_STATE_CONNECTED;
        clients[client_index].name[0] = '\0';
         LOG("Marking client index %d (name '%s') as inactive.\n", client_index, disconnecting_client_name);
    } else { disconnecting_client_name[0] = '\0'; }

    if (game_id_leaving > 0) { // Gestione uscita da partita
        int game_idx = find_game_index_unsafe(game_id_leaving);
        if (game_idx != -1) {
             LOG("Client '%s' leaving game %d (idx %d), handling departure (using prev fd %d).\n", disconnecting_client_name, game_id_leaving, game_idx, leaving_fd_copy);
             handle_player_leaving_game(game_idx, leaving_fd_copy, disconnecting_client_name);
        } else { LOG("Client '%s' disconnected, game ID %d not found.\n", disconnecting_client_name, game_id_leaving); }
    } else { LOG("Client '%s' (prev fd %d) disconnected from non-game state.\n", disconnecting_client_name, leaving_fd_copy); }
    pthread_mutex_unlock(&game_list_mutex); pthread_mutex_unlock(&client_list_mutex);
    LOG("Thread finished for connection (prev fd %d, index %d)\n", leaving_fd_copy, client_index);
    return NULL;
}