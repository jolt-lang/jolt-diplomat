#ifndef JsonKind_D_H
#define JsonKind_D_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"





typedef enum JsonKind {
  JsonKind_Null = 0,
  JsonKind_Bool = 1,
  JsonKind_Number = 2,
  JsonKind_String = 3,
  JsonKind_Array = 4,
  JsonKind_Object = 5,
} JsonKind;

typedef struct JsonKind_option {union { JsonKind ok; }; bool is_ok; } JsonKind_option;



#endif // JsonKind_D_H
