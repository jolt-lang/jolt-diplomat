#ifndef ParseError_H
#define ParseError_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "ParseError.d.h"






void sv_ParseError_message_mv1(const ParseError* self, DiplomatWrite* write);

void sv_ParseError_destroy_mv1(ParseError* self);





#endif // ParseError_H
