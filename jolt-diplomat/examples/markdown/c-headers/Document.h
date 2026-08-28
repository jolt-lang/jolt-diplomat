#ifndef Document_H
#define Document_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"

#include "MarkdownOptions.d.h"

#include "Document.d.h"






Document* Document_parse(DiplomatStringView text, MarkdownOptions opts);

void Document_to_html(const Document* self, DiplomatWrite* write);

uint32_t Document_heading_count(const Document* self);

uint32_t Document_source_len(const Document* self);

void Document_destroy(Document* self);





#endif // Document_H
