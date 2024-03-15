/*
 * Copyright (C) 2015 The Android Open Source Project
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

#pragma once

#include <AutoBackendTextureRelease.h>
#include <DisplayList.h>
#include <Matrix.h>
#include <Properties.h>
#include <Rect.h>
#include <RenderNode.h>
#include <hwui/Bitmap.h>
#include <pipeline/skia/SkiaRecordingCanvas.h>
#include <private/hwui/DrawGlInfo.h>
#include <renderstate/RenderState.h>
#include <renderthread/RenderThread.h>

#include <SkBitmap.h>
#include <SkColor.h>
#include <SkFont.h>
#include <SkImageInfo.h>
#include <SkRefCnt.h>

#include <gtest/gtest.h>
#include <memory>
#include <unordered_map>

class SkCanvas;
class SkMatrix;
class SkPath;
struct SkRect;

namespace android {
namespace uirenderer {

#define EXPECT_MATRIX_APPROX_EQ(a, b) EXPECT_TRUE(TestUtils::matricesAreApproxEqual(a, b))

#define EXPECT_RECT_APPROX_EQ(a, b)                          \
    EXPECT_TRUE(MathUtils::areEqual((a).left, (b).left) &&   \
                MathUtils::areEqual((a).top, (b).top) &&     \
                MathUtils::areEqual((a).right, (b).right) && \
                MathUtils::areEqual((a).bottom, (b).bottom));

#define EXPECT_CLIP_RECT(expRect, clipStatePtr)                                      \
    EXPECT_NE(nullptr, (clipStatePtr)) << "Op is unclipped";                         \
    if ((clipStatePtr)->mode == ClipMode::Rectangle) {                               \
        EXPECT_EQ((expRect), reinterpret_cast<const ClipRect*>(clipStatePtr)->rect); \
    } else {                                                                         \
        ADD_FAILURE() << "ClipState not a rect";                                     \
    }

#define INNER_PIPELINE_RENDERTHREAD_TEST(test_case_name, test_name)                                \
    TEST(test_case_name, test_name) {                                                              \
        TestUtils::runOnRenderThread(test_case_name##_##test_name##_RenderThreadTest::doTheThing); \
    }

/**
 * Like gtest's TEST, but runs on the RenderThread, and 'renderThread' is passed, in top level scope
 * (for e.g. accessing its RenderState)
 */
#define RENDERTHREAD_TEST(test_case_name, test_name)                                        \
    class test_case_name##_##test_name##_RenderThreadTest {                                 \
    public:                                                                                 \
        static void doTheThing(renderthread::RenderThread& renderThread);                   \
    };                                                                                      \
    INNER_PIPELINE_RENDERTHREAD_TEST(test_case_name, test_name);                            \
    /* Temporarily disabling Vulkan until we can figure out a way to stub out the driver */ \
    /* INNER_PIPELINE_RENDERTHREAD_TEST(test_case_name, test_name, SkiaVulkan); */          \
    void test_case_name##_##test_name##_RenderThreadTest::doTheThing(                       \
            renderthread::RenderThread& renderThread)

/**
 * Sets a property value temporarily, generally for the duration of a test, restoring the previous
 * value when going out of scope.
 *
 * Can be used e.g. to test behavior only active while Properties::debugOverdraw is enabled.
 */
template <typename T>
class ScopedProperty {
public:
    ScopedProperty(T& property, T newValue) : mPropertyPtr(&property), mOldValue(property) {
        property = newValue;
    }
    ~ScopedProperty() { *mPropertyPtr = mOldValue; }

private:
    T* mPropertyPtr;
    T mOldValue;
};

class TestUtils {
public:
    class SignalingDtor {
    public:
        SignalingDtor() : mSignal(nullptr) {}
        explicit SignalingDtor(int* signal) : mSignal(signal) {}
        void setSignal(int* signal) { mSignal = signal; }
        ~SignalingDtor() {
            if (mSignal) {
                (*mSignal)++;
            }
        }

    private:
        int* mSignal;
    };

    class MockTreeObserver : public TreeObserver {
    public:
        virtual void onMaybeRemovedFromTree(RenderNode* node) {}
    };

