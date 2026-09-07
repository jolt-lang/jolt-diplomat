#ifndef AudioStream_H
#define AudioStream_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "AudioError.d.h"

#include "AudioStream.d.h"






typedef struct sdl3_AudioStream_open_mv1_result {union {AudioStream* ok; AudioError* err;}; bool is_ok;} sdl3_AudioStream_open_mv1_result;
sdl3_AudioStream_open_mv1_result sdl3_AudioStream_open_mv1(int32_t sample_rate);

int32_t sdl3_AudioStream_queued_bytes_mv1(const AudioStream* self);

void sdl3_AudioStream_put_samples_mv1(const AudioStream* self, DiplomatF32View samples);

void sdl3_AudioStream_destroy_mv1(AudioStream* self);





#endif // AudioStream_H
