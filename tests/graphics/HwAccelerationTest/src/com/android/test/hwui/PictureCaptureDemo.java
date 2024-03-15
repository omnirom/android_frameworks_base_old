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
 * limitations under the License.
 */

package com.android.test.hwui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Picture;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewDebug;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Random;
import java.util.concurrent.Executors;

public class PictureCaptureDemo extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        inner.addView(spinner,
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        inner.addView(new View(this), new LayoutParams(50, 1));

        Picture picture = new Picture();
        Canvas canvas = picture.beginRecording(100, 100);
        canvas.drawColor(Color.RED);
        Paint paint = new Paint();
        paint.setTextSize(32);
        paint.setColor(Color.BLACK);
        canvas.drawText("Hello", 0, 50, paint);
        picture.endRecording();

        ImageView iv1 = new ImageView(this);
        iv1.setImageBitmap(Bitmap.createBitmap(picture, 100, 100, Bitmap.Config.ARGB_8888));
        inner.addView(iv1, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        inner.addView(new View(this), new LayoutParams(50, 1));

        ImageView iv2 = new ImageView(this);
        iv2.setImageBitmap(Bitmap.createBitmap(picture, 100, 100, Bitmap.Config.HARDWARE));
        inner.addView(iv2, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        TextView hello = new TextView(this);
        hello.setText("I'm on a layer!");
        hello.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        inner.addView(hello,
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        layout.addView(inner,
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        // For testing with a functor in the tree
        WebView wv = new WebView(this);
        wv.setWebViewClient(new WebViewClient());
        wv.setWebChromeClient(new WebChromeClient());
        wv.loadUrl("https://google.com");
        LayoutParams wvParams = new LayoutParams(LayoutParams.MATCH_PARENT, 400);
        wvParams.bottomMargin = 50;
        layout.addView(wv, wvParams);

        SurfaceView mySurfaceView = new SurfaceView(this);
        layout.addView(mySurfaceView,
                new LayoutParams(LayoutParams.MATCH_PARENT, 600, 1f));

        setContentView(layout);

        mySurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            private AutoCloseable mStopCapture;

            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                final Random rand = new Random();
                OutputStream renderingStream = new ByteArrayOutputStream() {
                    @Override
                    public void flush() throws IOException {
                        Picture picture = Picture.createFromStream(
                                new ByteArrayInputStream(buf, 0, count));
                        Canvas canvas = holder.lockCanvas();
                        if (canvas != null && picture != null) {
                            canvas.drawPicture(picture);
                            holder.unlockCanvasAndPost(canvas);
                        }
                        reset();
                    }
                };

                mStopCapture = ViewDebug.startRenderingCommandsCapture(mySurfaceView,
                        Executors.newSingleThreadExecutor(), () -> {
                            if (rand.nextInt(20) == 0) {
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e) {
                                }
                            }
                            return renderingStream;
                        });
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (mStopCapture != null) {
                    try {
                        mStopCapture.close();
                    } catch (Exception e) {
                    }
                    mStopCapture = null;
                }
            }
        });
    }
}
