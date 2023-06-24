/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "SkiaVulkanPipeline.h"

#include <GrDirectContext.h>
#include <GrTypes.h>
#include <SkSurface.h>
#include <SkTypes.h>
#include <cutils/properties.h>
#include <gui/TraceUtils.h>
#include <strings.h>
#include <vk/GrVkTypes.h>

#include "DeferredLayerUpdater.h"
#include "LightingInfo.h"
#include "Readback.h"
#include "ShaderCache.h"
#include "SkiaPipeline.h"
#include "SkiaProfileRenderer.h"
#include "VkInteropFunctorDrawable.h"
#include "renderstate/RenderState.h"
#include "renderthread/Frame.h"
#include "renderthread/IRenderPipeline.h"

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {
namespace skiapipeline {

SkiaVulkanPipeline::SkiaVulkanPipeline(renderthread::RenderThread& thread) : SkiaPipeline(thread) {
    thread.renderState().registerContextCallback(this);
}

SkiaVulkanPipeline::~SkiaVulkanPipeline() {
    mRenderThread.renderState().removeContextCallback(this);
}

VulkanManager& SkiaVulkanPipeline::vulkanManager() {
    return mRenderThread.vulkanManager();
}

MakeCurrentResult SkiaVulkanPipeline::makeCurrent() {
    // In case the surface was destroyed (e.g. a previous trimMemory call) we
    // need to recreate it here.
    if (!isSurfaceReady() && mNativeWindow) {
        setSurface(mNativeWindow.get(), SwapBehavior::kSwap_default);
    }
    return isContextReady() ? MakeCurrentResult::AlreadyCurrent : MakeCurrentResult::Failed;
}

Frame SkiaVulkanPipeline::getFrame() {
    LOG_ALWAYS_FATAL_IF(mVkSurface == nullptr, "getFrame() called on a context with no surface!");
    return vulkanManager().dequeueNextBuffer(mVkSurface);
}

IRenderPipeline::DrawResult SkiaVulkanPipeline::draw(
        const Frame& frame, const SkRect& screenDirty, const SkRect& dirty,
        const LightGeometry& lightGeometry, LayerUpdateQueue* layerUpdateQueue,
        const Rect& contentDrawBounds, bool opaque, const LightInfo& lightInfo,
        const std::vector<sp<RenderNode>>& renderNodes, FrameInfoVisualizer* profiler) {
    sk_sp<SkSurface> backBuffer = mVkSurface->getCurrentSkSurface();
    if (backBuffer.get() == nullptr) {
        return {false, -1};
    }

    // update the coordinates of the global light position based on surface rotation
    SkPoint lightCenter = mVkSurface->getCurrentPreTransform().mapXY(lightGeometry.center.x,
                                                                     lightGeometry.center.y);
    LightGeometry localGeometry = lightGeometry;
    localGeometry.center.x = lightCenter.fX;
    localGeometry.center.y = lightCenter.fY;

    LightingInfo::updateLighting(localGeometry, lightInfo);
    renderFrame(*layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds, backBuffer,
                mVkSurface->getCurrentPreTransform());

    // Draw visual debugging features
    if (CC_UNLIKELY(Properties::showDirtyRegions ||
                    ProfileType::None != Properties::getProfileType())) {
        SkCanvas* profileCanvas = backBuffer->getCanvas();
        SkiaProfileRenderer profileRenderer(profileCanvas);
        profiler->draw(profileRenderer);
    }

    nsecs_t submissionTime = IRenderPipeline::DrawResult::kUnknownTime;
    {
        ATRACE_NAME("flush commands");
        submissionTime = vulkanManager().finishFrame(backBuffer.get());
    }
    layerUpdateQueue->clear();

    // Log memory statistics
    if (CC_UNLIKELY(Properties::debugLevel != kDebugDisabled)) {
        dumpResourceCacheUsage();
    }

    return {true, submissionTime};
}

bool SkiaVulkanPipeline::swapBuffers(const Frame& frame, bool drew, const SkRect& screenDirty,
                                     FrameInfo* currentFrameInfo, bool* requireSwap) {
    *requireSwap = drew;

    // Even if we decided to cancel the frame, from the perspective of jank
    // metrics the frame was swapped at this point
    currentFrameInfo->markSwapBuffers();

    if (*requireSwap) {
        vulkanManager().swapBuffers(mVkSurface, screenDirty);
    }

    return *requireSwap;
}

DeferredLayerUpdater* SkiaVulkanPipeline::createTextureLayer() {
    mRenderThread.requireVkContext();

    return new DeferredLayerUpdater(mRenderThread.renderState());
}

void SkiaVulkanPipeline::onStop() {}

// We can safely ignore the swap behavior because VkManager will always operate
// in a mode equivalent to EGLManager::SwapBehavior::kBufferAge
bool SkiaVulkanPipeline::setSurface(ANativeWindow* surface, SwapBehavior /*swapBehavior*/) {
    mNativeWindow = surface;

    if (mVkSurface) {
        vulkanManager().destroySurface(mVkSurface);
        mVkSurface = nullptr;
    }

    if (surface) {
        mRenderThread.requireVkContext();
        mVkSurface =
                vulkanManager().createSurface(surface, mColorMode, mSurfaceColorSpace,
                                              mSurfaceColorType, mRenderThread.getGrContext(), 0);
    }

    return mVkSurface != nullptr;
}

bool SkiaVulkanPipeline::isSurfaceReady() {
    return CC_UNLIKELY(mVkSurface != nullptr);
}

bool SkiaVulkanPipeline::isContextReady() {
    return CC_LIKELY(vulkanManager().hasVkContext());
}

void SkiaVulkanPipeline::invokeFunctor(const RenderThread& thread, Functor* functor) {
    VkInteropFunctorDrawable::vkInvokeFunctor(functor);
}

sk_sp<Bitmap> SkiaVulkanPipeline::allocateHardwareBitmap(renderthread::RenderThread& renderThread,
                                                         SkBitmap& skBitmap) {
    LOG_ALWAYS_FATAL("Unimplemented");
    return nullptr;
}

void SkiaVulkanPipeline::onContextDestroyed() {
    if (mVkSurface) {
        vulkanManager().destroySurface(mVkSurface);
        mVkSurface = nullptr;
    }
}

const SkM44& SkiaVulkanPipeline::getPixelSnapMatrix() const {
    return mVkSurface->getPixelSnapMatrix();
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
