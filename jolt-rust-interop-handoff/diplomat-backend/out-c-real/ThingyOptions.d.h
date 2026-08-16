#ifndef ThingyOptions_D_H
#define ThingyOptions_D_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"





typedef struct ThingyOptions {
  bool verbose;
  double scale;
} ThingyOptions;

typedef struct ThingyOptions_option {union { ThingyOptions ok; }; bool is_ok; } ThingyOptions_option;



#endif // ThingyOptions_D_H
