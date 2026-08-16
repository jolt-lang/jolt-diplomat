// Corrected shim: decomposes EVERY struct-by-value crossing (not just
// Result) into scalars/pointers, since real jolt.ffi rejects struct-by-value
// in BOTH parameter and return position ("unknown foreign type :struct" /
// ClassCastException on any composite type literal — verified against
// jolt v0.7.13 directly, not assumed).
#include "../out-c/Thingy.h"
#include "../out-c/ThingyError.h"
#include <string.h>

// Decomposed: (const char* data, size_t len) instead of DiplomatStringView by value.
// out: caller-allocated buffer sized to sizeof(Thingy_try_create_result); we write
// into it as raw bytes rather than returning the struct by value.
void jolt_Thingy_try_create(const char* data, size_t len, void* out) {
    DiplomatStringView s = { .data = data, .len = len };
    Thingy_try_create_result r = Thingy_try_create(s);
    memcpy(out, &r, sizeof(r));
}

// Decomposed: (bool verbose, double scale) instead of ThingyOptions by value.
void jolt_Thingy_describe(const Thingy* self, bool verbose, double scale, DiplomatWrite* write) {
    ThingyOptions opts = { .verbose = verbose, .scale = scale };
    Thingy_describe(self, opts, write);
}

// sizeof helper so the Jolt side can alloc exactly enough, without needing
// its own struct layout knowledge for Thingy_try_create_result.
size_t jolt_sizeof_try_create_result(void) {
    return sizeof(Thingy_try_create_result);
}

// diplomat_simple_write ALSO returns DiplomatWrite by value (56 bytes) —
// same problem as Thingy_try_create_result, same fix: decompose to an
// out-pointer. Naively declaring the C fn's return as :pointer from Jolt
// silently returned garbage rather than crashing (SysV ABI: >16-byte
// structs return via a hidden first pointer arg, so the "returned value"
// Jolt read was actually whatever register happened to hold something).
void jolt_diplomat_simple_write(char* buf, size_t buf_size, void* out) {
    DiplomatWrite w = diplomat_simple_write(buf, buf_size);
    memcpy(out, &w, sizeof(w));
}
