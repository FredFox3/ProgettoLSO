#include "protocol.h"
#include "utils.h"
#include "game_logic.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

const char *CMD_NAME_PREFIX = "NAME ";
const char *CMD_LIST = "LIST";
const char *CMD_CREATE = "CREATE";
const char *CMD_JOIN_REQUEST_PREFIX = "JOIN_REQUEST ";
const char *CMD_ACCEPT_PREFIX = "ACCEPT ";
const char *CMD_REJECT_PREFIX = "REJECT ";
const char *CMD_MOVE_PREFIX = "MOVE ";
const char *CMD_QUIT = "QUIT";
const char *CMD_REMATCH_YES = "REMATCH YES";
const char *CMD_REMATCH_NO = "REMATCH NO";
const char *CMD_GET_NAME = "CMD:GET_NAME\n";
const char *CMD_REMATCH_OFFER = "CMD:REMATCH_OFFER\n";
const char *RESP_NAME_OK = "RESP:NAME_OK\n";
const char *RESP_CREATED_FMT = "RESP:CREATED %d\n";
const char *RESP_GAMES_LIST_PREFIX = "RESP:GAMES_LIST;";
const char *RESP_REQUEST_SENT_FMT = "RESP:REQUEST_SENT %d\n";
const char *RESP_JOIN_ACCEPTED_FMT = "RESP:JOIN_ACCEPTED %d %c %s\n";
const char *RESP_REJECT_OK_FMT = "RESP:REJECT_OK %s\n";
const char *RESP_JOIN_REJECTED_FMT = "RESP:JOIN_REJECTED %d %s\n";
const char *RESP_QUIT_OK = "RESP:QUIT_OK Tornare alla lobby.\n";
const char *RESP_REMATCH_ACCEPTED_FMT = "RESP:REMATCH_ACCEPTED %d In attesa di un nuovo avversario.\n";
const char *RESP_REMATCH_DECLINED = "RESP:REMATCH_DECLINED Tornare alla lobby.\n";
const char *NOTIFY_JOIN_REQUEST_FMT = "NOTIFY:JOIN_REQUEST %s\n";
const char *NOTIFY_GAME_START_FMT = "NOTIFY:GAME_START %d %c %s\n";
const char *NOTIFY_REQUEST_CANCELLED_FMT = "NOTIFY:REQUEST_CANCELLED %s se n'è andato\n";
const char *NOTIFY_OPPONENT_ACCEPTED_REMATCH = "NOTIFY:OPPONENT_ACCEPTED_REMATCH Tornare alla lobby.\n";
const char *NOTIFY_OPPONENT_DECLINED = "NOTIFY:OPPONENT_DECLINED Tornare alla lobby.\n";
const char *ERR_NAME_TAKEN = "ERROR:NAME_TAKEN\n";
const char *ERR_SERVER_FULL_GAMES = "ERROR:Server pieno, impossibile creare una partita (nessuno slot disponibile)\n";
const char *ERR_SERVER_FULL_SLOTS = "ERROR:Il server è pieno. Riprova più tardi.\n";
const char *ERR_INVALID_MOVE_FORMAT = "ERROR:Formato della mossa non valido. Usa: MOVE <riga> <colonna>\n";
const char *ERR_INVALID_MOVE_BOUNDS = "ERROR:Mossa non valida (fuori dai limiti 0-2)\n";
const char *ERR_INVALID_MOVE_OCCUPIED = "ERROR:Mossa non valida (cella occupata)\n";
const char *ERR_NOT_YOUR_TURN = "ERROR:Non è il tuo turno\n";
const char *ERR_GAME_NOT_FOUND = "ERROR:Partita non trovata\n";
const char *ERR_GAME_NOT_IN_PROGRESS = "ERROR:Partita non in corso\n";
const char *ERR_GAME_NOT_WAITING = "ERROR:La partita non è in attesa di giocatori\n";
const char *ERR_GAME_ALREADY_STARTED = "ERROR:Partita già iniziata\n";
const char *ERR_GAME_FINISHED = "ERROR:Partita terminata\n";
const char *ERR_CANNOT_JOIN_OWN_GAME = "ERROR:Non puoi unirti alla tua partita\n";
const char *ERR_ALREADY_PENDING = "ERROR:Il creatore della partita è occupato con un'altra richiesta di adesione\n";
const char *ERR_NO_PENDING_REQUEST = "ERROR:Nessuna richiesta di adesione in sospeso trovata per questo giocatore\n";
const char *ERR_JOINER_LEFT = "ERROR:Il giocatore che ha richiesto di unirsi non è più disponibile.\n";
const char *ERR_CREATOR_LEFT = "ERROR:Il creatore della partita sembra disconnesso.\n";
const char *ERR_NOT_IN_LOBBY = "ERROR:Comando disponibile solo nello stato LOBBY\n";
const char *ERR_NOT_WAITING = "ERROR:Comando disponibile solo nello stato WAITING\n";
const char *ERR_NOT_PLAYING = "ERROR:Comando disponibile solo nello stato PLAYING o a partita terminata\n";
const char *ERR_UNKNOWN_COMMAND_FMT = "ERROR:Comando sconosciuto o stato non valido (%d) per il comando: %s\n";
const char *ERR_NOT_FINISHED_GAME = "ERROR:Comando disponibile solo dopo che la partita è terminata\n";
const char *ERR_NOT_THE_WINNER = "ERROR:Solo il vincitore può decidere il rematch\n";
const char *ERR_NOT_IN_FINISHED_OR_DRAW_GAME = "ERROR:Comando rematch non valido nello stato attuale della partita\n";
const char *ERR_INVALID_REMATCH_CHOICE = "ERROR:Scelta del comando rematch non valido\n";
const char *ERR_DRAW_REMATCH_ONLY_PLAYER = "ERROR:Impossibile richiedere il rematch dopo un pareggio se non si è un giocatore nella partita\n";
const char *ERR_GENERIC = "ERROR:Si è verificato un errore interno del server.\n";

