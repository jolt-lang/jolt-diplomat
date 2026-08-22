#ifndef Version_H
#define Version_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "ParseError.d.h"

#include "Version.d.h"






typedef struct sv_Version_parse_mv1_result {union {Version* ok; ParseError* err;}; bool is_ok;} sv_Version_parse_mv1_result;
sv_Version_parse_mv1_result sv_Version_parse_mv1(DiplomatStringView text);

uint64_t sv_Version_major_mv1(const Version* self);

uint64_t sv_Version_minor_mv1(const Version* self);

uint64_t sv_Version_patch_mv1(const Version* self);

void sv_Version_to_string_mv1(const Version* self, DiplomatWrite* write);

bool sv_Version_is_prerelease_mv1(const Version* self);

typedef struct sv_Version_pre_mv1_result { bool is_ok;} sv_Version_pre_mv1_result;
sv_Version_pre_mv1_result sv_Version_pre_mv1(const Version* self, DiplomatWrite* write);

void sv_Version_destroy_mv1(Version* self);





#endif // Version_H
