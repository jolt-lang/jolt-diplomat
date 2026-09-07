#ifndef SdlError_H
#define SdlError_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "SdlError.d.h"






void sdl3_SdlError_message_mv1(const SdlError* self, DiplomatWrite* write);

void sdl3_SdlError_destroy_mv1(SdlError* self);





#endif // SdlError_H
