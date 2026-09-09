#ifndef SearchIndex_H
#define SearchIndex_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "ResultSet.d.h"
#include "TantivyError.d.h"

#include "SearchIndex.d.h"






SearchIndex* tantivy_SearchIndex_new_in_ram_mv1(void);

typedef struct tantivy_SearchIndex_new_on_disk_mv1_result {union {SearchIndex* ok; TantivyError* err;}; bool is_ok;} tantivy_SearchIndex_new_on_disk_mv1_result;
tantivy_SearchIndex_new_on_disk_mv1_result tantivy_SearchIndex_new_on_disk_mv1(DiplomatStringView path);

typedef struct tantivy_SearchIndex_add_product_mv1_result {union { TantivyError* err;}; bool is_ok;} tantivy_SearchIndex_add_product_mv1_result;
tantivy_SearchIndex_add_product_mv1_result tantivy_SearchIndex_add_product_mv1(const SearchIndex* self, DiplomatStringView title, DiplomatStringView description, DiplomatStringView category, uint64_t price_cents);

typedef struct tantivy_SearchIndex_commit_mv1_result {union { TantivyError* err;}; bool is_ok;} tantivy_SearchIndex_commit_mv1_result;
tantivy_SearchIndex_commit_mv1_result tantivy_SearchIndex_commit_mv1(const SearchIndex* self);

uint64_t tantivy_SearchIndex_doc_count_mv1(const SearchIndex* self);

ResultSet* tantivy_SearchIndex_search_mv1(const SearchIndex* self, DiplomatStringView query, uint32_t limit);

void tantivy_SearchIndex_destroy_mv1(SearchIndex* self);





#endif // SearchIndex_H
