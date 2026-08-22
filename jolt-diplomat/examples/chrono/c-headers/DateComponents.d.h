#ifndef DateComponents_D_H
#define DateComponents_D_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"





typedef struct DateComponents {
  int32_t year;
  uint8_t month;
  uint8_t day;
  uint8_t hour;
  uint8_t minute;
  uint8_t second;
} DateComponents;

typedef struct DateComponents_option {union { DateComponents ok; }; bool is_ok; } DateComponents_option;



#endif // DateComponents_D_H
