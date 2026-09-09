#ifndef AudioError_H
#define AudioError_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "AudioError.d.h"






void sdl3_AudioError_message_mv1(const AudioError* self, DiplomatWrite* write);

void sdl3_AudioError_destroy_mv1(AudioError* self);





#endif // AudioError_H
