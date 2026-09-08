#ifndef MarkdownOptions_D_H
#define MarkdownOptions_D_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"





typedef struct MarkdownOptions {
  bool tables;
  bool strikethrough;
  bool footnotes;
  bool tasklists;
} MarkdownOptions;

typedef struct MarkdownOptions_option {union { MarkdownOptions ok; }; bool is_ok; } MarkdownOptions_option;



#endif // MarkdownOptions_D_H
