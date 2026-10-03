/* SPDX-License-Identifier: GPL-3.0-or-later */
#pragma once
#include <stdbool.h>
#include <string.h>

static bool is_gpu_blocklisted(const char *desc)
{
    if (!desc) return true;
    const char *adreno = strstr(desc, "Adreno");
    if (adreno) {
        /* Only the S24 Ultra's 750 is eligible for the explicit experimental
         * toggle. Keep older/unknown Adreno devices protected. */
        const char *model = adreno + strlen("Adreno");
        while (*model == ' ') model++;
        if (strncmp(model, "(TM)", 4) == 0) model += 4;
        while (*model == ' ') model++;
        return !(strncmp(model, "750", 3) == 0 &&
                 (model[3] == '\0' || model[3] == ' ' || model[3] == ')'));
    }
    return strstr(desc, "Xclipse") != NULL || strstr(desc, "PowerVR") != NULL;
}
