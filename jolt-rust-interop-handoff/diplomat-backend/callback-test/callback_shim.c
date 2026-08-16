#include "Thingy.h"
#include <string.h>

// Decompose the by-value DiplomatCallback struct into 3 scalars — same
// rule as every other struct-by-value crossing in this project.
uint8_t jolt_Thingy_apply_callback(
    const Thingy* self,
    const void* data,
    uint8_t (*run_callback)(const void*, uint8_t),
    void (*destructor)(const void*)
) {
    DiplomatCallback_Thingy_apply_callback_f cb = {
        .data = data, .run_callback = run_callback, .destructor = destructor
    };
    return Thingy_apply_callback(self, cb);
}

void jolt_Thingy_try_create(const char* s_data, size_t s_len, void* out) {
    Thingy_try_create_result r = Thingy_try_create((DiplomatStringView){ .data = s_data, .len = s_len });
    memcpy(out, &r, sizeof(r));
}
size_t jolt_sizeof_try_create_result(void) { return sizeof(Thingy_try_create_result); }
