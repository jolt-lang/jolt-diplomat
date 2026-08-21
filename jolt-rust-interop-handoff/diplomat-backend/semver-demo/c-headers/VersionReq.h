#ifndef VersionReq_H
#define VersionReq_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "ParseError.d.h"
#include "Version.d.h"

#include "VersionReq.d.h"






typedef struct sv_VersionReq_parse_mv1_result {union {VersionReq* ok; ParseError* err;}; bool is_ok;} sv_VersionReq_parse_mv1_result;
sv_VersionReq_parse_mv1_result sv_VersionReq_parse_mv1(DiplomatStringView text);

bool sv_VersionReq_matches_mv1(const VersionReq* self, const Version* version);

void sv_VersionReq_to_string_mv1(const VersionReq* self, DiplomatWrite* write);

void sv_VersionReq_destroy_mv1(VersionReq* self);





#endif // VersionReq_H
