#ifndef DateTime_H
#define DateTime_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "DateComponents.d.h"
#include "DateTimeError.d.h"

#include "DateTime.d.h"






DateTime* chrono_DateTime_now_mv1(void);

typedef struct chrono_DateTime_parse_mv1_result {union {DateTime* ok; DateTimeError* err;}; bool is_ok;} chrono_DateTime_parse_mv1_result;
chrono_DateTime_parse_mv1_result chrono_DateTime_parse_mv1(DiplomatStringView s);

DateTime* chrono_DateTime_from_timestamp_mv1(int64_t secs);

void chrono_DateTime_to_rfc3339_mv1(const DateTime* self, DiplomatWrite* write);

typedef struct chrono_DateTime_format_mv1_result { bool is_ok;} chrono_DateTime_format_mv1_result;
chrono_DateTime_format_mv1_result chrono_DateTime_format_mv1(const DateTime* self, DiplomatStringView fmt, DiplomatWrite* write);

int64_t chrono_DateTime_timestamp_secs_mv1(const DateTime* self);

DateComponents chrono_DateTime_components_mv1(const DateTime* self);

void chrono_DateTime_destroy_mv1(DateTime* self);





#endif // DateTime_H
