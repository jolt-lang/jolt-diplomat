#ifndef ThingyOptions2_D_H
#define ThingyOptions2_D_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "Mode.d.h"




typedef struct ThingyOptions2 {
  Mode mode;
  double scale;
} ThingyOptions2;

typedef struct ThingyOptions2_option {union { ThingyOptions2 ok; }; bool is_ok; } ThingyOptions2_option;



#endif // ThingyOptions2_D_H
