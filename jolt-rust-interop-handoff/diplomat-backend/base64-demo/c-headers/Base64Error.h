#ifndef Base64Error_H
#define Base64Error_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "Base64Error.d.h"






void b64_Base64Error_message_mv1(const Base64Error* self, DiplomatWrite* write);

void b64_Base64Error_destroy_mv1(Base64Error* self);





#endif // Base64Error_H
