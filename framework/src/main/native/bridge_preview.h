#ifndef MAAEND_BRIDGE_PREVIEW_H
#define MAAEND_BRIDGE_PREVIEW_H

#include "bridge_internal.h"

#include <media/NdkImage.h>

void SetPreviewSurface(JNIEnv *env, jobject jSurface);
bool IsPreviewEnabled();
bool DispatchPreview(AImage *image);
void DrainPreviewQueue();

#endif