    static bool matricesAreApproxEqual(const Matrix4& a, const Matrix4& b) {
        for (int i = 0; i < 16; i++) {
            if (!MathUtils::areEqual(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }

    static sk_sp<Bitmap> createBitmap(int width, int height,
                                      SkColorType colorType = kN32_SkColorType) {
        SkImageInfo info = SkImageInfo::Make(width, height, colorType, kPremul_SkAlphaType);
        return Bitmap::allocateHeapBitmap(info);
    }

    static sk_sp<Bitmap> createBitmap(int width, int height, SkBitmap* outBitmap) {
        SkImageInfo info = SkImageInfo::Make(width, height, kN32_SkColorType, kPremul_SkAlphaType);
        outBitmap->setInfo(info);
        return Bitmap::allocateHeapBitmap(outBitmap);
    }

    static sp<DeferredLayerUpdater> createTextureLayerUpdater(
            renderthread::RenderThread& renderThread);

    static sp<DeferredLayerUpdater> createTextureLayerUpdater(
            renderthread::RenderThread& renderThread, uint32_t width, uint32_t height,
            const SkMatrix& transform);

    static sp<RenderNode> createNode(
            int left, int top, int right, int bottom,
            std::function<void(RenderProperties& props, Canvas& canvas)> setup) {
        sp<RenderNode> node = new RenderNode();
        RenderProperties& props = node->mutateStagingProperties();
        props.setLeftTopRightBottom(left, top, right, bottom);
        if (setup) {
            std::unique_ptr<Canvas> canvas(
                    Canvas::create_recording_canvas(props.getWidth(), props.getHeight()));
            setup(props, *canvas.get());
            canvas->finishRecording(node.get());
        }
        node->setPropertyFieldsDirty(0xFFFFFFFF);
        return node;
    }

    template <class RecordingCanvasType>
    static sp<RenderNode> createNode(
            int left, int top, int right, int bottom,
            std::function<void(RenderProperties& props, RecordingCanvasType& canvas)> setup) {
        sp<RenderNode> node = new RenderNode();
        RenderProperties& props = node->mutateStagingProperties();
        props.setLeftTopRightBottom(left, top, right, bottom);
        if (setup) {
            RecordingCanvasType canvas(props.getWidth(), props.getHeight());
            setup(props, canvas);
            node->setStagingDisplayList(canvas.finishRecording());
        }
        node->setPropertyFieldsDirty(0xFFFFFFFF);
        return node;
    }

    static void recordNode(RenderNode& node, std::function<void(Canvas&)> contentCallback) {
        std::unique_ptr<Canvas> canvas(Canvas::create_recording_canvas(
                node.stagingProperties().getWidth(), node.stagingProperties().getHeight(), &node));
        contentCallback(*canvas.get());
        canvas->finishRecording(&node);
    }

    static sp<RenderNode> createSkiaNode(
            int left, int top, int right, int bottom,
            std::function<void(RenderProperties& props, skiapipeline::SkiaRecordingCanvas& canvas)>
                    setup,
            const char* name = nullptr,
            std::unique_ptr<skiapipeline::SkiaDisplayList> displayList = nullptr) {
        sp<RenderNode> node = new RenderNode();
        if (name) {
            node->setName(name);
        }
        RenderProperties& props = node->mutateStagingProperties();
        props.setLeftTopRightBottom(left, top, right, bottom);
        if (displayList) {
            node->setStagingDisplayList(DisplayList(std::move(displayList)));
        }
        if (setup) {
            std::unique_ptr<skiapipeline::SkiaRecordingCanvas> canvas(
                    new skiapipeline::SkiaRecordingCanvas(nullptr, props.getWidth(),
                                                          props.getHeight()));
            setup(props, *canvas.get());
            canvas->finishRecording(node.get());
        }
        node->setPropertyFieldsDirty(0xFFFFFFFF);
        TestUtils::syncHierarchyPropertiesAndDisplayList(node);
        return node;
    }

    /**
     * Forces a sync of a tree of RenderNode, such that every descendant will have its staging
     * properties and DisplayList moved to the render copies.
     *
     * Note: does not check dirtiness bits, so any non-staging DisplayLists will be discarded.
     * For this reason, this should generally only be called once on a tree.
     */
    static void syncHierarchyPropertiesAndDisplayList(sp<RenderNode>& node) {
        syncHierarchyPropertiesAndDisplayListImpl(node.get());
    }

    static sp<RenderNode>& getSyncedNode(sp<RenderNode>& node) {
        syncHierarchyPropertiesAndDisplayList(node);
        return node;
    }

    typedef std::function<void(renderthread::RenderThread& thread)> RtCallback;

    class TestTask : public renderthread::RenderTask {
    public:
        explicit TestTask(RtCallback rtCallback) : rtCallback(rtCallback) {}
        virtual ~TestTask() {}
        virtual void run() override;
        RtCallback rtCallback;
    };

    /**
     * NOTE: requires surfaceflinger to run, otherwise this method will wait indefinitely.
     */
    static void runOnRenderThread(RtCallback rtCallback) {
        TestTask task(rtCallback);
        renderthread::RenderThread::getInstance().queue().runSync([&]() { task.run(); });
    }

    static void runOnRenderThreadUnmanaged(RtCallback rtCallback) {
        auto& rt = renderthread::RenderThread::getInstance();
        rt.queue().runSync([&]() { rtCallback(rt); });
    }


    static bool isRenderThreadRunning() { return renderthread::RenderThread::hasInstance(); }
    static pid_t getRenderThreadTid() { return renderthread::RenderThread::getInstance().getTid(); }

    static SkColor interpolateColor(float fraction, SkColor start, SkColor end);

    static void drawUtf8ToCanvas(Canvas* canvas, const char* text, const Paint& paint, float x,
                                 float y);

    static void drawUtf8ToCanvas(Canvas* canvas, const char* text, const Paint& paint,
                                 const SkPath& path);

    static std::unique_ptr<uint16_t[]> asciiToUtf16(const char* str);

    static SkColor getColor(const sk_sp<SkSurface>& surface, int x, int y);

    static SkRect getClipBounds(const SkCanvas* canvas);
    static SkRect getLocalClipBounds(const SkCanvas* canvas);

    static int getUsageCount(const AutoBackendTextureRelease* textureRelease) {
        EXPECT_NE(nullptr, textureRelease);
        return textureRelease->mUsageCount;
    }

    struct CallCounts {
        int sync = 0;
        int contextDestroyed = 0;
        int destroyed = 0;
        int removeOverlays = 0;
        int glesDraw = 0;
        int vkInitialize = 0;
        int vkDraw = 0;
        int vkPostDraw = 0;
    };

    static void expectOnRenderThread(const std::string_view& function = "unknown") {
        EXPECT_EQ(gettid(), TestUtils::getRenderThreadTid()) << "Called on wrong thread: " << function;
    }

    static int createMockFunctor() {
        const auto renderMode = WebViewFunctor_queryPlatformRenderMode();
        return WebViewFunctor_create(nullptr, createMockFunctorCallbacks(renderMode), renderMode);
    }

    static WebViewFunctorCallbacks createMockFunctorCallbacks(RenderMode mode) {
        auto callbacks = WebViewFunctorCallbacks{
                .onSync =
                        [](int functor, void* client_data, const WebViewSyncData& data) {
                            expectOnRenderThread("onSync");
                            sMockFunctorCounts[functor].sync++;
                        },
                .onContextDestroyed =
                        [](int functor, void* client_data) {
                            expectOnRenderThread("onContextDestroyed");
                            sMockFunctorCounts[functor].contextDestroyed++;
                        },
                .onDestroyed =
                        [](int functor, void* client_data) {
                            expectOnRenderThread("onDestroyed");
                            sMockFunctorCounts[functor].destroyed++;
                        },
                .removeOverlays =
                        [](int functor, void* data,
                           void (*mergeTransaction)(ASurfaceTransaction*)) {
                            expectOnRenderThread("removeOverlays");
                            sMockFunctorCounts[functor].removeOverlays++;
                        },
        };
        switch (mode) {
            case RenderMode::OpenGL_ES:
                callbacks.gles.draw = [](int functor, void* client_data, const DrawGlInfo& params,
                                         const WebViewOverlayData& overlay_params) {
                    expectOnRenderThread("draw");
                    sMockFunctorCounts[functor].glesDraw++;
                };
                break;
            case RenderMode::Vulkan:
                callbacks.vk.initialize = [](int functor, void* data,
                                             const VkFunctorInitParams& params) {
                    expectOnRenderThread("initialize");
                    sMockFunctorCounts[functor].vkInitialize++;
                };
                callbacks.vk.draw = [](int functor, void* data, const VkFunctorDrawParams& params,
                                       const WebViewOverlayData& overlayParams) {
                    expectOnRenderThread("draw");
                    sMockFunctorCounts[functor].vkDraw++;
                };
                callbacks.vk.postDraw = [](int functor, void* data) {
                    expectOnRenderThread("postDraw");
                    sMockFunctorCounts[functor].vkPostDraw++;
                };
                break;
        }
        return callbacks;
    }

    static CallCounts& countsForFunctor(int functor) { return sMockFunctorCounts[functor]; }

    static SkFont defaultFont();

private:
    static std::unordered_map<int, CallCounts> sMockFunctorCounts;

    static void syncHierarchyPropertiesAndDisplayListImpl(RenderNode* node) {
        MarkAndSweepRemoved observer(nullptr);
        node->syncProperties();
        if (node->mNeedsDisplayListSync) {
            node->mNeedsDisplayListSync = false;
            node->syncDisplayList(observer, nullptr);
        }
        auto& displayList = node->getDisplayList();
        if (displayList) {
            displayList.updateChildren([](RenderNode* child) {
                syncHierarchyPropertiesAndDisplayListImpl(child);
            });
        }
    }

};  // class TestUtils

} /* namespace uirenderer */
} /* namespace android */
