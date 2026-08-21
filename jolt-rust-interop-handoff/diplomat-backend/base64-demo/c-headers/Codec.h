#ifndef Codec_H
#define Codec_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "Base64Error.d.h"

#include "Codec.d.h"






Codec* b64_Codec_standard_mv1(void);

Codec* b64_Codec_url_safe_mv1(void);

void b64_Codec_encode_mv1(const Codec* self, DiplomatU8View input, DiplomatWrite* write);

typedef struct b64_Codec_decode_mv1_result {union { Base64Error* err;}; bool is_ok;} b64_Codec_decode_mv1_result;
b64_Codec_decode_mv1_result b64_Codec_decode_mv1(const Codec* self, DiplomatStringView input, DiplomatWrite* write);

void b64_Codec_destroy_mv1(Codec* self);





#endif // Codec_H
