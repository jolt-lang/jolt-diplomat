#ifndef AllocStats_H
#define AllocStats_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "AllocStats.d.h"






uint64_t tantivy_AllocStats_bytes_allocated_mv1(void);

uint64_t tantivy_AllocStats_bytes_deallocated_mv1(void);

void tantivy_AllocStats_destroy_mv1(AllocStats* self);





#endif // AllocStats_H
