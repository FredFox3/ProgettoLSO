#ifndef CLIENT_HANDLER_H
#define CLIENT_HANDLER_H

#include <pthread.h>

typedef void *(*PTHREAD_FUNC)(void *);

void *handle_client(void *arg);

#endif