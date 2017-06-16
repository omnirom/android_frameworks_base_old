/*
 * Copyright (C) 2014 The Android Open Source Project
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
#include <GpuMemoryTracker.h>
#include "renderstate/RenderState.h"

#include "renderthread/CanvasContext.h"
#include "renderthread/EglManager.h"
#include "utils/GLUtils.h"
#include <algorithm>

namespace android {
namespace uirenderer {

RenderState::RenderState(renderthread::RenderThread& thread)
        : mRenderThread(thread)
        , mViewportWidth(0)
        , mViewportHeight(0)
        , mFramebuffer(0) {
    mThreadId = pthread_self();
}

RenderState::~RenderState() {
    LOG_ALWAYS_FATAL_IF(mBlend || mMeshState || mScissor || mStencil,
            "State object lifecycle not managed correctly");
}

void RenderState::onGLContextCreated() {
    LOG_ALWAYS_FATAL_IF(mBlend || mMeshState || mScissor || mStencil,
            "State object lifecycle not managed correctly");
    GpuMemoryTracker::onGLContextCreated();

    mBlend = new Blend();
    mMeshState = new MeshState();
    mScissor = new Scissor();
    mStencil = new Stencil();

    // This is delayed because the first access of Caches makes GL calls
    if (!mCaches) {
        mCaches = &Caches::createInstance(*this);
    }
    mCaches->init();
    mCaches->textureCache.setAssetAtlas(&mAssetAtlas);
}

static void layerLostGlContext(Layer* layer) {
    layer->onGlContextLost();
}

void RenderState::onGLContextDestroyed() {
/*
    size_t size = mActiveLayers.size();
    if (CC_UNLIKELY(size != 0)) {
        ALOGE("Crashing, have %d contexts and %d layers at context destruction. isempty %d",
                mRegisteredContexts.size(), size, mActiveLayers.empty());
        mCaches->dumpMemoryUsage();
        for (std::set<renderthread::CanvasContext*>::iterator cit = mRegisteredContexts.begin();
                cit != mRegisteredContexts.end(); cit++) {
            renderthread::CanvasContext* context = *cit;
            ALOGE("Context: %p (root = %p)", context, context->mRootRenderNode.get());
            ALOGE("  Prefeteched layers: %zu", context->mPrefetechedLayers.size());
            for (std::set<RenderNode*>::iterator pit = context->mPrefetechedLayers.begin();
                    pit != context->mPrefetechedLayers.end(); pit++) {
                (*pit)->debugDumpLayers("    ");
            }
            context->mRootRenderNode->debugDumpLayers("  ");
        }


        if (mActiveLayers.begin() == mActiveLayers.end()) {
            ALOGE("set has become empty. wat.");
        }
        for (std::set<const Layer*>::iterator lit = mActiveLayers.begin();
             lit != mActiveLayers.end(); lit++) {
            const Layer* layer = *(lit);
            ALOGE("Layer %p, state %d, texlayer %d, fbo %d, buildlayered %d",
                    layer, layer->state, layer->isTextureLayer(), layer->getFbo(), layer->wasBuildLayered);
        }
        LOG_ALWAYS_FATAL("%d layers have survived gl context destruction", size);
    }
*/

    mLayerPool.clear();

    // TODO: reset all cached state in state objects
    std::for_each(mActiveLayers.begin(), mActiveLayers.end(), layerLostGlContext);
    mAssetAtlas.terminate();

    mCaches->terminate();

    delete mBlend;
    mBlend = nullptr;
    delete mMeshState;
    mMeshState = nullptr;
    delete mScissor;
    mScissor = nullptr;
    delete mStencil;
    mStencil = nullptr;

    GpuMemoryTracker::onGLContextDestroyed();
}

void RenderState::flush(Caches::FlushMode mode) {
    switch (mode) {
        case Caches::FlushMode::Full:
            // fall through
        case Caches::FlushMode::Moderate:
            // fall through
        case Caches::FlushMode::Layers:
            mLayerPool.clear();
            break;
    }
    mCaches->flush(mode);
}

