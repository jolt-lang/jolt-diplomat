#include <pthread.h>
#include <stdio.h>
#include <unistd.h>

typedef int (*cb_t)(int);

typedef struct { cb_t cb; int arg; int result; } thread_args_t;

static void* thread_fn(void* p) {
    thread_args_t* ta = (thread_args_t*)p;
    ta->result = ta->cb(ta->arg);
    return NULL;
}

// Invokes cb from a BRAND NEW pthread the Jolt runtime never activated —
// the exact scenario the docs' "single thread" warning is about, but for
// export!/jolt_library_init, not confirmed for foreign-callable specifically.
int invoke_from_new_thread(cb_t cb, int arg) {
    thread_args_t ta = { cb, arg, 0 };
    pthread_t t;
    pthread_create(&t, NULL, thread_fn, &ta);
    pthread_join(t, NULL);
    return ta.result;
}

// Same, but invokes cb SYNCHRONOUSLY on the calling thread (control case —
// should always work, matches the qsort test).
int invoke_same_thread(cb_t cb, int arg) {
    return cb(arg);
}
