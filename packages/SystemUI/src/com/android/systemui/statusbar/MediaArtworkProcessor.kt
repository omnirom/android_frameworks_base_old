/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.MathUtils
import com.android.internal.graphics.ColorUtils
import com.android.systemui.statusbar.notification.MediaNotificationProcessor

import javax.inject.Inject
import javax.inject.Singleton

private const val COLOR_ALPHA = (255 * 0.7f).toInt()
//private const val BLUR_RADIUS = 25f
private const val DOWNSAMPLE = 6

@Singleton
class MediaArtworkProcessor @Inject constructor() {

    private val mTmpSize = Point()
    private var mArtworkCache: Bitmap? = null
    private var mDownSample: Int = DOWNSAMPLE
    private var mColorAlpha: Int = COLOR_ALPHA

    fun processArtwork(context: Context, artwork: Bitmap, blur_radius: Float): Bitmap {
        if (mArtworkCache != null) {
            return mArtworkCache!!
        }

        if (blur_radius < 5f) {
            mDownSample = 2
            mColorAlpha = (mColorAlpha * 0.5f).toInt()
        } else if (blur_radius < 1f) {
            mDownSample = 1
            mColorAlpha = (mColorAlpha * 0.1f).toInt()
        }
        context.display.getSize(mTmpSize)
        val renderScript = RenderScript.create(context)
        val rect = Rect(0, 0, artwork.width, artwork.height)
        MathUtils.fitRect(rect, Math.max(mTmpSize.x / mDownSample, mTmpSize.y / mDownSample))
        var inBitmap = Bitmap.createScaledBitmap(artwork, rect.width(), rect.height(),
                true /* filter */)
        // Render script blurs only support ARGB_8888, we need a conversion if we got a
        // different bitmap config.
        if (inBitmap.config != Bitmap.Config.ARGB_8888) {
            val oldIn = inBitmap
            inBitmap = oldIn.copy(Bitmap.Config.ARGB_8888, false /* isMutable */)
            oldIn.recycle()
        }
        val input = Allocation.createFromBitmap(renderScript, inBitmap,
                Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_GRAPHICS_TEXTURE)
        val outBitmap = Bitmap.createBitmap(inBitmap.width, inBitmap.height,
                Bitmap.Config.ARGB_8888)
        val output = Allocation.createFromBitmap(renderScript, outBitmap)
        val blur = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript))
        blur.setRadius(blur_radius)
        blur.setInput(input)
        blur.forEach(output)
        output.copyTo(outBitmap)

        val swatch = MediaNotificationProcessor.findBackgroundSwatch(artwork)

        input.destroy()
        output.destroy()
        inBitmap.recycle()
        blur.destroy()

        val canvas = Canvas(outBitmap)
        canvas.drawColor(ColorUtils.setAlphaComponent(swatch.rgb, COLOR_ALPHA))
        return outBitmap
    }

    fun clearCache() {
        mArtworkCache?.recycle()
        mArtworkCache = null
    }
}
