#ifndef Hex_H
#define Hex_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "Base64Error.d.h"

#include "Hex.d.h"






Hex* b64_Hex_new_mv1(void);

void b64_Hex_encode_mv1(DiplomatU8View input, DiplomatWrite* write);

typedef struct b64_Hex_decode_mv1_result {union { Base64Error* err;}; bool is_ok;} b64_Hex_decode_mv1_result;
b64_Hex_decode_mv1_result b64_Hex_decode_mv1(DiplomatStringView input, DiplomatWrite* write);

void b64_Hex_destroy_mv1(Hex* self);





#endif // Hex_H
