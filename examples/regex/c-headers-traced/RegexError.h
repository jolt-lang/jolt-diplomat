#ifndef RegexError_H
#define RegexError_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "RegexError.d.h"






void rx_RegexError_message_mv1(const RegexError* self, DiplomatWrite* write);

void rx_RegexError_destroy_mv1(RegexError* self);





#endif // RegexError_H
