#include <stdio.h>
#include <string.h>
#include "../out-c/Thingy.h"
#include "../out-c/ThingyError.h"

void Thingy_try_create_shim(DiplomatStringView s, Thingy_try_create_result* out);

int main() {
    // success path
    DiplomatStringView s1 = { .data = "42", .len = 2 };
    Thingy_try_create_result r1;
    Thingy_try_create_shim(s1, &r1);
    printf("success case: is_ok=%d\n", r1.is_ok);
    if (r1.is_ok) {
        printf("  value(): %u\n", Thingy_value(r1.ok));

        DiplomatU8View others = { .data = (uint8_t[]){1,2,3}, .len = 3 };
        printf("  sum_with([1,2,3]): %u\n", Thingy_sum_with(r1.ok, others));

        char buf[256];
        DiplomatWrite w = diplomat_simple_write(buf, sizeof(buf));
        ThingyOptions opts = { .verbose = true, .scale = 2.5 };
        Thingy_describe(r1.ok, opts, &w);
        printf("  describe(verbose): %.*s\n", (int)w.len, buf);

        Thingy_destroy(r1.ok);
    }

    // error path
    DiplomatStringView s2 = { .data = "not a number", .len = 12 };
    Thingy_try_create_result r2;
    Thingy_try_create_shim(s2, &r2);
    printf("error case: is_ok=%d err=%d\n", r2.is_ok, r2.is_ok ? -1 : r2.err);

    return 0;
}
