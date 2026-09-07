#ifndef TunesError_H
#define TunesError_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "TunesError.d.h"






void tunes_TunesError_message_mv1(const TunesError* self, DiplomatWrite* write);

void tunes_TunesError_destroy_mv1(TunesError* self);





#endif // TunesError_H
