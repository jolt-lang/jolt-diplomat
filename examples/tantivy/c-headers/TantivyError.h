#ifndef TantivyError_H
#define TantivyError_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "TantivyError.d.h"






void tantivy_TantivyError_message_mv1(const TantivyError* self, DiplomatWrite* write);

void tantivy_TantivyError_destroy_mv1(TantivyError* self);





#endif // TantivyError_H
