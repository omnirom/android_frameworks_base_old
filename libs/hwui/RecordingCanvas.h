/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <SkCanvas.h>
#include <SkCanvasVirtualEnforcer.h>
#include <SkDrawable.h>
#include <SkGainmapInfo.h>
#include <SkNoDrawCanvas.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <SkRect.h>
#include <SkRuntimeEffect.h>
#include <log/log.h>

#include <cstdlib>
#include <utility>
#include <vector>

#include "CanvasTransform.h"
#include "Gainmap.h"
#include "hwui/Bitmap.h"
#include "pipeline/skia/AnimatedDrawables.h"
#include "utils/AutoMalloc.h"
#include "utils/Macros.h"
#include "utils/TypeLogic.h"

enum class SkBlendMode;
class SkRRect;
class Mesh;

namespace android {
namespace uirenderer {

namespace skiapipeline {
class FunctorDrawable;
}

namespace VectorDrawable {
class Tree;
}
typedef uirenderer::VectorDrawable::Tree VectorDrawableRoot;

enum class DisplayListOpType : uint8_t {
#define X(T) T,
#include "DisplayListOps.in"
#undef X
};

struct DisplayListOp {
    const uint8_t type : 8;
    const uint32_t skip : 24;
};

static_assert(sizeof(DisplayListOp) == 4);

class DrawMeshPayload {
public:
    explicit DrawMeshPayload(const SkMesh* mesh) : mesh(mesh) {}
    explicit DrawMeshPayload(const Mesh* meshWrapper) : meshWrapper(meshWrapper) {}

    [[nodiscard]] const SkMesh& getSkMesh() const;

private:
    const SkMesh* mesh = nullptr;
    const Mesh* meshWrapper = nullptr;
};

struct DrawImagePayload {
    explicit DrawImagePayload(Bitmap& bitmap)
            : image(bitmap.makeImage()), palette(bitmap.palette()) {
        if (bitmap.hasGainmap()) {
            auto gainmap = bitmap.gainmap();
            gainmapInfo = gainmap->info;
            gainmapImage = gainmap->bitmap->makeImage();
        }
    }

    explicit DrawImagePayload(const SkImage* image)
            : image(sk_ref_sp(image)), palette(BitmapPalette::Unknown) {}

    DrawImagePayload(const DrawImagePayload&) = default;
    DrawImagePayload(DrawImagePayload&&) = default;
    DrawImagePayload& operator=(const DrawImagePayload&) = default;
    DrawImagePayload& operator=(DrawImagePayload&&) = default;
    ~DrawImagePayload() = default;

    sk_sp<SkImage> image;
    BitmapPalette palette;

    sk_sp<SkImage> gainmapImage;
    SkGainmapInfo gainmapInfo;
};

class RecordingCanvas;

class DisplayListData final {
public:
    DisplayListData() : mHasText(false), mHasFill(false) {}
    ~DisplayListData();

    void draw(SkCanvas* canvas) const;

    void reset();
    bool empty() const { return fUsed == 0; }

    void applyColorTransform(ColorTransform transform);

    bool hasText() const { return mHasText; }
    bool hasFill() const { return mHasFill; }
    size_t usedSize() const { return fUsed; }
    size_t allocatedSize() const { return fReserved; }

private:
    friend class RecordingCanvas;

    void save();
    void saveLayer(const SkRect*, const SkPaint*, const SkImageFilter*, SkCanvas::SaveLayerFlags);
    void saveBehind(const SkRect*);
    void restore();

    void concat(const SkM44&);
    void setMatrix(const SkM44&);
    void scale(SkScalar, SkScalar);
    void translate(SkScalar, SkScalar);
    void translateZ(SkScalar);

    void clipPath(const SkPath&, SkClipOp, bool aa);
    void clipRect(const SkRect&, SkClipOp, bool aa);
    void clipRRect(const SkRRect&, SkClipOp, bool aa);
    void clipRegion(const SkRegion&, SkClipOp);
    void resetClip();

