#ifndef JsonError_H
#define JsonError_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "JsonError.d.h"






void json_JsonError_message_mv1(const JsonError* self, DiplomatWrite* write);

void json_JsonError_destroy_mv1(JsonError* self);





#endif // JsonError_H
