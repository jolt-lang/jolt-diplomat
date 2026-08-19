#ifndef Markdown_H
#define Markdown_H

#include <stdio.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include "diplomat_runtime.h"


#include "Markdown.d.h"






Markdown* md_Markdown_create_mv1(void);

void md_Markdown_to_html_mv1(DiplomatStringView input, DiplomatWrite* write);

void md_Markdown_destroy_mv1(Markdown* self);





#endif // Markdown_H
