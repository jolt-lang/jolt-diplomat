#ifndef UrlError_H
#define UrlError_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "UrlError.d.h"






void url_UrlError_message_mv1(const UrlError* self, DiplomatWrite* write);

void url_UrlError_destroy_mv1(UrlError* self);





#endif // UrlError_H
