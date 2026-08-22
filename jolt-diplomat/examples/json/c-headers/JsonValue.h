#ifndef JsonValue_H
#define JsonValue_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "JsonError.d.h"
#include "JsonKind.d.h"

#include "JsonValue.d.h"






typedef struct json_JsonValue_parse_mv1_result {union {JsonValue* ok; JsonError* err;}; bool is_ok;} json_JsonValue_parse_mv1_result;
json_JsonValue_parse_mv1_result json_JsonValue_parse_mv1(DiplomatStringView text);

JsonKind json_JsonValue_kind_mv1(const JsonValue* self);

typedef struct json_JsonValue_as_bool_mv1_result {union {bool ok; }; bool is_ok;} json_JsonValue_as_bool_mv1_result;
json_JsonValue_as_bool_mv1_result json_JsonValue_as_bool_mv1(const JsonValue* self);

typedef struct json_JsonValue_as_f64_mv1_result {union {double ok; }; bool is_ok;} json_JsonValue_as_f64_mv1_result;
json_JsonValue_as_f64_mv1_result json_JsonValue_as_f64_mv1(const JsonValue* self);

typedef struct json_JsonValue_as_str_mv1_result { bool is_ok;} json_JsonValue_as_str_mv1_result;
json_JsonValue_as_str_mv1_result json_JsonValue_as_str_mv1(const JsonValue* self, DiplomatWrite* write);

typedef struct json_JsonValue_array_len_mv1_result {union {uint64_t ok; }; bool is_ok;} json_JsonValue_array_len_mv1_result;
json_JsonValue_array_len_mv1_result json_JsonValue_array_len_mv1(const JsonValue* self);

JsonValue* json_JsonValue_array_get_mv1(const JsonValue* self, uint64_t index);

typedef struct json_JsonValue_object_get_mv1_result { bool is_ok;} json_JsonValue_object_get_mv1_result;
json_JsonValue_object_get_mv1_result json_JsonValue_object_get_mv1(const JsonValue* self, DiplomatStringView key, DiplomatWrite* write);

void json_JsonValue_to_string_mv1(const JsonValue* self, DiplomatWrite* write);

void json_JsonValue_destroy_mv1(JsonValue* self);





#endif // JsonValue_H
