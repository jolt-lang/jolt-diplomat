#ifndef TunesMixer_H
#define TunesMixer_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "TunesError.d.h"
#include "Waveform.d.h"

#include "TunesMixer.d.h"






TunesMixer* tunes_TunesMixer_new_mv1(float bpm);

void tunes_TunesMixer_add_note_mv1(TunesMixer* self, float freq_hz, float start_time, float duration, Waveform waveform);

void tunes_TunesMixer_add_chord_mv1(TunesMixer* self, DiplomatF32View freqs, float start_time, float duration, Waveform waveform);

void tunes_TunesMixer_clear_mv1(TunesMixer* self);

void tunes_TunesMixer_disable_cache_mv1(TunesMixer* self);

float tunes_TunesMixer_total_duration_mv1(const TunesMixer* self);

size_t tunes_TunesMixer_render_buffer_size_mv1(TunesMixer* self, float sample_rate);

void tunes_TunesMixer_render_into_mv1(TunesMixer* self, DiplomatF32ViewMut buf, float sample_rate);

void tunes_TunesMixer_process_block_mv1(TunesMixer* self, DiplomatF32ViewMut buf, float sample_rate, float start_time);

typedef struct tunes_TunesMixer_export_wav_mv1_result {union { TunesError* err;}; bool is_ok;} tunes_TunesMixer_export_wav_mv1_result;
tunes_TunesMixer_export_wav_mv1_result tunes_TunesMixer_export_wav_mv1(TunesMixer* self, DiplomatStringView path, uint32_t sample_rate);

void tunes_TunesMixer_destroy_mv1(TunesMixer* self);





#endif // TunesMixer_H
