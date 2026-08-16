#ifndef Mode_D_H
#define Mode_D_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"





typedef enum Mode {
  Mode_Terse = 0,
  Mode_Verbose = 1,
  Mode_Debug = 2,
} Mode;

typedef struct Mode_option {union { Mode ok; }; bool is_ok; } Mode_option;



#endif // Mode_D_H