void process_name_command(int client_idx, const char *name_arg)
{
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !name_arg)
        return;
    int fd = -1;
    ClientState current_state;
    bool name_taken = false;

    pthread_mutex_lock(&client_list_mutex);

    if (!clients[client_idx].active || clients[client_idx].state != CLIENT_STATE_CONNECTED)
    {
        fd = clients[client_idx].active ? clients[client_idx].fd : -1;
        current_state = clients[client_idx].active ? clients[client_idx].state : CLIENT_STATE_CONNECTED;
        pthread_mutex_unlock(&client_list_mutex);
        LOG("Client fd %d (idx %d) ha inviato NAME in stato errato (%d) o è inattivo.\n", fd, client_idx, current_state);
        return;
    }
    fd = clients[client_idx].fd;

    char clean_name[MAX_NAME_LEN];
    strncpy(clean_name, name_arg, MAX_NAME_LEN - 1);
    clean_name[MAX_NAME_LEN - 1] = '\0';
    clean_name[strcspn(clean_name, "\r\n ")] = 0;

    if (strlen(clean_name) == 0)
    {
        pthread_mutex_unlock(&client_list_mutex);
        LOG("Client fd %d (idx %d) ha tentato di registrare un nome vuoto.\n", fd, client_idx);
        send_to_client(fd, "ERROR:Name cannot be empty.\n");
        return;
    }

    for (int i = 0; i < MAX_TOTAL_CLIENTS; ++i)
    {
        if (i != client_idx && clients[i].active && clients[i].name[0] != '\0')
        {
            if (strcmp(clients[i].name, clean_name) == 0)
            {
                name_taken = true;
                break;
            }
        }
    }

    if (name_taken)
    {
        pthread_mutex_unlock(&client_list_mutex);
        LOG("Client fd %d (idx %d) ha tentato un nome duplicato: %s\n", fd, client_idx, clean_name);
        send_to_client(fd, ERR_NAME_TAKEN);
    }
    else
    {
        strncpy(clients[client_idx].name, clean_name, MAX_NAME_LEN - 1);
        clients[client_idx].name[MAX_NAME_LEN - 1] = '\0';
        clients[client_idx].state = CLIENT_STATE_LOBBY;
        LOG("Client fd %d (idx %d) ha registrato il nome: %s\n", fd, client_idx, clients[client_idx].name);

        pthread_mutex_unlock(&client_list_mutex);
        send_to_client(fd, RESP_NAME_OK);
    }
}

void process_list_command(int client_idx)
{
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS)
        return;
    char response[BUFFER_SIZE * 2];
    response[0] = '\0';
    strcat(response, RESP_GAMES_LIST_PREFIX);
    bool first_game = true;
    int client_fd = -1;
    pthread_mutex_lock(&client_list_mutex);
    if (!clients[client_idx].active)
    {
        pthread_mutex_unlock(&client_list_mutex);
        return;
    }
    client_fd = clients[client_idx].fd;
    pthread_mutex_unlock(&client_list_mutex);

    pthread_mutex_lock(&game_list_mutex);
    for (int i = 0; i < MAX_GAMES; ++i)
    {
        if (games[i].state != GAME_STATE_EMPTY)
        {
            if (!first_game)
                strncat(response, "|", sizeof(response) - strlen(response) - 1);
            char state_str[20];
            switch (games[i].state)
            {
            case GAME_STATE_WAITING:
                strcpy(state_str, "Waiting");
                break;
            case GAME_STATE_IN_PROGRESS:
                strcpy(state_str, "In Progress");
                break;
            case GAME_STATE_FINISHED:
                strcpy(state_str, "Finished");
                break;
            default:
                strcpy(state_str, "Unknown");
                break;
            }
            char game_info[150];
            snprintf(game_info, sizeof(game_info), "%d,%s,%s", games[i].id, games[i].player1_name[0] ? games[i].player1_name : "?", state_str);
            if ((games[i].state == GAME_STATE_IN_PROGRESS || games[i].state == GAME_STATE_FINISHED) && games[i].player2_name[0])
            {
                strncat(game_info, ",", sizeof(game_info) - strlen(game_info) - 1);
                strncat(game_info, games[i].player2_name, sizeof(game_info) - strlen(game_info) - 1);
            }
            strncat(response, game_info, sizeof(response) - strlen(response) - 1);
            first_game = false;
        }
    }
    pthread_mutex_unlock(&game_list_mutex);
    strncat(response, "\n", sizeof(response) - strlen(response) - 1);
    send_to_client(client_fd, response);
}

void process_create_command(int client_idx)
{
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS)
        return;
    char response[BUFFER_SIZE];
    int game_idx = -1;
    int created_game_id = -1;
    int client_fd = -1;
    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);
    if (!clients[client_idx].active || clients[client_idx].state != CLIENT_STATE_LOBBY)
    {
        client_fd = clients[client_idx].active ? clients[client_idx].fd : -1;
        ClientState state = clients[client_idx].active ? clients[client_idx].state : CLIENT_STATE_LOBBY;
        LOG("Client fd %d (idx %d) ha inviato CREATE in stato errato (%d).\n", client_fd, client_idx, state);
        snprintf(response, sizeof(response), "%s\n", ERR_NOT_IN_LOBBY);
        goto create_cleanup;
    }
    client_fd = clients[client_idx].fd;
    for (int i = 0; i < MAX_GAMES; ++i)
    {
        if (games[i].state == GAME_STATE_EMPTY)
        {
            game_idx = i;
            break;
        }
    }
    if (game_idx != -1)
    {
        LOG("Uso slot partita vuoto %d per nuova partita.\n", game_idx);
        created_game_id = next_game_id++;
        games[game_idx].id = created_game_id;
        games[game_idx].state = GAME_STATE_WAITING;
        init_board(games[game_idx].board);
        games[game_idx].player1_fd = client_fd;
        games[game_idx].player2_fd = -1;
        games[game_idx].current_turn_fd = -1;
        games[game_idx].winner_fd = -1;
        games[game_idx].player1_accepted_rematch = REMATCH_CHOICE_PENDING;
        games[game_idx].player2_accepted_rematch = REMATCH_CHOICE_PENDING;
        strncpy(games[game_idx].player1_name, clients[client_idx].name, MAX_NAME_LEN);
        games[game_idx].player1_name[MAX_NAME_LEN - 1] = '\0';
        games[game_idx].player2_name[0] = '\0';
        games[game_idx].pending_joiner_fd = -1;
        games[game_idx].pending_joiner_name[0] = '\0';
        clients[client_idx].state = CLIENT_STATE_WAITING;
        clients[client_idx].game_id = created_game_id;
        snprintf(response, sizeof(response), RESP_CREATED_FMT, created_game_id);
        LOG("Partita %d creata da %s (fd %d) nello slot %d.\n", created_game_id, games[game_idx].player1_name, client_fd, game_idx);
    }
    else
    {
        snprintf(response, sizeof(response), "%s\n", ERR_SERVER_FULL_GAMES);
        LOG("Creazione partita fallita per %s (fd %d): Nessuno slot partita vuoto.\n", clients[client_idx].name, client_fd);
    }
