#ifndef UrlInfo_D_H
#define UrlInfo_D_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"





typedef struct UrlInfo {
  uint16_t port;
  bool has_port;
  uint32_t path_len;
} UrlInfo;

typedef struct UrlInfo_option {union { UrlInfo ok; }; bool is_ok; } UrlInfo_option;



#endif // UrlInfo_D_H
