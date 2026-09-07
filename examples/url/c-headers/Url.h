#ifndef Url_H
#define Url_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "UrlError.d.h"
#include "UrlInfo.d.h"

#include "Url.d.h"






typedef struct url_Url_parse_mv1_result {union {Url* ok; UrlError* err;}; bool is_ok;} url_Url_parse_mv1_result;
url_Url_parse_mv1_result url_Url_parse_mv1(DiplomatStringView input);

void url_Url_scheme_mv1(const Url* self, DiplomatWrite* write);

typedef struct url_Url_host_mv1_result { bool is_ok;} url_Url_host_mv1_result;
url_Url_host_mv1_result url_Url_host_mv1(const Url* self, DiplomatWrite* write);

void url_Url_path_mv1(const Url* self, DiplomatWrite* write);

typedef struct url_Url_query_mv1_result { bool is_ok;} url_Url_query_mv1_result;
url_Url_query_mv1_result url_Url_query_mv1(const Url* self, DiplomatWrite* write);

typedef struct url_Url_port_mv1_result {union {uint16_t ok; }; bool is_ok;} url_Url_port_mv1_result;
url_Url_port_mv1_result url_Url_port_mv1(const Url* self);

void url_Url_to_string_mv1(const Url* self, DiplomatWrite* write);

UrlInfo url_Url_info_mv1(const Url* self);

void url_Url_destroy_mv1(Url* self);





#endif // Url_H
