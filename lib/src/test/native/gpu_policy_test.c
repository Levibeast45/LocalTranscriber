/* SPDX-License-Identifier: GPL-3.0-or-later */
#include <assert.h>
#include "../../main/jni/gpu_policy.h"
int main(void) {
    assert(!is_gpu_blocklisted("Adreno (TM) 750"));
    assert(!is_gpu_blocklisted("Adreno 750"));
    assert(!is_gpu_blocklisted("Turnip Adreno (TM) 750"));
    assert(is_gpu_blocklisted("Adreno (TM) 730"));
    assert(is_gpu_blocklisted("Adreno 7500"));
    assert(is_gpu_blocklisted("Adreno 750unknown"));
    assert(is_gpu_blocklisted("Adreno"));
    assert(is_gpu_blocklisted("Adreno 740"));
    assert(is_gpu_blocklisted("Xclipse 920"));
    assert(is_gpu_blocklisted("PowerVR"));
    assert(is_gpu_blocklisted(NULL));
    assert(!is_gpu_blocklisted("Mali-G715"));
    return 0;
}
