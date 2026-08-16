#ifndef ThingyError_D_H
#define ThingyError_D_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"





typedef enum ThingyError {
  ThingyError_ParseError = 0,
} ThingyError;

typedef struct ThingyError_option {union { ThingyError ok; }; bool is_ok; } ThingyError_option;



#endif // ThingyError_D_H
