#ifndef StubError_D_H
#define StubError_D_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"





typedef enum StubError {
  StubError_ParseError = 0,
} StubError;

typedef struct StubError_option {union { StubError ok; }; bool is_ok; } StubError_option;



#endif // StubError_D_H