create_cleanup:
    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);
    if (client_fd >= 0)
    {
        send_to_client(client_fd, response);
    }
}

void process_join_request_command(int client_idx, const char *game_id_str)
{
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !game_id_str)
        return;
    int game_id_to_join = atoi(game_id_str);
    int game_idx = -1;
    int creator_fd = -1;
    int requester_fd = -1;
    char requester_name[MAX_NAME_LEN] = {0};
    char response_requester[BUFFER_SIZE];
    char notify_creator[BUFFER_SIZE];
    bool proceed = false;
    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);
    if (!clients[client_idx].active || clients[client_idx].state != CLIENT_STATE_LOBBY)
    {
        requester_fd = clients[client_idx].active ? clients[client_idx].fd : -1;
        snprintf(response_requester, sizeof(response_requester), "%s\n", ERR_NOT_IN_LOBBY);
        goto join_req_cleanup;
    }
    requester_fd = clients[client_idx].fd;
    strncpy(requester_name, clients[client_idx].name, MAX_NAME_LEN - 1);
    requester_name[MAX_NAME_LEN - 1] = '\0';
    game_idx = find_game_index_unsafe(game_id_to_join);
    if (game_idx == -1)
    {
        snprintf(response_requester, sizeof(response_requester), "%s %d\n", ERR_GAME_NOT_FOUND, game_id_to_join);
        goto join_req_cleanup;
    }
    GameInfo *game = &games[game_idx];
    creator_fd = game->player1_fd;
    if (game->state != GAME_STATE_WAITING)
    {
        snprintf(response_requester, sizeof(response_requester), "%s\n", ERR_GAME_NOT_WAITING);
        goto join_req_cleanup;
    }
    if (creator_fd == requester_fd)
    {
        snprintf(response_requester, sizeof(response_requester), "%s\n", ERR_CANNOT_JOIN_OWN_GAME);
        goto join_req_cleanup;
    }
    if (game->pending_joiner_fd != -1)
    {
        snprintf(response_requester, sizeof(response_requester), "%s\n", ERR_ALREADY_PENDING);
        goto join_req_cleanup;
    }
    if (creator_fd < 0)
    {
        snprintf(response_requester, sizeof(response_requester), "%s\n", ERR_CREATOR_LEFT);
        goto join_req_cleanup;
    }
    game->pending_joiner_fd = requester_fd;
    strncpy(game->pending_joiner_name, requester_name, MAX_NAME_LEN - 1);
    game->pending_joiner_name[MAX_NAME_LEN - 1] = '\0';
    snprintf(notify_creator, sizeof(notify_creator), NOTIFY_JOIN_REQUEST_FMT, requester_name);
    snprintf(response_requester, sizeof(response_requester), RESP_REQUEST_SENT_FMT, game_id_to_join);
    LOG("Client %s ha richiesto di unirsi alla partita %d. Notifico il creatore %s\n", requester_name, game_id_to_join, game->player1_name);
    proceed = true;
join_req_cleanup:
    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);
    if (proceed)
    {
        if (!send_to_client(creator_fd, notify_creator))
        {
            LOG("Invio richiesta join al creatore fd %d fallito. Annullamento.\n", creator_fd);
            pthread_mutex_lock(&game_list_mutex);
            if (game_idx != -1 && find_game_index_unsafe(game_id_to_join) == game_idx && games[game_idx].pending_joiner_fd == requester_fd)
            {
                games[game_idx].pending_joiner_fd = -1;
                games[game_idx].pending_joiner_name[0] = '\0';
            }
            pthread_mutex_unlock(&game_list_mutex);
            snprintf(response_requester, sizeof(response_requester), "%s\n", ERR_CREATOR_LEFT);
            send_to_client(requester_fd, response_requester);
        }
        else
        {
            send_to_client(requester_fd, response_requester);
        }
    }
    else if (requester_fd >= 0)
    {
        send_to_client(requester_fd, response_requester);
    }
}