void RenderState::setViewport(GLsizei width, GLsizei height) {
    mViewportWidth = width;
    mViewportHeight = height;
    glViewport(0, 0, mViewportWidth, mViewportHeight);
}


void RenderState::getViewport(GLsizei* outWidth, GLsizei* outHeight) {
    *outWidth = mViewportWidth;
    *outHeight = mViewportHeight;
}

void RenderState::bindFramebuffer(GLuint fbo) {
    if (mFramebuffer != fbo) {
        mFramebuffer = fbo;
        glBindFramebuffer(GL_FRAMEBUFFER, mFramebuffer);
    }
}

GLuint RenderState::createFramebuffer() {
    GLuint ret;
    glGenFramebuffers(1, &ret);
    return ret;
}

void RenderState::deleteFramebuffer(GLuint fbo) {
    if (mFramebuffer == fbo) {
        // GL defines that deleting the currently bound FBO rebinds FBO 0.
        // Reflect this in our cached value.
        mFramebuffer = 0;
    }
    glDeleteFramebuffers(1, &fbo);
}

void RenderState::invokeFunctor(Functor* functor, DrawGlInfo::Mode mode, DrawGlInfo* info) {
    if (mode == DrawGlInfo::kModeProcessNoContext) {
        // If there's no context we don't need to interrupt as there's
        // no gl state to save/restore
        (*functor)(mode, info);
    } else {
        interruptForFunctorInvoke();
        (*functor)(mode, info);
        resumeFromFunctorInvoke();
    }
}

void RenderState::interruptForFunctorInvoke() {
    mCaches->setProgram(nullptr);
    mCaches->textureState().resetActiveTexture();
    meshState().unbindMeshBuffer();
    meshState().unbindIndicesBuffer();
    meshState().resetVertexPointers();
    meshState().disableTexCoordsVertexArray();
    debugOverdraw(false, false);
}

void RenderState::resumeFromFunctorInvoke() {
    glViewport(0, 0, mViewportWidth, mViewportHeight);
    glBindFramebuffer(GL_FRAMEBUFFER, mFramebuffer);
    debugOverdraw(false, false);

    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);

    scissor().invalidate();
    blend().invalidate();

    mCaches->textureState().activateTexture(0);
    mCaches->textureState().resetBoundTextures();
}

void RenderState::debugOverdraw(bool enable, bool clear) {
    if (Properties::debugOverdraw && mFramebuffer == 0) {
        if (clear) {
            scissor().setEnabled(false);
            stencil().clear();
        }
        if (enable) {
            stencil().enableDebugWrite();
        } else {
            stencil().disable();
        }
    }
}

class DecStrongTask : public renderthread::RenderTask {
public:
    DecStrongTask(VirtualLightRefBase* object) : mObject(object) {}

    virtual void run() override {
        mObject->decStrong(nullptr);
        mObject = nullptr;
        delete this;
    }

private:
    VirtualLightRefBase* mObject;
};

void RenderState::postDecStrong(VirtualLightRefBase* object) {
    if (pthread_equal(mThreadId, pthread_self())) {
        object->decStrong(nullptr);
    } else {
        mRenderThread.queue(new DecStrongTask(object));
    }
}

///////////////////////////////////////////////////////////////////////////////
// Render
///////////////////////////////////////////////////////////////////////////////

