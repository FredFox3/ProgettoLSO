#ifndef UTILS_H
#define UTILS_H

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/time.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <stdbool.h>

void get_timestamp(char *buffer, size_t len);

#define LOG(...)                                     \
    do                                               \
    {                                                \
        char timestamp[30];                          \
        get_timestamp(timestamp, sizeof(timestamp)); \
        printf("%s - ", timestamp);                  \
        printf(__VA_ARGS__);                         \
        fflush(stdout);                              \
    } while (0)

#define LOG_PERROR(msg)                              \
    do                                               \
    {                                                \
        char timestamp[30];                          \
        get_timestamp(timestamp, sizeof(timestamp)); \
        fprintf(stderr, "%s - ERROR: ", timestamp);  \
        fflush(stderr);                              \
        perror(msg);                                 \
    } while (0)

bool send_to_client(int client_fd, const char *message);

#endif