void process_accept_command(int client_idx, const char *accepted_player_name)
{
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !accepted_player_name)
        return;
    int creator_idx = client_idx;
    int creator_fd = -1;
    int joiner_idx = -1;
    int joiner_fd = -1;
    int game_idx = -1;
    int current_game_id = -1;
    char response_creator[BUFFER_SIZE] = {0};
    char response_joiner[BUFFER_SIZE] = {0};
    char notify_creator_start[BUFFER_SIZE] = {0};
    char notify_joiner_start[BUFFER_SIZE] = {0};
    bool start_game = false;
    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);
    if (!clients[creator_idx].active || clients[creator_idx].state != CLIENT_STATE_WAITING)
    {
        snprintf(response_creator, sizeof(response_creator), "%s\n", ERR_NOT_WAITING);
        goto accept_cleanup;
    }
    creator_fd = clients[creator_idx].fd;
    current_game_id = clients[creator_idx].game_id;
    game_idx = find_game_index_unsafe(current_game_id);
    if (game_idx == -1)
    {
        snprintf(response_creator, sizeof(response_creator), "%s %d\n", ERR_GAME_NOT_FOUND, current_game_id);
        goto accept_cleanup;
    }
    GameInfo *game = &games[game_idx];
    if (game->state != GAME_STATE_WAITING)
    {
        snprintf(response_creator, sizeof(response_creator), "%s\n", ERR_GAME_NOT_WAITING);
        goto accept_cleanup;
    }
    if (game->player1_fd != creator_fd)
    {
        snprintf(response_creator, sizeof(response_creator), "ERROR:Not creator\n");
        goto accept_cleanup;
    }
    if (game->pending_joiner_fd < 0 || strcmp(game->pending_joiner_name, accepted_player_name) != 0)
    {
        snprintf(response_creator, sizeof(response_creator), "%s '%s'\n", ERR_NO_PENDING_REQUEST, accepted_player_name);
        goto accept_cleanup;
    }
    joiner_fd = game->pending_joiner_fd;
    joiner_idx = find_client_index_unsafe(joiner_fd);
    if (joiner_idx == -1 || !clients[joiner_idx].active)
    {
        snprintf(response_creator, sizeof(response_creator), "%s\n", ERR_JOINER_LEFT);
        game->pending_joiner_fd = -1;
        game->pending_joiner_name[0] = '\0';
        goto accept_cleanup;
    }
    LOG("Creatore %s HA ACCETTATO richiesta join da %s per partita %d\n", clients[creator_idx].name, accepted_player_name, current_game_id);
    game->player2_fd = joiner_fd;
    strncpy(game->player2_name, accepted_player_name, MAX_NAME_LEN - 1);
    game->player2_name[MAX_NAME_LEN - 1] = '\0';
    game->state = GAME_STATE_IN_PROGRESS;
    game->current_turn_fd = creator_fd;
    game->pending_joiner_fd = -1;
    game->pending_joiner_name[0] = '\0';
    clients[creator_idx].state = CLIENT_STATE_PLAYING;
    clients[joiner_idx].state = CLIENT_STATE_PLAYING;
    clients[joiner_idx].game_id = current_game_id;
    snprintf(response_joiner, sizeof(response_joiner), RESP_JOIN_ACCEPTED_FMT, current_game_id, 'O', clients[creator_idx].name);
    snprintf(notify_joiner_start, sizeof(notify_joiner_start), NOTIFY_GAME_START_FMT, current_game_id, 'O', clients[creator_idx].name);
    snprintf(notify_creator_start, sizeof(notify_creator_start), NOTIFY_GAME_START_FMT, current_game_id, 'X', accepted_player_name);
    start_game = true;
accept_cleanup:
    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);
    if (start_game)
    {
        send_to_client(joiner_fd, response_joiner);
        send_to_client(joiner_fd, notify_joiner_start);
        send_to_client(creator_fd, notify_creator_start);

        pthread_mutex_lock(&game_list_mutex);
        if (game_idx != -1)
            broadcast_game_state(game_idx);
        pthread_mutex_unlock(&game_list_mutex);
    }
    else if (creator_fd >= 0)
    {
        send_to_client(creator_fd, response_creator);
    }
}

void process_reject_command(int client_idx, const char *rejected_player_name)
{
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !rejected_player_name)
        return;
    int creator_idx = client_idx;
    int creator_fd = -1;
    int joiner_fd = -1;
    int game_idx = -1;
    int current_game_id = -1;
    char response_creator[BUFFER_SIZE] = {0};
    char response_joiner[BUFFER_SIZE] = {0};
    bool rejected = false;
    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);
    if (!clients[creator_idx].active || clients[creator_idx].state != CLIENT_STATE_WAITING)
    {
        snprintf(response_creator, sizeof(response_creator), "%s\n", ERR_NOT_WAITING);
        goto reject_cleanup;
    }
    creator_fd = clients[creator_idx].fd;
    current_game_id = clients[creator_idx].game_id;
    game_idx = find_game_index_unsafe(current_game_id);
    if (game_idx == -1)
    {
        snprintf(response_creator, sizeof(response_creator), "%s %d\n", ERR_GAME_NOT_FOUND, current_game_id);
        goto reject_cleanup;
    }
    GameInfo *game = &games[game_idx];
    if (game->state != GAME_STATE_WAITING)
    {
        snprintf(response_creator, sizeof(response_creator), "%s\n", ERR_GAME_NOT_WAITING);
        goto reject_cleanup;
    }
    if (game->player1_fd != creator_fd)
    {
        snprintf(response_creator, sizeof(response_creator), "ERROR:Not creator\n");
        goto reject_cleanup;
    }
    if (game->pending_joiner_fd < 0 || strcmp(game->pending_joiner_name, rejected_player_name) != 0)
    {
        snprintf(response_creator, sizeof(response_creator), "%s '%s'\n", ERR_NO_PENDING_REQUEST, rejected_player_name);
        goto reject_cleanup;
    }
    joiner_fd = game->pending_joiner_fd;
    LOG("Creatore %s HA RIFIUTATO richiesta join da %s per partita %d\n", clients[creator_idx].name, rejected_player_name, current_game_id);
    game->pending_joiner_fd = -1;
    game->pending_joiner_name[0] = '\0';
    snprintf(response_joiner, sizeof(response_joiner), RESP_JOIN_REJECTED_FMT, current_game_id, clients[creator_idx].name);
    snprintf(response_creator, sizeof(response_creator), RESP_REJECT_OK_FMT, rejected_player_name);
    rejected = true;
reject_cleanup:
    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);
    if (rejected)
    {
        send_to_client(joiner_fd, response_joiner);
        send_to_client(creator_fd, response_creator);
    }
    else if (creator_fd >= 0)
    {
        send_to_client(creator_fd, response_creator);
    }
}

