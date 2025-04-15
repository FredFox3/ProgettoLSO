#include "utils.h"

void get_timestamp(char *buffer, size_t len) {
    struct timeval tv;
    struct tm tm_info;
    gettimeofday(&tv, NULL);
    localtime_r(&tv.tv_sec, &tm_info);
    strftime(buffer, len, "%H:%M:%S", &tm_info);
    int ms = tv.tv_usec / 1000;

    snprintf(buffer + strlen(buffer), len - strlen(buffer), ".%03d", ms);
}

bool send_to_client(int client_fd, const char* message) {
    if (client_fd < 0) {
        return true;
    }
    if (!message) {
        LOG("Tentativo di invio messaggio NULL a fd %d\n", client_fd);
        return false;
    }

    ssize_t bytes_sent = send(client_fd, message, strlen(message), MSG_NOSIGNAL);

    if (bytes_sent < 0) {

        if (errno == EPIPE || errno == ECONNRESET) {
            LOG("Invio fallito a fd %d (client disconnesso): %s\n", client_fd, strerror(errno));
            return true;
        } else {

            LOG_PERROR("Invio fallito");
            fprintf(stderr, "    Client FD: %d, Errno: %d\n", client_fd, errno);
            return false;
        }
    } else if (bytes_sent < strlen(message)) {

        LOG("Invio parziale a fd %d (%zd / %zu byte)\n", client_fd, bytes_sent, strlen(message));
        return false;
    }


    return true;
}