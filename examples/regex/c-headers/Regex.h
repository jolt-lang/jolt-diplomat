#ifndef Regex_H
#define Regex_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "RegexError.d.h"

#include "Regex.d.h"






typedef struct rx_Regex_create_mv1_result {union {Regex* ok; RegexError* err;}; bool is_ok;} rx_Regex_create_mv1_result;
rx_Regex_create_mv1_result rx_Regex_create_mv1(DiplomatStringView pattern);

bool rx_Regex_is_match_mv1(const Regex* self, DiplomatStringView text);

typedef struct rx_Regex_find_mv1_result { bool is_ok;} rx_Regex_find_mv1_result;
rx_Regex_find_mv1_result rx_Regex_find_mv1(const Regex* self, DiplomatStringView text, DiplomatWrite* write);

void rx_Regex_replace_all_mv1(const Regex* self, DiplomatStringView text, DiplomatStringView replacement, DiplomatWrite* write);

void rx_Regex_destroy_mv1(Regex* self);





#endif // Regex_H