void process_move_command(int client_idx, const char *move_args)
{
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !move_args)
        return;
    int r, c;
    int player_fd = -1;
    int game_idx = -1;
    int current_game_id = -1;
    char response[BUFFER_SIZE] = {0};
    bool move_made = false;
    bool game_over = false;
    char game_over_status_self[20] = {0};
    char game_over_status_opponent[20] = {0};
    int winner_fd_if_game_over = -1;
    int opponent_fd_if_game_over = -1;
    int opponent_idx_if_game_over = -1;
    char client_name[MAX_NAME_LEN] = {0};

    if (sscanf(move_args, "%d %d", &r, &c) != 2)
    {
        pthread_mutex_lock(&client_list_mutex);
        if (clients[client_idx].active)
            player_fd = clients[client_idx].fd;
        pthread_mutex_unlock(&client_list_mutex);
        if (player_fd >= 0)
            snprintf(response, sizeof(response), "%s\n", ERR_INVALID_MOVE_FORMAT);
        goto move_error_exit;
    }

    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);

    if (!clients[client_idx].active)
    {
        goto move_cleanup;
    }
    player_fd = clients[client_idx].fd;
    strncpy(client_name, clients[client_idx].name, MAX_NAME_LEN - 1);
    client_name[MAX_NAME_LEN - 1] = '\0';

    if (clients[client_idx].state != CLIENT_STATE_PLAYING)
    {
        snprintf(response, sizeof(response), "%s\n", ERR_NOT_PLAYING);
        goto move_cleanup;
    }
    current_game_id = clients[client_idx].game_id;
    if (current_game_id <= 0)
    {
        snprintf(response, sizeof(response), "%s\n", ERR_NOT_PLAYING);
        goto move_cleanup;
    }
    game_idx = find_game_index_unsafe(current_game_id);
    if (game_idx == -1)
    {
        snprintf(response, sizeof(response), "%s %d\n", ERR_GAME_NOT_FOUND, current_game_id);
        clients[client_idx].state = CLIENT_STATE_LOBBY;
        clients[client_idx].game_id = 0;
        goto move_cleanup;
    }

    GameInfo *game = &games[game_idx];
    if (game->state != GAME_STATE_IN_PROGRESS)
    {
        snprintf(response, sizeof(response), "%s\n", ERR_GAME_NOT_IN_PROGRESS);
        goto move_cleanup;
    }
    if (game->current_turn_fd != player_fd)
    {
        snprintf(response, sizeof(response), "%s\n", ERR_NOT_YOUR_TURN);
        goto move_cleanup;
    }
    if (r < 0 || r > 2 || c < 0 || c > 2)
    {
        snprintf(response, sizeof(response), "%s\n", ERR_INVALID_MOVE_BOUNDS);
        goto move_cleanup;
    }
    if (game->board[r][c] != CELL_EMPTY)
    {
        snprintf(response, sizeof(response), "%s\n", ERR_INVALID_MOVE_OCCUPIED);
        goto move_cleanup;
    }

    Cell player_symbol = (player_fd == game->player1_fd) ? CELL_X : CELL_O;
    game->board[r][c] = player_symbol;
    LOG("Giocatore '%s' (fd %d, %c) ha mosso in %d,%d nella partita %d.\n", client_name, player_fd, (player_symbol == CELL_X ? 'X' : 'O'), r, c, current_game_id);
    move_made = true;
    opponent_fd_if_game_over = find_opponent_fd(game, player_fd);
    opponent_idx_if_game_over = find_client_index_unsafe(opponent_fd_if_game_over);

    if (check_winner(game->board, player_symbol))
    {
        game_over = true;
        game->state = GAME_STATE_FINISHED;
        game->current_turn_fd = -1;
        game->winner_fd = player_fd;
        winner_fd_if_game_over = player_fd;
        strcpy(game_over_status_self, "WIN");
        strcpy(game_over_status_opponent, "LOSE");
        LOG("Partita %d terminata. Vincitore: '%s' (fd %d).\n", current_game_id, client_name, player_fd);

        if (opponent_idx_if_game_over != -1 && clients[opponent_idx_if_game_over].active)
        {
            LOG("Imposto stato del perdente '%s' (fd %d, idx %d) a LOBBY sul server.\n",
                clients[opponent_idx_if_game_over].name, opponent_fd_if_game_over, opponent_idx_if_game_over);
            clients[opponent_idx_if_game_over].state = CLIENT_STATE_LOBBY;
            clients[opponent_idx_if_game_over].game_id = 0;
        }
    }
    else if (board_full(game->board))
    {
        game_over = true;
        game->state = GAME_STATE_FINISHED;
        game->current_turn_fd = -1;
        game->winner_fd = -1;
        winner_fd_if_game_over = -1;
        strcpy(game_over_status_self, "DRAW");
        strcpy(game_over_status_opponent, "DRAW");
        LOG("Partita %d terminata. Risultato: PAREGGIO.\n", current_game_id);
    }
    else
    {
        game->current_turn_fd = opponent_fd_if_game_over;
    }

