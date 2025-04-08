#include "utils.h"

void get_timestamp(char *buffer, size_t len) {
    struct timeval tv;
    struct tm tm_info;
    gettimeofday(&tv, NULL);
    localtime_r(&tv.tv_sec, &tm_info); // Usa localtime_r per thread-safety
    strftime(buffer, len, "%H:%M:%S", &tm_info);
    int ms = tv.tv_usec / 1000;
    // Usa snprintf per sicurezza contro buffer overflow
    snprintf(buffer + strlen(buffer), len - strlen(buffer), ".%03d", ms);
}

bool send_to_client(int client_fd, const char* message) {
    if (client_fd < 0) { // Non tentare di inviare a fd non validi (-1, -2, ecc.)
        // LOG("Attempted to send to invalid fd %d. Message: %s\n", client_fd, message); // Log opzionale
        return true; // Consideralo "successo" nel senso che non c'è errore di invio
    }
    if (!message) {
        LOG("Attempted to send NULL message to fd %d\n", client_fd);
        return false;
    }

    ssize_t bytes_sent = send(client_fd, message, strlen(message), MSG_NOSIGNAL);

    if (bytes_sent < 0) {
        // EPIPE e ECONNRESET indicano che il client si è disconnesso. Non è un errore
        // critico del server, quindi ritorniamo true ma logghiamo.
        if (errno == EPIPE || errno == ECONNRESET) {
            LOG("Send failed to fd %d (client disconnected): %s\n", client_fd, strerror(errno));
            return true; // Il chiamante gestirà la disconnessione
        } else {
            // Altri errori sono più problematici
            LOG_PERROR("Send failed");
            fprintf(stderr, "    Client FD: %d, Errno: %d\n", client_fd, errno); // Maggiori dettagli
            return false; // Segnala un problema di invio
        }
    } else if (bytes_sent < strlen(message)) {
        // Invio parziale - raro con TCP blocking, ma possibile. Trattalo come errore qui.
        LOG("Partial send to fd %d (%zd / %zu bytes)\n", client_fd, bytes_sent, strlen(message));
        return false;
    }

    // LOG("Sent to fd %d: %s", client_fd, message); // Log opzionale per debug
    return true;
}