void RenderState::render(const Glop& glop, const Matrix4& orthoMatrix) {
    const Glop::Mesh& mesh = glop.mesh;
    const Glop::Mesh::Vertices& vertices = mesh.vertices;
    const Glop::Mesh::Indices& indices = mesh.indices;
    const Glop::Fill& fill = glop.fill;

    GL_CHECKPOINT(MODERATE);

    // ---------------------------------------------
    // ---------- Program + uniform setup ----------
    // ---------------------------------------------
    mCaches->setProgram(fill.program);

    if (fill.colorEnabled) {
        fill.program->setColor(fill.color);
    }

    fill.program->set(orthoMatrix,
            glop.transform.modelView,
            glop.transform.meshTransform(),
            glop.transform.transformFlags & TransformFlags::OffsetByFudgeFactor);

    // Color filter uniforms
    if (fill.filterMode == ProgramDescription::ColorFilterMode::Blend) {
        const FloatColor& color = fill.filter.color;
        glUniform4f(mCaches->program().getUniform("colorBlend"),
                color.r, color.g, color.b, color.a);
    } else if (fill.filterMode == ProgramDescription::ColorFilterMode::Matrix) {
        glUniformMatrix4fv(mCaches->program().getUniform("colorMatrix"), 1, GL_FALSE,
                fill.filter.matrix.matrix);
        glUniform4fv(mCaches->program().getUniform("colorMatrixVector"), 1,
                fill.filter.matrix.vector);
    }

    // Round rect clipping uniforms
    if (glop.roundRectClipState) {
        // TODO: avoid query, and cache values (or RRCS ptr) in program
        const RoundRectClipState* state = glop.roundRectClipState;
        const Rect& innerRect = state->innerRect;

        // add half pixel to round out integer rect space to cover pixel centers
        float roundedOutRadius = state->radius + 0.5f;

        // Divide by the radius to simplify the calculations in the fragment shader
        // roundRectPos is also passed from vertex shader relative to top/left & radius
        glUniform4f(fill.program->getUniform("roundRectInnerRectLTWH"),
                innerRect.left / roundedOutRadius, innerRect.top / roundedOutRadius,
                (innerRect.right - innerRect.left) / roundedOutRadius,
                (innerRect.bottom - innerRect.top) / roundedOutRadius);

        glUniformMatrix4fv(fill.program->getUniform("roundRectInvTransform"),
                1, GL_FALSE, &state->matrix.data[0]);

        glUniform1f(fill.program->getUniform("roundRectRadius"),
                roundedOutRadius);
    }

    GL_CHECKPOINT(MODERATE);

    // --------------------------------
    // ---------- Mesh setup ----------
    // --------------------------------
    // vertices
    meshState().bindMeshBuffer(vertices.bufferObject);
    meshState().bindPositionVertexPointer(vertices.position, vertices.stride);

    // indices
    meshState().bindIndicesBuffer(indices.bufferObject);

    // texture
    if (fill.texture.texture != nullptr) {
        const Glop::Fill::TextureData& texture = fill.texture;
        // texture always takes slot 0, shader samplers increment from there
        mCaches->textureState().activateTexture(0);

        mCaches->textureState().bindTexture(texture.target, texture.texture->id());
        if (texture.clamp != GL_INVALID_ENUM) {
            texture.texture->setWrap(texture.clamp, false, false, texture.target);
        }
        if (texture.filter != GL_INVALID_ENUM) {
            texture.texture->setFilter(texture.filter, false, false, texture.target);
        }

        if (texture.textureTransform) {
            glUniformMatrix4fv(fill.program->getUniform("mainTextureTransform"), 1,
                    GL_FALSE, &texture.textureTransform->data[0]);
        }
    }

    // vertex attributes (tex coord, color, alpha)
    if (vertices.attribFlags & VertexAttribFlags::TextureCoord) {
        meshState().enableTexCoordsVertexArray();
        meshState().bindTexCoordsVertexPointer(vertices.texCoord, vertices.stride);
    } else {
        meshState().disableTexCoordsVertexArray();
    }
    int colorLocation = -1;
    if (vertices.attribFlags & VertexAttribFlags::Color) {
        colorLocation = fill.program->getAttrib("colors");
        glEnableVertexAttribArray(colorLocation);
        glVertexAttribPointer(colorLocation, 4, GL_FLOAT, GL_FALSE, vertices.stride, vertices.color);
    }
    int alphaLocation = -1;
    if (vertices.attribFlags & VertexAttribFlags::Alpha) {
        // NOTE: alpha vertex position is computed assuming no VBO
        const void* alphaCoords = ((const GLbyte*) vertices.position) + kVertexAlphaOffset;
        alphaLocation = fill.program->getAttrib("vtxAlpha");
        glEnableVertexAttribArray(alphaLocation);
        glVertexAttribPointer(alphaLocation, 1, GL_FLOAT, GL_FALSE, vertices.stride, alphaCoords);
    }
    // Shader uniforms
    SkiaShader::apply(*mCaches, fill.skiaShaderData);

    GL_CHECKPOINT(MODERATE);
    Texture* texture = (fill.skiaShaderData.skiaShaderType & kBitmap_SkiaShaderType) ?
            fill.skiaShaderData.bitmapData.bitmapTexture : nullptr;
    const AutoTexture autoCleanup(texture);

    // ------------------------------------
    // ---------- GL state setup ----------
    // ------------------------------------
    blend().setFactors(glop.blend.src, glop.blend.dst);

    GL_CHECKPOINT(MODERATE);

    // ------------------------------------
    // ---------- Actual drawing ----------
    // ------------------------------------
    if (indices.bufferObject == meshState().getQuadListIBO()) {
        // Since the indexed quad list is of limited length, we loop over
        // the glDrawXXX method while updating the vertex pointer
        GLsizei elementsCount = mesh.elementCount;
        const GLbyte* vertexData = static_cast<const GLbyte*>(vertices.position);
        while (elementsCount > 0) {
            GLsizei drawCount = std::min(elementsCount, (GLsizei) kMaxNumberOfQuads * 6);
            GLsizei vertexCount = (drawCount / 6) * 4;
            meshState().bindPositionVertexPointer(vertexData, vertices.stride);
            if (vertices.attribFlags & VertexAttribFlags::TextureCoord) {
                meshState().bindTexCoordsVertexPointer(
                        vertexData + kMeshTextureOffset, vertices.stride);
            }

            if (mCaches->extensions().getMajorGlVersion() >= 3) {
                glDrawRangeElements(mesh.primitiveMode, 0, vertexCount-1, drawCount, GL_UNSIGNED_SHORT, nullptr);
            } else {
                glDrawElements(mesh.primitiveMode, drawCount, GL_UNSIGNED_SHORT, nullptr);
            }
            elementsCount -= drawCount;
            vertexData += vertexCount * vertices.stride;
        }
    } else if (indices.bufferObject || indices.indices) {
        if (mCaches->extensions().getMajorGlVersion() >= 3) {
            // use glDrawRangeElements to reduce CPU overhead (otherwise the driver has to determine the min/max index values)
            glDrawRangeElements(mesh.primitiveMode, 0, mesh.vertexCount-1, mesh.elementCount, GL_UNSIGNED_SHORT, indices.indices);
        } else {
            glDrawElements(mesh.primitiveMode, mesh.elementCount, GL_UNSIGNED_SHORT, indices.indices);
        }
    } else {
        glDrawArrays(mesh.primitiveMode, 0, mesh.elementCount);
    }

    GL_CHECKPOINT(MODERATE);

    // -----------------------------------
    // ---------- Mesh teardown ----------
    // -----------------------------------
    if (vertices.attribFlags & VertexAttribFlags::Alpha) {
        glDisableVertexAttribArray(alphaLocation);
    }
    if (vertices.attribFlags & VertexAttribFlags::Color) {
        glDisableVertexAttribArray(colorLocation);
    }

    GL_CHECKPOINT(MODERATE);
}

void RenderState::dump() {
    blend().dump();
    meshState().dump();
    scissor().dump();
    stencil().dump();
}

} /* namespace uirenderer */
} /* namespace android */