move_cleanup:
    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);

    if (move_made)
    {
        pthread_mutex_lock(&game_list_mutex);
        if (find_game_index_unsafe(current_game_id) == game_idx)
        {
            broadcast_game_state(game_idx);
        }
        pthread_mutex_unlock(&game_list_mutex);

        if (game_over)
        {
            char notify_self[BUFFER_SIZE], notify_opponent[BUFFER_SIZE];
            snprintf(notify_self, sizeof(notify_self), "NOTIFY:GAMEOVER %s\n", game_over_status_self);
            snprintf(notify_opponent, sizeof(notify_opponent), "NOTIFY:GAMEOVER %s\n", game_over_status_opponent);

            send_to_client(player_fd, notify_self);
            if (opponent_fd_if_game_over >= 0)
                send_to_client(opponent_fd_if_game_over, notify_opponent);

            if (winner_fd_if_game_over != -1)
            {
                LOG("Invio offerta rematch al vincitore '%s' (fd %d) per partita %d\n", client_name, winner_fd_if_game_over, current_game_id);
                send_to_client(winner_fd_if_game_over, CMD_REMATCH_OFFER);

                if (opponent_fd_if_game_over >= 0)
                {
                    LOG("Invio al perdente (fd %d) comando diretto 'back to lobby' (RESP:REMATCH_DECLINED) per partita %d\n", opponent_fd_if_game_over, current_game_id);
                    send_to_client(opponent_fd_if_game_over, RESP_REMATCH_DECLINED);
                }
            }
            else
            {
                LOG("Invio offerta rematch a entrambi i giocatori (fd %d, fd %d) per partita %d in PAREGGIO\n", player_fd, opponent_fd_if_game_over, current_game_id);
                send_to_client(player_fd, CMD_REMATCH_OFFER);
                if (opponent_fd_if_game_over >= 0)
                    send_to_client(opponent_fd_if_game_over, CMD_REMATCH_OFFER);
            }
        }
    }
    else
    {
    move_error_exit:
        if (player_fd >= 0 && response[0] != '\0')
        {
            send_to_client(player_fd, response);
        }
    }
}

