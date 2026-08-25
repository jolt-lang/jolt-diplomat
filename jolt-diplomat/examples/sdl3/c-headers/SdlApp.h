#ifndef SdlApp_H
#define SdlApp_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "SdlError.d.h"
#include "SdlEvent.d.h"

#include "SdlApp.d.h"






typedef struct sdl3_SdlApp_create_mv1_result {union {SdlApp* ok; SdlError* err;}; bool is_ok;} sdl3_SdlApp_create_mv1_result;
sdl3_SdlApp_create_mv1_result sdl3_SdlApp_create_mv1(DiplomatStringView title, uint32_t width, uint32_t height);

typedef struct sdl3_SdlApp_load_font_mv1_result {union { SdlError* err;}; bool is_ok;} sdl3_SdlApp_load_font_mv1_result;
sdl3_SdlApp_load_font_mv1_result sdl3_SdlApp_load_font_mv1(SdlApp* self, DiplomatStringView path, uint32_t pt_size);

void sdl3_SdlApp_draw_text_mv1(SdlApp* self, DiplomatStringView text, float x, float y, uint8_t r, uint8_t g, uint8_t b, uint8_t a);

SdlEvent sdl3_SdlApp_poll_event_mv1(SdlApp* self);

void sdl3_SdlApp_set_draw_color_mv1(SdlApp* self, uint8_t r, uint8_t g, uint8_t b, uint8_t a);

void sdl3_SdlApp_clear_mv1(SdlApp* self);

void sdl3_SdlApp_present_mv1(SdlApp* self);

void sdl3_SdlApp_fill_rect_mv1(SdlApp* self, float x, float y, float w, float h);

void sdl3_SdlApp_draw_rect_mv1(SdlApp* self, float x, float y, float w, float h);

void sdl3_SdlApp_draw_line_mv1(SdlApp* self, float x1, float y1, float x2, float y2);

typedef struct sdl3_SdlApp_set_title_mv1_result {union { SdlError* err;}; bool is_ok;} sdl3_SdlApp_set_title_mv1_result;
sdl3_SdlApp_set_title_mv1_result sdl3_SdlApp_set_title_mv1(SdlApp* self, DiplomatStringView title);

uint32_t sdl3_SdlApp_window_width_mv1(const SdlApp* self);

uint32_t sdl3_SdlApp_window_height_mv1(const SdlApp* self);

void sdl3_SdlApp_destroy_mv1(SdlApp* self);





#endif // SdlApp_H
