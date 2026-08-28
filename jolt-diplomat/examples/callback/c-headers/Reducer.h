#ifndef Reducer_H
#define Reducer_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "Reducer.d.h"





typedef struct DiplomatCallback_Reducer_reduce_f {
    const void* data;
    int32_t (*run_callback)(const void*, int32_t, int32_t );
    void (*destructor)(const void*);
} DiplomatCallback_Reducer_reduce_f;
typedef struct DiplomatCallback_Reducer_apply_twice_f {
    const void* data;
    int32_t (*run_callback)(const void*, int32_t );
    void (*destructor)(const void*);
} DiplomatCallback_Reducer_apply_twice_f;

int32_t Reducer_reduce(DiplomatI32View items, DiplomatCallback_Reducer_reduce_f f_cb_wrap);

int32_t Reducer_apply_twice(int32_t x, DiplomatCallback_Reducer_apply_twice_f f_cb_wrap);

void Reducer_destroy(Reducer* self);





#endif // Reducer_H
