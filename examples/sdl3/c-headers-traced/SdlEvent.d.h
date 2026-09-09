#ifndef SdlEvent_D_H
#define SdlEvent_D_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"





typedef struct SdlEvent {
  uint8_t kind;
  int32_t key_code;
  uint8_t mouse_button;
  float mouse_x;
  float mouse_y;
} SdlEvent;

typedef struct SdlEvent_option {union { SdlEvent ok; }; bool is_ok; } SdlEvent_option;



#endif // SdlEvent_D_H
