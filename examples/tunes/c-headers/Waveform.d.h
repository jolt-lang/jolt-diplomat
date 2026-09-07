#ifndef Waveform_D_H
#define Waveform_D_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"





typedef enum Waveform {
  Waveform_Sine = 0,
  Waveform_Square = 1,
  Waveform_Sawtooth = 2,
  Waveform_Triangle = 3,
} Waveform;

typedef struct Waveform_option {union { Waveform ok; }; bool is_ok; } Waveform_option;



#endif // Waveform_D_H