    void drawPaint(const SkPaint&);
    void drawBehind(const SkPaint&);
    void drawPath(const SkPath&, const SkPaint&);
    void drawRect(const SkRect&, const SkPaint&);
    void drawRegion(const SkRegion&, const SkPaint&);
    void drawOval(const SkRect&, const SkPaint&);
    void drawArc(const SkRect&, SkScalar, SkScalar, bool, const SkPaint&);
    void drawRRect(const SkRRect&, const SkPaint&);
    void drawDRRect(const SkRRect&, const SkRRect&, const SkPaint&);

    void drawMesh(const SkMesh&, const sk_sp<SkBlender>&, const SkPaint&);
    void drawMesh(const Mesh&, const sk_sp<SkBlender>&, const SkPaint&);

    void drawAnnotation(const SkRect&, const char*, SkData*);
    void drawDrawable(SkDrawable*, const SkMatrix*);
    void drawPicture(const SkPicture*, const SkMatrix*, const SkPaint*);

    void drawTextBlob(const SkTextBlob*, SkScalar, SkScalar, const SkPaint&);

    void drawImage(DrawImagePayload&&, SkScalar, SkScalar, const SkSamplingOptions&,
                   const SkPaint*);
    void drawImageRect(DrawImagePayload&&, const SkRect*, const SkRect&, const SkSamplingOptions&,
                       const SkPaint*, SkCanvas::SrcRectConstraint);
    void drawImageLattice(DrawImagePayload&&, const SkCanvas::Lattice&, const SkRect&, SkFilterMode,
                          const SkPaint*);

    void drawPatch(const SkPoint[12], const SkColor[4], const SkPoint[4], SkBlendMode,
                   const SkPaint&);
    void drawPoints(SkCanvas::PointMode, size_t, const SkPoint[], const SkPaint&);
    void drawVertices(const SkVertices*, SkBlendMode, const SkPaint&);
    void drawAtlas(const SkImage*, const SkRSXform[], const SkRect[], const SkColor[], int,
                   SkBlendMode, const SkSamplingOptions&, const SkRect*, const SkPaint*);
    void drawRippleDrawable(const skiapipeline::RippleDrawableParams& params);
    void drawShadowRec(const SkPath&, const SkDrawShadowRec&);
    void drawVectorDrawable(VectorDrawableRoot* tree);
    void drawWebView(skiapipeline::FunctorDrawable*);

    template <typename T, typename... Args>
    void* push(size_t, Args&&...);

    template <typename Fn, typename... Args>
    void map(const Fn[], Args...) const;

    AutoTMalloc<uint8_t> fBytes;
    size_t fUsed = 0;
    size_t fReserved = 0;

    bool mHasText : 1;
    bool mHasFill : 1;
};

class RecordingCanvas final : public SkCanvasVirtualEnforcer<SkNoDrawCanvas> {
public:
    RecordingCanvas();
    void reset(DisplayListData*, const SkIRect& bounds);

    sk_sp<SkSurface> onNewSurface(const SkImageInfo&, const SkSurfaceProps&) override;

    void willSave() override;
    SaveLayerStrategy getSaveLayerStrategy(const SaveLayerRec&) override;
    void willRestore() override;
    bool onDoSaveBehind(const SkRect*) override;

    void didConcat44(const SkM44&) override;
    void didSetM44(const SkM44&) override;
    void didScale(SkScalar, SkScalar) override;
    void didTranslate(SkScalar, SkScalar) override;

    void onClipRect(const SkRect&, SkClipOp, ClipEdgeStyle) override;
    void onClipRRect(const SkRRect&, SkClipOp, ClipEdgeStyle) override;
    void onClipPath(const SkPath&, SkClipOp, ClipEdgeStyle) override;
    void onClipRegion(const SkRegion&, SkClipOp) override;
    void onResetClip() override;

