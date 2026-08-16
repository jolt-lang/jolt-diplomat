#ifndef Thingy_H
#define Thingy_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "Doubled.d.h"
#include "ThingyError.d.h"
#include "ThingyOptions.d.h"
#include "ThingyOptions2.d.h"
#include "ThingyOptions3.d.h"

#include "Thingy.d.h"





typedef struct DiplomatCallback_Thingy_apply_callback_f {
    const void* data;
    uint8_t (*run_callback)(const void*, uint8_t );
    void (*destructor)(const void*);
} DiplomatCallback_Thingy_apply_callback_f;

uint8_t Thingy_apply_callback(const Thingy* self, DiplomatCallback_Thingy_apply_callback_f f_cb_wrap);

typedef struct Thingy_try_create_result {union {Thingy* ok; ThingyError err;}; bool is_ok;} Thingy_try_create_result;
Thingy_try_create_result Thingy_try_create(DiplomatStringView s);

uint8_t Thingy_value(const Thingy* self);

void Thingy_describe(const Thingy* self, ThingyOptions opts, DiplomatWrite* write);

uint32_t Thingy_sum_with(const Thingy* self, DiplomatU8View others);

int64_t Thingy_sum_with_i32(const Thingy* self, DiplomatI32View others);

void Thingy_describe2(const Thingy* self, ThingyOptions2 opts, DiplomatWrite* write);

void Thingy_describe3(const Thingy* self, ThingyOptions3 opts, DiplomatWrite* write);

Doubled* Thingy_double(const Thingy* self);


void Thingy_destroy(Thingy* self);





#endif // Thingy_H
