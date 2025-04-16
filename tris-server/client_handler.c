#include "client_handler.h"
#include "types.h"
#include "utils.h"
#include "protocol.h"
#include "game_logic.h"
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>

void *handle_client(void *arg)
{
    if (!arg)
    {
        LOG("Errore: handle_client ha ricevuto un argomento NULL.\n");
        return NULL;
    }

    int client_index = *((int *)arg);
    free(arg);

    if (client_index < 0 || client_index >= MAX_TOTAL_CLIENTS)
    {
        LOG("Errore: handle_client ha ricevuto un indice client non valido %d.\n", client_index);
        return NULL;
    }

    int client_fd = -1;
    bool client_needs_name = false;
    pthread_mutex_lock(&client_list_mutex);
    if (clients[client_index].active)
    {
        client_fd = clients[client_index].fd;
        client_needs_name = (clients[client_index].state == CLIENT_STATE_CONNECTED);
    }
    else
    {
        LOG("Attenzione: handle_client avviato per un indice client già inattivo %d.\n", client_index);
        pthread_mutex_unlock(&client_list_mutex);
        return NULL;
    }
    pthread_mutex_unlock(&client_list_mutex);

    if (client_fd < 0)
    {
        LOG("Errore: handle_client fd < 0 per l'indice client attivo %d.\n", client_index);
        return NULL;
    }

    LOG("Thread avviato per il client fd %d (indice %d)\n", client_fd, client_index);

    if (client_needs_name)
    {
        if (!send_to_client(client_fd, CMD_GET_NAME))
        {
            LOG("Invio CMD_GET_NAME al client fd %d fallito. Chiusura connessione.\n", client_fd);
            goto cleanup_connection;
        }
    }

    char buffer[BUFFER_SIZE];
    bool client_connected = true;

    while (client_connected && keep_running)
    {
        memset(buffer, 0, BUFFER_SIZE);
        ssize_t bytes_read = read(client_fd, buffer, BUFFER_SIZE - 1);

        if (bytes_read == 0)
        {
            LOG("Client fd %d (indice %d) disconnesso correttamente (letti 0 byte).\n", client_fd, client_index);
            client_connected = false;
            break;
        }
        else if (bytes_read < 0)
        {
            if (errno == EINTR)
            {
                continue;
            }
            if (errno == ECONNRESET)
            {
                LOG("Client fd %d (indice %d) disconnesso bruscamente (ECONNRESET).\n", client_fd, client_index);
            }
            else
            {
                LOG_PERROR("Errore di lettura");
                fprintf(stderr, "    Client FD: %d, Indice: %d\n", client_fd, client_index);
            }
            client_connected = false;
            break;
        }

        buffer[strcspn(buffer, "\r\n")] = 0;

        ClientState current_state;
        char current_name[MAX_NAME_LEN];
        pthread_mutex_lock(&client_list_mutex);
        if (clients[client_index].active)
        {
            current_state = clients[client_index].state;
            strncpy(current_name, clients[client_index].name, MAX_NAME_LEN - 1);
            current_name[MAX_NAME_LEN - 1] = '\0';
        }
        else
        {
            LOG("Attenzione: Indice client %d diventato inattivo durante la lettura dello stato (fd %d).\n", client_index, client_fd);
            pthread_mutex_unlock(&client_list_mutex);
            client_connected = false;
            break;
        }
        pthread_mutex_unlock(&client_list_mutex);

        LOG("Ricevuto da fd %d (idx %d, nome '%s', stato %d): [%s]\n",
            client_fd, client_index, current_name[0] ? current_name : "(no name yet)", current_state, buffer);

        if (strncmp(buffer, CMD_NAME_PREFIX, strlen(CMD_NAME_PREFIX)) == 0 && current_state == CLIENT_STATE_CONNECTED)
        {
            process_name_command(client_index, buffer + strlen(CMD_NAME_PREFIX));
        }

        else if (strcmp(buffer, CMD_LIST) == 0 && (current_state == CLIENT_STATE_LOBBY || current_state == CLIENT_STATE_WAITING))
        {
            LOG("Elaborazione comando LIST per client %d nello stato %d.\n", client_index, current_state);
            process_list_command(client_index);
        }
        else if (strcmp(buffer, CMD_CREATE) == 0 && current_state == CLIENT_STATE_LOBBY)
        {
            process_create_command(client_index);
        }
        else if (strncmp(buffer, CMD_JOIN_REQUEST_PREFIX, strlen(CMD_JOIN_REQUEST_PREFIX)) == 0 && current_state == CLIENT_STATE_LOBBY)
        {
            process_join_request_command(client_index, buffer + strlen(CMD_JOIN_REQUEST_PREFIX));
        }
        else if (strncmp(buffer, CMD_ACCEPT_PREFIX, strlen(CMD_ACCEPT_PREFIX)) == 0 && current_state == CLIENT_STATE_WAITING)
        {
            process_accept_command(client_index, buffer + strlen(CMD_ACCEPT_PREFIX));
        }
        else if (strncmp(buffer, CMD_REJECT_PREFIX, strlen(CMD_REJECT_PREFIX)) == 0 && current_state == CLIENT_STATE_WAITING)
        {
            process_reject_command(client_index, buffer + strlen(CMD_REJECT_PREFIX));
        }
        else if (strncmp(buffer, CMD_MOVE_PREFIX, strlen(CMD_MOVE_PREFIX)) == 0 && current_state == CLIENT_STATE_PLAYING)
        {
            process_move_command(client_index, buffer + strlen(CMD_MOVE_PREFIX));
        }
        else if ((strcmp(buffer, CMD_REMATCH_YES) == 0 || strcmp(buffer, CMD_REMATCH_NO) == 0))
        {
            if (current_state == CLIENT_STATE_PLAYING)
            {
                process_rematch_command(client_index, buffer);
            }
            else
            {
                send_unknown_command_error(client_index, buffer, current_state);
            }
        }
        else if (strcmp(buffer, CMD_QUIT) == 0)
        {
            if (process_quit_command(client_index))
            {
                client_connected = false;
            }
        }
        else
        {
            if (current_state != CLIENT_STATE_CONNECTED)
            {
                send_unknown_command_error(client_index, buffer, current_state);
            }
        }
    }

cleanup_connection:

    int fd_handled_by_this_thread = client_fd;
    LOG("Pulizia connessione client avviata per fd %d (indice %d)\n", fd_handled_by_this_thread, client_index);

    if (fd_handled_by_this_thread >= 0)
    {
        close(fd_handled_by_this_thread);
    }

    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);

    int game_id_leaving = 0;
    char disconnecting_client_name[MAX_NAME_LEN] = {0};

    if (clients[client_index].active && clients[client_index].fd == fd_handled_by_this_thread)
    {
        game_id_leaving = clients[client_index].game_id;
        strncpy(disconnecting_client_name, clients[client_index].name, MAX_NAME_LEN - 1);
        disconnecting_client_name[MAX_NAME_LEN - 1] = '\0';

        clients[client_index].active = false;
        clients[client_index].fd = -1;
        clients[client_index].game_id = 0;
        clients[client_index].state = CLIENT_STATE_CONNECTED;
        clients[client_index].name[0] = '\0';

        LOG("Marco l'indice client %d (nome '%s', prev_fd %d) come inattivo.\n",
            client_index,
            disconnecting_client_name[0] ? disconnecting_client_name : "N/A",
            fd_handled_by_this_thread);
    }
    else
    {
        LOG("Salto reset per indice client %d: Slot inattivo o fd non corrispondente (slot fd: %d, fd thread: %d).\n",
            client_index, clients[client_index].fd, fd_handled_by_this_thread);
    }

    if (game_id_leaving > 0)
    {
        int game_idx = find_game_index_unsafe(game_id_leaving);
        if (game_idx != -1)
        {
            LOG("Client '%s' (prev_fd %d) era nella partita %d (idx %d). Gestione uscita.\n",
                disconnecting_client_name[0] ? disconnecting_client_name : "N/A",
                fd_handled_by_this_thread,
                game_id_leaving, game_idx);

            handle_player_leaving_game(game_idx, fd_handled_by_this_thread, disconnecting_client_name);
        }
        else
        {
            LOG("Client '%s' (prev_fd %d) disconnesso, ma ID partita associato %d non trovato.\n",
                disconnecting_client_name[0] ? disconnecting_client_name : "N/A",
                fd_handled_by_this_thread, game_id_leaving);
        }
    }
    else if (disconnecting_client_name[0] != '\0')
    {
        LOG("Client '%s' (prev_fd %d) disconnesso da stato non di gioco.\n", disconnecting_client_name, fd_handled_by_this_thread);
    }

    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);

    LOG("Thread terminato per la connessione relativa all'indice client %d (prev_fd %d).\n", client_index, fd_handled_by_this_thread);
    return NULL;
}