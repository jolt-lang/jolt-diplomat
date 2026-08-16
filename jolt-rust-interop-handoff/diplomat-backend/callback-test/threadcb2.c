#include <pthread.h>
#include <stdio.h>

typedef int (*cb_t)(int);

typedef struct { cb_t cb; int arg; int result; } thread_args_t;

static void* thread_fn(void* p) {
    thread_args_t* ta = (thread_args_t*)p;
    ta->result = ta->cb(ta->arg);
    return NULL;
}

// Spawns N concurrent threads, all invoking cb simultaneously, writes each
// result into results[]. Stress test for :collect-safe under real
// concurrent load, not just one thread at a time.
void invoke_concurrent(cb_t cb, int n, int* results) {
    pthread_t* threads = malloc(sizeof(pthread_t) * n);
    thread_args_t* args = malloc(sizeof(thread_args_t) * n);
    for (int i = 0; i < n; i++) {
        args[i].cb = cb;
        args[i].arg = i;
        args[i].result = 0;
        pthread_create(&threads[i], NULL, thread_fn, &args[i]);
    }
    for (int i = 0; i < n; i++) {
        pthread_join(threads[i], NULL);
        results[i] = args[i].result;
    }
    free(threads);
    free(args);
}
