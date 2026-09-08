#ifndef ResultSet_H
#define ResultSet_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "ResultSet.d.h"






uint32_t tantivy_ResultSet_count_mv1(const ResultSet* self);

float tantivy_ResultSet_get_score_mv1(const ResultSet* self, uint32_t i);

void tantivy_ResultSet_get_title_mv1(const ResultSet* self, uint32_t i, DiplomatWrite* write);

void tantivy_ResultSet_get_description_mv1(const ResultSet* self, uint32_t i, DiplomatWrite* write);

void tantivy_ResultSet_get_category_mv1(const ResultSet* self, uint32_t i, DiplomatWrite* write);

uint64_t tantivy_ResultSet_get_price_cents_mv1(const ResultSet* self, uint32_t i);

void tantivy_ResultSet_get_snippet_mv1(const ResultSet* self, uint32_t i, DiplomatWrite* write);

void tantivy_ResultSet_destroy_mv1(ResultSet* self);





#endif // ResultSet_H
