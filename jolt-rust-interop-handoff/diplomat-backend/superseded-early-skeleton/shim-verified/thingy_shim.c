// Generated shim (hand-written here as the template the jolt backend
// should emit automatically). Sidesteps the open "does defcfn support
// struct-by-value return" question by converting Thingy_try_create's
// by-value Result return into an out-pointer, which jolt.ffi's
// documented FFI surface handles unambiguously either way.
#include "../out-c/Thingy.h"
#include "../out-c/ThingyError.h"

void Thingy_try_create_shim(DiplomatStringView s, Thingy_try_create_result* out) {
    *out = Thingy_try_create(s);
}