void process_rematch_command(int client_idx, const char *choice)
{
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS || !choice)
        return;

    int caller_fd = -1;
    int current_game_id = 0;
    int game_idx = -1;
    int p1_fd = -1, p2_fd = -1;
    int p1_idx = -1, p2_idx = -1;
    int opponent_fd = -1, opponent_idx = -1;
    char caller_name[MAX_NAME_LEN];
    char response_caller[BUFFER_SIZE] = {0};
    char notify_opponent_on_fail[BUFFER_SIZE] = {0};
    char error_response[BUFFER_SIZE] = {0};
    char notify_p1_start[BUFFER_SIZE] = {0};
    char notify_p2_start[BUFFER_SIZE] = {0};
    bool send_direct_response_to_caller = false;
    bool send_fail_response_to_caller_due_to_opponent_no = false;
    bool restart_draw_game = false;
    int player_to_start_draw_rematch = -1;

    pthread_mutex_lock(&client_list_mutex);
    pthread_mutex_lock(&game_list_mutex);

    if (!clients[client_idx].active)
    {
        goto rematch_cleanup_nolock;
    }
    caller_fd = clients[client_idx].fd;
    current_game_id = clients[client_idx].game_id;
    strncpy(caller_name, clients[client_idx].name, MAX_NAME_LEN);
    caller_name[MAX_NAME_LEN - 1] = '\0';
    if (current_game_id <= 0)
    {
        snprintf(error_response, sizeof(error_response), "%s\n", ERR_GENERIC);
        goto rematch_cleanup;
    }
    game_idx = find_game_index_unsafe(current_game_id);
    if (game_idx == -1)
    {
        snprintf(error_response, sizeof(error_response), "%s %d\n", ERR_GAME_NOT_FOUND, current_game_id);
        goto rematch_cleanup;
    }
    GameInfo *game = &games[game_idx];
    if (clients[client_idx].state != CLIENT_STATE_PLAYING || game->state != GAME_STATE_FINISHED)
    {
        snprintf(error_response, sizeof(error_response), "%s\n", ERR_NOT_IN_FINISHED_OR_DRAW_GAME);
        goto rematch_cleanup;
    }
    p1_fd = game->player1_fd;
    p2_fd = game->player2_fd;
    p1_idx = find_client_index_unsafe(p1_fd);
    p2_idx = find_client_index_unsafe(p2_fd);
    bool is_caller_p1 = (caller_fd == p1_fd);
    bool is_caller_p2 = (caller_fd == p2_fd);
    bool is_draw = (game->winner_fd == -1);
    bool is_caller_winner = (!is_draw && game->winner_fd == caller_fd);
    if (!is_caller_p1 && !is_caller_p2)
    {
        snprintf(error_response, sizeof(error_response), "%s\n", ERR_DRAW_REMATCH_ONLY_PLAYER);
        goto rematch_cleanup;
    }
    if (!is_draw && !is_caller_winner)
    {
        snprintf(error_response, sizeof(error_response), "%s\n", ERR_NOT_THE_WINNER);
        clients[client_idx].state = CLIENT_STATE_LOBBY;
        clients[client_idx].game_id = 0;
        goto rematch_cleanup;
    }
    opponent_fd = find_opponent_fd(game, caller_fd);
    opponent_idx = find_client_index_unsafe(opponent_fd);

    if (strcmp(choice, CMD_REMATCH_YES) == 0)
    {
        if (is_draw)
        {
            LOG("Rematch PAREGGIO YES da %s (fd %d) per partita %d\n", caller_name, caller_fd, game->id);
            RematchChoice opponent_choice;
            if (is_caller_p1)
            {
                game->player1_accepted_rematch = REMATCH_CHOICE_YES;
                opponent_choice = game->player2_accepted_rematch;
            }
            else
            {
                game->player2_accepted_rematch = REMATCH_CHOICE_YES;
                opponent_choice = game->player1_accepted_rematch;
            }
            if (opponent_choice == REMATCH_CHOICE_YES)
            {
                LOG("Rematch PAREGGIO ACCETTATO da entrambi nella partita %d! Riavvio partita.\n", game->id);
                game->state = GAME_STATE_IN_PROGRESS;
                init_board(game->board);

                game->current_turn_fd = game->player1_fd >= 0 ? game->player1_fd : game->player2_fd;
                if (game->current_turn_fd < 0)
                {
                    LOG("ERRORE: Entrambi i giocatori hanno accettato rematch in PAREGGIO ma non è possibile determinare il giocatore iniziale (P1_FD=%d, P2_FD=%d). Annullamento rematch.\n", game->player1_fd, game->player2_fd);

                    if (p1_idx != -1 && clients[p1_idx].active)
                    {
                        clients[p1_idx].state = CLIENT_STATE_LOBBY;
                        clients[p1_idx].game_id = 0;
                    }
                    if (p2_idx != -1 && clients[p2_idx].active)
                    {
                        clients[p2_idx].state = CLIENT_STATE_LOBBY;
                        clients[p2_idx].game_id = 0;
                    }
                }
                else
                {
                    player_to_start_draw_rematch = game->current_turn_fd;
                    LOG("Partita %d rematch PAREGGIO riavviata. Giocatore FD %d inizia.\n", game->id, player_to_start_draw_rematch);
                    game->winner_fd = -1;
                    game->player1_accepted_rematch = REMATCH_CHOICE_PENDING;
                    game->player2_accepted_rematch = REMATCH_CHOICE_PENDING;

                    if (p1_idx != -1 && clients[p1_idx].active)
                        clients[p1_idx].state = CLIENT_STATE_PLAYING;
                    if (p2_idx != -1 && clients[p2_idx].active)
                        clients[p2_idx].state = CLIENT_STATE_PLAYING;
                    snprintf(notify_p1_start, sizeof(notify_p1_start), NOTIFY_GAME_START_FMT, game->id, 'X', (game->player2_name[0] ? game->player2_name : "?"));
                    snprintf(notify_p2_start, sizeof(notify_p2_start), NOTIFY_GAME_START_FMT, game->id, 'O', (game->player1_name[0] ? game->player1_name : "?"));
                    restart_draw_game = true;
                }
            }
            else if (opponent_choice == REMATCH_CHOICE_NO)
            {
                LOG("Rematch PAREGGIO fallito per partita %d: %s ha detto YES, ma l'avversario ha già rifiutato.\n", game->id, caller_name);
                clients[client_idx].state = CLIENT_STATE_LOBBY;
                clients[client_idx].game_id = 0;
                snprintf(response_caller, sizeof(response_caller), "%s", RESP_REMATCH_DECLINED);
                send_fail_response_to_caller_due_to_opponent_no = true;
            }
            else
            {
                LOG("Rematch PAREGGIO: %s ha detto YES per partita %d. In attesa della scelta dell'avversario.\n", caller_name, game->id);
            }
        }
        else
        {
            LOG("Rematch VINCITORE YES da %s (fd %d) per partita %d\n", caller_name, caller_fd, game->id);
            game->state = GAME_STATE_WAITING;
            game->player1_fd = caller_fd;
            strncpy(game->player1_name, caller_name, MAX_NAME_LEN - 1);
            game->player1_name[MAX_NAME_LEN - 1] = '\0';
            LOG("Impostato nuovo proprietario partita %d a '%s' (fd %d)\n", game->id, game->player1_name, game->player1_fd);
            game->player2_fd = -1;
            game->player2_name[0] = '\0';
            init_board(game->board);
            game->current_turn_fd = -1;
            game->winner_fd = -1;
            game->player1_accepted_rematch = REMATCH_CHOICE_PENDING;
            game->player2_accepted_rematch = REMATCH_CHOICE_PENDING;
            clients[client_idx].state = CLIENT_STATE_WAITING;
            if (opponent_idx != -1 && clients[opponent_idx].active && clients[opponent_idx].fd == opponent_fd)
            {
                clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                clients[opponent_idx].game_id = 0;
                snprintf(notify_opponent_on_fail, sizeof(notify_opponent_on_fail), "%s", NOTIFY_OPPONENT_ACCEPTED_REMATCH);
                LOG("Sposto perdente '%s' (fd %d) in LOBBY poiché il vincitore ha accettato rematch per partita %d.\n", clients[opponent_idx].name, opponent_fd, game->id);
            }
            else
            {
                LOG("Perdente (opponent_fd %d) già inattivo o non valido per partita %d quando il vincitore ha accettato rematch.\n", opponent_fd, game->id);
            }
            snprintf(response_caller, sizeof(response_caller), RESP_REMATCH_ACCEPTED_FMT, game->id);
            send_direct_response_to_caller = true;
        }
    }
    else if (strcmp(choice, CMD_REMATCH_NO) == 0)
    {
        LOG("Rematch NO da %s (fd %d) per partita %d\n", caller_name, caller_fd, game->id);
        clients[client_idx].state = CLIENT_STATE_LOBBY;
        clients[client_idx].game_id = 0;
        snprintf(response_caller, sizeof(response_caller), "%s", RESP_REMATCH_DECLINED);
        send_direct_response_to_caller = true;

        if (is_draw)
        {
            LOG("Gestione PAREGGIO NO da %s per partita %d\n", caller_name, game->id);
            RematchChoice opponent_choice;
            if (is_caller_p1)
            {
                game->player1_accepted_rematch = REMATCH_CHOICE_NO;
                opponent_choice = game->player2_accepted_rematch;
            }
            else
            {
                game->player2_accepted_rematch = REMATCH_CHOICE_NO;
                opponent_choice = game->player1_accepted_rematch;
            }
            if (opponent_choice == REMATCH_CHOICE_PENDING)
            {
                LOG("Partita %d: %s ha detto NO (pareggio). Avversario PENDING. Nessuna notifica all'avversario inviata ora.\n", game->id, caller_name);
            }
            else
            {
                LOG("Partita %d: %s ha detto NO (pareggio). L'avversario aveva già scelto (%d). Notifico l'avversario.\n", game->id, caller_name, opponent_choice);
                if (opponent_idx != -1 && clients[opponent_idx].active && clients[opponent_idx].fd == opponent_fd)
                {

                    clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                    clients[opponent_idx].game_id = 0;
                    snprintf(notify_opponent_on_fail, sizeof(notify_opponent_on_fail), "%s", NOTIFY_OPPONENT_DECLINED);
                    LOG("Sposto avversario '%s' (fd %d) in LOBBY (rematch-pareggio NO finale da %s).\n", clients[opponent_idx].name, opponent_fd, caller_name);
                }
            }
        }
        else
        {
            LOG("Gestione VINCITORE NO da %s per partita %d\n", caller_name, game->id);
            if (opponent_idx != -1 && clients[opponent_idx].active && clients[opponent_idx].fd == opponent_fd)
            {
                clients[opponent_idx].state = CLIENT_STATE_LOBBY;
                clients[opponent_idx].game_id = 0;
                snprintf(notify_opponent_on_fail, sizeof(notify_opponent_on_fail), "%s", NOTIFY_OPPONENT_DECLINED);
                LOG("Notifico al perdente '%s' (fd %d) che il vincitore ha rifiutato rematch per partita %d.\n", clients[opponent_idx].name, opponent_fd, game->id);
            }
            else
            {
                LOG("Perdente (opponent_fd %d) già inattivo o non valido quando il vincitore ha rifiutato rematch per partita %d.\n", opponent_fd, game->id);
            }
        }
    }
    else
    {
        snprintf(error_response, sizeof(error_response), "%s\n", ERR_INVALID_REMATCH_CHOICE);
    }

