#ifndef ThingyOptions3_D_H
#define ThingyOptions3_D_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "Point.d.h"




typedef struct ThingyOptions3 {
  Point point;
  double scale;
} ThingyOptions3;

typedef struct ThingyOptions3_option {union { ThingyOptions3 ok; }; bool is_ok; } ThingyOptions3_option;



#endif // ThingyOptions3_D_H
