#ifndef Point_D_H
#define Point_D_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"





typedef struct Point {
  double x;
  double y;
} Point;

typedef struct Point_option {union { Point ok; }; bool is_ok; } Point_option;



#endif // Point_D_H
