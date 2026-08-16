#ifndef Doubled_H
#define Doubled_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "Doubled.d.h"






uint16_t Doubled_value(const Doubled* self);


void Doubled_destroy(Doubled* self);





#endif // Doubled_H