rematch_cleanup:
    pthread_mutex_unlock(&game_list_mutex);
    pthread_mutex_unlock(&client_list_mutex);

    if (restart_draw_game)
    {
        if (p1_fd >= 0 && notify_p1_start[0])
            send_to_client(p1_fd, notify_p1_start);
        if (p2_fd >= 0 && notify_p2_start[0])
            send_to_client(p2_fd, notify_p2_start);

        if (game_idx != -1 && player_to_start_draw_rematch >= 0)
        {
            pthread_mutex_lock(&game_list_mutex);
            if (find_game_index_unsafe(current_game_id) == game_idx && games[game_idx].state == GAME_STATE_IN_PROGRESS)
            {

                broadcast_game_state(game_idx);
            }
            else
            {
                LOG("ATTENZIONE: Stato partita %d cambiato prima del broadcast dello stato dopo l'inizio del rematch (pareggio).\n", current_game_id);
            }
            pthread_mutex_unlock(&game_list_mutex);
        }
        else
        {
            LOG("ATTENZIONE: Impossibile fare broadcast stato/turno per rematch partita %d (pareggio): game_idx (%d) o FD giocatore iniziale (%d) non validi\n",
                current_game_id, game_idx, player_to_start_draw_rematch);
        }
    }
    else if (send_fail_response_to_caller_due_to_opponent_no)
    {
        if (caller_fd >= 0 && response_caller[0])
            send_to_client(caller_fd, response_caller);
    }
    else if (send_direct_response_to_caller)
    {
        if (caller_fd >= 0 && response_caller[0])
            send_to_client(caller_fd, response_caller);
        if (opponent_fd >= 0 && notify_opponent_on_fail[0])
            send_to_client(opponent_fd, notify_opponent_on_fail);
    }
    else if (error_response[0])
    {
        if (caller_fd >= 0)
            send_to_client(caller_fd, error_response);
    }

rematch_cleanup_nolock:
    return;
}

bool process_quit_command(int client_idx)
{
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS)
        return false;
    int client_fd = -1;
    char client_name[MAX_NAME_LEN];
    ClientState current_state;
    int current_game_id;
    bool request_disconnect = false;
    pthread_mutex_lock(&client_list_mutex);
    if (!clients[client_idx].active)
    {
        pthread_mutex_unlock(&client_list_mutex);
        return true;
    }
    client_fd = clients[client_idx].fd;
    strncpy(client_name, clients[client_idx].name, MAX_NAME_LEN - 1);
    client_name[MAX_NAME_LEN - 1] = '\0';
    current_state = clients[client_idx].state;
    current_game_id = clients[client_idx].game_id;
    pthread_mutex_unlock(&client_list_mutex);

    LOG("Client %s ha inviato QUIT. Stato: %d, ID Partita: %d\n", client_name, current_state, current_game_id);

    if (current_state == CLIENT_STATE_PLAYING || current_state == CLIENT_STATE_WAITING)
    {
        LOG("Client %s sta lasciando il contesto della partita %d tramite QUIT.\n", client_name, current_game_id);
        pthread_mutex_lock(&client_list_mutex);
        pthread_mutex_lock(&game_list_mutex);
        int game_idx = find_game_index_unsafe(current_game_id);
        if (game_idx != -1)
        {
            handle_player_leaving_game(game_idx, client_fd, client_name);
        }
        else
        {
            LOG("QUIT: Partita %d non trovata per il client %s.\n", current_game_id, client_name);
        }
        if (clients[client_idx].active)
        {
            clients[client_idx].state = CLIENT_STATE_LOBBY;
            clients[client_idx].game_id = 0;
            LOG("Client %s spostato in LOBBY dopo QUIT.\n", client_name);
        }
        pthread_mutex_unlock(&game_list_mutex);
        pthread_mutex_unlock(&client_list_mutex);
        send_to_client(client_fd, RESP_QUIT_OK);
        request_disconnect = false;
    }
    else
    {
        LOG("Client %s ha inviato QUIT dallo stato %d. Interpreto come disconnessione.\n", client_name, current_state);
        request_disconnect = true;
    }
    return request_disconnect;
}

void send_unknown_command_error(int client_idx, const char *received_command, ClientState current_state)
{
    if (client_idx < 0 || client_idx >= MAX_TOTAL_CLIENTS)
        return;
    int client_fd = -1;
    pthread_mutex_lock(&client_list_mutex);
    if (clients[client_idx].active)
        client_fd = clients[client_idx].fd;
    pthread_mutex_unlock(&client_list_mutex);
    if (client_fd >= 0)
    {
        char error_resp[BUFFER_SIZE];
        snprintf(error_resp, sizeof(error_resp), ERR_UNKNOWN_COMMAND_FMT,
                 current_state, received_command ? received_command : "<empty>");
        strncat(error_resp, "\n", sizeof(error_resp) - strlen(error_resp) - 1);
        send_to_client(client_fd, error_resp);
    }
}