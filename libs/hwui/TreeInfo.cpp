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

#include "TreeInfo.h"

#include "renderthread/CanvasContext.h"

namespace android::uirenderer {

TreeInfo::TreeInfo(TraversalMode mode, renderthread::CanvasContext& canvasContext)
        : mode(mode)
        , prepareTextures(mode == MODE_FULL)
        , canvasContext(canvasContext)
        , disableForceDark(canvasContext.getForceDarkType() == ForceDarkType::NONE ? 1 : 0)
        , forceDarkType(canvasContext.getForceDarkType())
        , screenSize(canvasContext.getNextFrameSize()) {}

}  // namespace android::uirenderer
