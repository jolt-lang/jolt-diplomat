#ifndef AllocStats_H
#define AllocStats_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "AllocStats.d.h"






uint64_t AllocStats_bytes_allocated(void);

uint64_t AllocStats_bytes_deallocated(void);

void AllocStats_destroy(AllocStats* self);





#endif // AllocStats_H
