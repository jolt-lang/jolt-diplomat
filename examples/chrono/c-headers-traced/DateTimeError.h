#ifndef DateTimeError_H
#define DateTimeError_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "DateTimeError.d.h"






void chrono_DateTimeError_message_mv1(const DateTimeError* self, DiplomatWrite* write);

void chrono_DateTimeError_destroy_mv1(DateTimeError* self);





#endif // DateTimeError_H
