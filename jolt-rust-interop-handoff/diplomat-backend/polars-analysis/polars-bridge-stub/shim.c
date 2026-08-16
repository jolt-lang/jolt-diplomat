#include "StubDataFrame.h"
#include "StubError.h"
#include <string.h>

void jolt_StubDataFrame_try_from_csv(const char* data, size_t len, void* out) {
    StubDataFrame_try_from_csv_result r =
        StubDataFrame_try_from_csv((DiplomatStringView){ .data = data, .len = len });
    memcpy(out, &r, sizeof(r));
}
size_t jolt_sizeof_try_from_csv_result(void) { return sizeof(StubDataFrame_try_from_csv_result); }

double jolt_StubDataFrame_map_reduce_threaded(
    const StubDataFrame* self,
    const void* data,
    double (*run_callback)(const void*, double),
    void (*destructor)(const void*)
) {
    DiplomatCallback_StubDataFrame_map_reduce_threaded_f cb = {
        .data = data, .run_callback = run_callback, .destructor = destructor
    };
    return StubDataFrame_map_reduce_threaded(self, cb);
}