    void onDrawPaint(const SkPaint&) override;
    void onDrawBehind(const SkPaint&) override;
    void onDrawPath(const SkPath&, const SkPaint&) override;
    void onDrawRect(const SkRect&, const SkPaint&) override;
    void onDrawRegion(const SkRegion&, const SkPaint&) override;
    void onDrawOval(const SkRect&, const SkPaint&) override;
    void onDrawArc(const SkRect&, SkScalar, SkScalar, bool, const SkPaint&) override;
    void onDrawRRect(const SkRRect&, const SkPaint&) override;
    void onDrawDRRect(const SkRRect&, const SkRRect&, const SkPaint&) override;

    void onDrawDrawable(SkDrawable*, const SkMatrix*) override;
    void onDrawPicture(const SkPicture*, const SkMatrix*, const SkPaint*) override;
    void onDrawAnnotation(const SkRect&, const char[], SkData*) override;

    void onDrawTextBlob(const SkTextBlob*, SkScalar, SkScalar, const SkPaint&) override;

    void drawRippleDrawable(const skiapipeline::RippleDrawableParams& params);

    void drawImage(DrawImagePayload&&, SkScalar, SkScalar, const SkSamplingOptions&,
                   const SkPaint*);
    void drawImageRect(DrawImagePayload&&, const SkRect&, const SkRect&, const SkSamplingOptions&,
                       const SkPaint*, SrcRectConstraint);
    void drawImageLattice(DrawImagePayload&&, const Lattice& lattice, const SkRect&, SkFilterMode,
                          const SkPaint*);

    void onDrawImage2(const SkImage*, SkScalar, SkScalar, const SkSamplingOptions&,
                      const SkPaint*) override;
    void onDrawImageLattice2(const SkImage*, const Lattice&, const SkRect&, SkFilterMode,
                             const SkPaint*) override;
    void onDrawImageRect2(const SkImage*, const SkRect&, const SkRect&, const SkSamplingOptions&,
                          const SkPaint*, SrcRectConstraint) override;

    void onDrawPatch(const SkPoint[12], const SkColor[4], const SkPoint[4], SkBlendMode,
                     const SkPaint&) override;
    void onDrawPoints(PointMode, size_t count, const SkPoint pts[], const SkPaint&) override;
    void onDrawVerticesObject(const SkVertices*, SkBlendMode, const SkPaint&) override;
    void onDrawMesh(const SkMesh&, sk_sp<SkBlender>, const SkPaint&) override;
    void onDrawAtlas2(const SkImage*, const SkRSXform[], const SkRect[], const SkColor[], int,
                     SkBlendMode, const SkSamplingOptions&, const SkRect*, const SkPaint*) override;
    void onDrawShadowRec(const SkPath&, const SkDrawShadowRec&) override;

    void drawMesh(const Mesh& mesh, sk_sp<SkBlender> blender, const SkPaint& paint);
    void drawVectorDrawable(VectorDrawableRoot* tree);
    void drawWebView(skiapipeline::FunctorDrawable*);

    /**
     * If "isClipMayBeComplex" returns false, it is guaranteed the current clip is a rectangle.
     * If the return value is true, then clip may or may not be complex (there is no guarantee).
     */
    inline bool isClipMayBeComplex() { return mClipMayBeComplex; }

private:
    typedef SkCanvasVirtualEnforcer<SkNoDrawCanvas> INHERITED;

    inline void setClipMayBeComplex() {
        if (!mClipMayBeComplex) {
            mComplexSaveCount = mSaveCount;
            mClipMayBeComplex = true;
        }
    }

    DisplayListData* fDL;

    /**
     * mClipMayBeComplex tracks if the current clip is a rectangle. This flag is used to promote
     * FunctorDrawable to a layer, if it is clipped by a non-rect.
     */
    bool mClipMayBeComplex = false;

    /**
     * mSaveCount is the current level of our save tree.
     */
    int mSaveCount = 0;

    /**
     * mComplexSaveCount is the first save level, which has a complex clip. Every level below
     * mComplexSaveCount is assumed to have a complex clip and every level above mComplexSaveCount
     * is guaranteed to not be complex.
     */
    int mComplexSaveCount = 0;
};

}  // namespace uirenderer
}  // namespace android
