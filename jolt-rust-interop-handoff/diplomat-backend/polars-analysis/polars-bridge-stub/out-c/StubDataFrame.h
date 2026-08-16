#ifndef StubDataFrame_H
#define StubDataFrame_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "StubError.d.h"

#include "StubDataFrame.d.h"





typedef struct DiplomatCallback_StubDataFrame_map_reduce_threaded_f {
    const void* data;
    double (*run_callback)(const void*, double );
    void (*destructor)(const void*);
} DiplomatCallback_StubDataFrame_map_reduce_threaded_f;

typedef struct StubDataFrame_try_from_csv_result {union {StubDataFrame* ok; StubError err;}; bool is_ok;} StubDataFrame_try_from_csv_result;
StubDataFrame_try_from_csv_result StubDataFrame_try_from_csv(DiplomatStringView csv);

size_t StubDataFrame_row_count(const StubDataFrame* self);

double StubDataFrame_sum(const StubDataFrame* self);

double StubDataFrame_map_reduce_threaded(const StubDataFrame* self, DiplomatCallback_StubDataFrame_map_reduce_threaded_f f_cb_wrap);


void StubDataFrame_destroy(StubDataFrame* self);





#endif // StubDataFrame_H
