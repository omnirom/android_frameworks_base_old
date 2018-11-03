/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.systemui.screenshot.GlobalScreenshot.SHARING_INTENT;
import static com.android.systemui.statusbar.phone.StatusBar.SYSTEM_DIALOG_REASON_SCREENSHOT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.admin.DevicePolicyManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.Notification.BigPictureStyle;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.Toast;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.util.omni.TaskUtils;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.util.NotificationChannels;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * POD used in the AsyncTask which saves an image in the background.
 */
class SaveImageInBackgroundData {
    Context context;
    Bitmap image;
    Uri imageUri;
    Runnable finisher;
    int iconSize;
    int previewWidth;
    int previewheight;
    int errorMsgResId;

    void clearImage() {
        image = null;
        imageUri = null;
        iconSize = 0;
    }
    void clearContext() {
        context = null;
    }
}

/**
 * An AsyncTask that saves an image to the media store in the background.
 */
class SaveImageInBackgroundTask extends AsyncTask<Void, Void, Void> {
    private static final String TAG = "SaveImageInBackgroundTask";

    private static final String SCREENSHOTS_DIR_NAME = "Screenshots";
    private static final String SCREENSHOT_FILE_NAME_TEMPLATE = "Screenshot_%s.png";
    private static final String SCREENSHOT_FILE_NAME_TEMPLATE_APPNAME = "Screenshot_%s_%s.png";
    private static final String SCREENSHOT_SHARE_SUBJECT_TEMPLATE = "Screenshot (%s)";

    private final SaveImageInBackgroundData mParams;
    private final NotificationManager mNotificationManager;
    private final Notification.Builder mNotificationBuilder, mPublicNotificationBuilder;
    private final File mScreenshotDir;
    private String mImageFileName;
    private final String mImageFilePath;
    private final long mImageTime;
    private final BigPictureStyle mNotificationStyle;
    private final int mImageWidth;
    private final int mImageHeight;

    SaveImageInBackgroundTask(Context context, SaveImageInBackgroundData data,
            NotificationManager nManager) {
        Resources r = context.getResources();

        // Prepare all the output metadata
        mParams = data;
        mImageTime = System.currentTimeMillis();
        String imageDate = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(mImageTime));
        mImageFileName = String.format(SCREENSHOT_FILE_NAME_TEMPLATE, imageDate);
        final PackageManager pm = context.getPackageManager();
        ActivityInfo info = TaskUtils.getRunningActivityInfo(context);
        boolean onKeyguard = context.getSystemService(KeyguardManager.class).isKeyguardLocked();
        if (info != null && !onKeyguard) {
            CharSequence appName = pm.getApplicationLabel(info.applicationInfo);
            if (appName != null) {
                // replace all spaces and special chars with an underscore
                String appNameString = appName.toString().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
                mImageFileName = String.format(SCREENSHOT_FILE_NAME_TEMPLATE_APPNAME, appNameString, imageDate);
            }
        }

        mScreenshotDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), SCREENSHOTS_DIR_NAME);
        mImageFilePath = new File(mScreenshotDir, mImageFileName).getAbsolutePath();

        // Create the large notification icon
        mImageWidth = data.image.getWidth();
        mImageHeight = data.image.getHeight();
        int iconSize = data.iconSize;
        int previewWidth = data.previewWidth;
        int previewHeight = data.previewheight;

        Paint paint = new Paint();
        ColorMatrix desat = new ColorMatrix();
        desat.setSaturation(0.25f);
        paint.setColorFilter(new ColorMatrixColorFilter(desat));
        Matrix matrix = new Matrix();
        int overlayColor = 0x40FFFFFF;

        matrix.setTranslate((previewWidth - mImageWidth) / 2, (previewHeight - mImageHeight) / 2);
        Bitmap picture = generateAdjustedHwBitmap(data.image, previewWidth, previewHeight, matrix,
                paint, overlayColor);

        // Note, we can't use the preview for the small icon, since it is non-square
        float scale = (float) iconSize / Math.min(mImageWidth, mImageHeight);
        matrix.setScale(scale, scale);
        matrix.postTranslate((iconSize - (scale * mImageWidth)) / 2,
                (iconSize - (scale * mImageHeight)) / 2);
        Bitmap icon = generateAdjustedHwBitmap(data.image, iconSize, iconSize, matrix, paint,
                overlayColor);

        mNotificationManager = nManager;
        final long now = System.currentTimeMillis();

        // Setup the notification
        mNotificationStyle = new Notification.BigPictureStyle()
                .bigPicture(picture.createAshmemBitmap());

        // The public notification will show similar info but with the actual screenshot omitted
        mPublicNotificationBuilder =
                new Notification.Builder(context, NotificationChannels.SCREENSHOTS_HEADSUP)
                        .setContentTitle(r.getString(R.string.screenshot_saving_title))
                        .setSmallIcon(R.drawable.stat_notify_image)
                        .setCategory(Notification.CATEGORY_PROGRESS)
                        .setWhen(now)
                        .setShowWhen(true)
                        .setColor(r.getColor(
                                com.android.internal.R.color.system_notification_accent_color));
        SystemUI.overrideNotificationAppName(context, mPublicNotificationBuilder, true);

        mNotificationBuilder = new Notification.Builder(context,
                NotificationChannels.SCREENSHOTS_HEADSUP)
            .setContentTitle(r.getString(R.string.screenshot_saving_title))
            .setSmallIcon(R.drawable.stat_notify_image)
            .setWhen(now)
            .setShowWhen(true)
            .setColor(r.getColor(com.android.internal.R.color.system_notification_accent_color))
            .setStyle(mNotificationStyle)
            .setPublicVersion(mPublicNotificationBuilder.build());
        mNotificationBuilder.setFlag(Notification.FLAG_NO_CLEAR, true);
        SystemUI.overrideNotificationAppName(context, mNotificationBuilder, true);

        mNotificationManager.notify(SystemMessage.NOTE_GLOBAL_SCREENSHOT,
                mNotificationBuilder.build());

        /**
         * NOTE: The following code prepares the notification builder for updating the notification
         * after the screenshot has been written to disk.
         */

        // On the tablet, the large icon makes the notification appear as if it is clickable (and
        // on small devices, the large icon is not shown) so defer showing the large icon until
        // we compose the final post-save notification below.
        mNotificationBuilder.setLargeIcon(icon.createAshmemBitmap());
        // But we still don't set it for the expanded view, allowing the smallIcon to show here.
        mNotificationStyle.bigLargeIcon((Bitmap) null);
    }

    /**
     * Generates a new hardware bitmap with specified values, copying the content from the passed
     * in bitmap.
     */
    private Bitmap generateAdjustedHwBitmap(Bitmap bitmap, int width, int height, Matrix matrix,
            Paint paint, int color) {
        Picture picture = new Picture();
        Canvas canvas = picture.beginRecording(width, height);
        canvas.drawColor(color);
        canvas.drawBitmap(bitmap, matrix, paint);
        picture.endRecording();
        return Bitmap.createBitmap(picture);
    }

    @Override
    protected Void doInBackground(Void... params) {
        if (isCancelled()) {
            return null;
        }

        // By default, AsyncTask sets the worker thread to have background thread priority, so bump
        // it back up so that we save a little quicker.
        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

        Context context = mParams.context;
        Bitmap image = mParams.image;
        Resources r = context.getResources();

        try {
            // Create screenshot directory if it doesn't exist
            mScreenshotDir.mkdirs();

            // media provider uses seconds for DATE_MODIFIED and DATE_ADDED, but milliseconds
            // for DATE_TAKEN
            long dateSeconds = mImageTime / 1000;

            // Save
            OutputStream out = new FileOutputStream(mImageFilePath);
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();

            // Save the screenshot to the MediaStore
            ContentValues values = new ContentValues();
            ContentResolver resolver = context.getContentResolver();
            values.put(MediaStore.Images.ImageColumns.DATA, mImageFilePath);
            values.put(MediaStore.Images.ImageColumns.TITLE, mImageFileName);
            values.put(MediaStore.Images.ImageColumns.DISPLAY_NAME, mImageFileName);
            values.put(MediaStore.Images.ImageColumns.DATE_TAKEN, mImageTime);
            values.put(MediaStore.Images.ImageColumns.DATE_ADDED, dateSeconds);
            values.put(MediaStore.Images.ImageColumns.DATE_MODIFIED, dateSeconds);
            values.put(MediaStore.Images.ImageColumns.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.ImageColumns.WIDTH, mImageWidth);
            values.put(MediaStore.Images.ImageColumns.HEIGHT, mImageHeight);
            values.put(MediaStore.Images.ImageColumns.SIZE, new File(mImageFilePath).length());
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            // Create a share intent
            String subjectDate = DateFormat.getDateTimeInstance().format(new Date(mImageTime));
            String subject = String.format(SCREENSHOT_SHARE_SUBJECT_TEMPLATE, subjectDate);
            Intent sharingIntent = new Intent(Intent.ACTION_SEND);
            sharingIntent.setType("image/png");
            sharingIntent.putExtra(Intent.EXTRA_STREAM, uri);
            sharingIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
            sharingIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // Create a share action for the notification. Note, we proxy the call to
            // ScreenshotActionReceiver because RemoteViews currently forces an activity options
            // on the PendingIntent being launched, and since we don't want to trigger the share
            // sheet in this case, we start the chooser activity directly in
            // ScreenshotActionReceiver.
            PendingIntent shareAction = PendingIntent.getBroadcast(context, 0,
                    new Intent(context, GlobalScreenshot.ScreenshotActionReceiver.class)
                            .putExtra(SHARING_INTENT, sharingIntent),
                    PendingIntent.FLAG_CANCEL_CURRENT);
            Notification.Action.Builder shareActionBuilder = new Notification.Action.Builder(
                    R.drawable.ic_screenshot_share,
                    r.getString(com.android.internal.R.string.share), shareAction);
            mNotificationBuilder.addAction(shareActionBuilder.build());

            Intent editIntent = new Intent(Intent.ACTION_EDIT);
            editIntent.setType("image/png");
            editIntent.setData(uri);
            editIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            editIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            // Create a edit action for the notification the same way.
            PendingIntent editAction = PendingIntent.getBroadcast(context, 1,
                    new Intent(context, GlobalScreenshot.ScreenshotActionReceiver.class)
                            .putExtra(SHARING_INTENT, editIntent),
                    PendingIntent.FLAG_CANCEL_CURRENT);
            Notification.Action.Builder editActionBuilder = new Notification.Action.Builder(
                    R.drawable.ic_screenshot_edit,
                    r.getString(com.android.internal.R.string.screenshot_edit), editAction);
            mNotificationBuilder.addAction(editActionBuilder.build());


            // Create a delete action for the notification
            PendingIntent deleteAction = PendingIntent.getBroadcast(context, 0,
                    new Intent(context, GlobalScreenshot.DeleteScreenshotReceiver.class)
                            .putExtra(GlobalScreenshot.SCREENSHOT_URI_ID, uri.toString()),
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT);
            Notification.Action.Builder deleteActionBuilder = new Notification.Action.Builder(
                    R.drawable.ic_screenshot_delete,
                    r.getString(com.android.internal.R.string.delete), deleteAction);
            mNotificationBuilder.addAction(deleteActionBuilder.build());

            mParams.imageUri = uri;
            mParams.image = null;
            mParams.errorMsgResId = 0;
        } catch (Exception e) {
            // IOException/UnsupportedOperationException may be thrown if external storage is not
            // mounted
            Slog.e(TAG, "unable to save screenshot", e);
            mParams.clearImage();
            mParams.errorMsgResId = R.string.screenshot_failed_to_save_text;
        }

        // Recycle the bitmap data
        if (image != null) {
            image.recycle();
        }

        return null;
    }

    @Override
    protected void onPostExecute(Void params) {
        if (mParams.errorMsgResId != 0) {
            // Show a message that we've failed to save the image to disk
            GlobalScreenshot.notifyScreenshotError(mParams.context, mNotificationManager,
                    mParams.errorMsgResId);
        } else {
            // Show the final notification to indicate screenshot saved
            Context context = mParams.context;
            Resources r = context.getResources();

            // Create the intent to show the screenshot in gallery
            Intent launchIntent = new Intent(Intent.ACTION_VIEW);
            launchIntent.setDataAndType(mParams.imageUri, "image/png");
            launchIntent.setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);

            final long now = System.currentTimeMillis();

            // Update the text and the icon for the existing notification
            mPublicNotificationBuilder
                    .setContentTitle(r.getString(R.string.screenshot_saved_title))
                    .setContentText(r.getString(R.string.screenshot_saved_text))
                    .setContentIntent(PendingIntent.getActivity(mParams.context, 0, launchIntent, 0))
                    .setWhen(now)
                    .setAutoCancel(true)
                    .setColor(context.getColor(
                            com.android.internal.R.color.system_notification_accent_color));
            mNotificationBuilder
                .setContentTitle(r.getString(R.string.screenshot_saved_title))
                .setContentText(r.getString(R.string.screenshot_saved_text))
                .setContentIntent(PendingIntent.getActivity(mParams.context, 0, launchIntent, 0))
                .setWhen(now)
                .setAutoCancel(true)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setPublicVersion(mPublicNotificationBuilder.build())
                .setFlag(Notification.FLAG_NO_CLEAR, false);

            mNotificationManager.notify(SystemMessage.NOTE_GLOBAL_SCREENSHOT,
                    mNotificationBuilder.build());
        }
        mParams.finisher.run();
        mParams.clearContext();
    }

    @Override
    protected void onCancelled(Void params) {
        // If we are cancelled while the task is running in the background, we may get null params.
        // The finisher is expected to always be called back, so just use the baked-in params from
        // the ctor in any case.
        mParams.finisher.run();
        mParams.clearImage();
        mParams.clearContext();

        // Cancel the posted notification
        mNotificationManager.cancel(SystemMessage.NOTE_GLOBAL_SCREENSHOT);
    }
}

/**
 * An AsyncTask that deletes an image from the media store in the background.
 */
class DeleteImageInBackgroundTask extends AsyncTask<Uri, Void, Void> {
    private Context mContext;

    DeleteImageInBackgroundTask(Context context) {
        mContext = context;
    }

    @Override
    protected Void doInBackground(Uri... params) {
        if (params.length != 1) return null;

        Uri screenshotUri = params[0];
        ContentResolver resolver = mContext.getContentResolver();
        resolver.delete(screenshotUri, null, null);
        return null;
    }
}

class GlobalScreenshot {
    static final String SCREENSHOT_URI_ID = "android:screenshot_uri_id";
    static final String SHARING_INTENT = "android:screenshot_sharing_intent";

    private static final int SCREENSHOT_FLASH_TO_PEAK_DURATION = 130;
    private static final int SCREENSHOT_DROP_IN_DURATION = 430;
    private static final int SCREENSHOT_DROP_OUT_DELAY = 500;
    private static final int SCREENSHOT_DROP_OUT_DURATION = 430;
    private static final int SCREENSHOT_DROP_OUT_SCALE_DURATION = 370;
    private static final int SCREENSHOT_FAST_DROP_OUT_DURATION = 320;
    private static final float BACKGROUND_ALPHA = 0.5f;
    private static final float SCREENSHOT_SCALE = 1f;
    private static final float SCREENSHOT_DROP_IN_MIN_SCALE = SCREENSHOT_SCALE * 0.725f;
    private static final float SCREENSHOT_DROP_OUT_MIN_SCALE = SCREENSHOT_SCALE * 0.45f;
    private static final float SCREENSHOT_FAST_DROP_OUT_MIN_SCALE = SCREENSHOT_SCALE * 0.6f;
    private static final float SCREENSHOT_DROP_OUT_MIN_SCALE_OFFSET = 0f;
    private final int mPreviewWidth;
    private final int mPreviewHeight;

    private Context mContext;
    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mWindowLayoutParams;
    private NotificationManager mNotificationManager;
    private Display mDisplay;
    private DisplayMetrics mDisplayMetrics;
    private Matrix mDisplayMatrix;

    private Bitmap mScreenBitmap;
    private View mScreenshotLayout;
    private ScreenshotSelectorView mScreenshotSelectorView;
    private ImageView mBackgroundView;
    private ImageView mScreenshotView;
    private ImageView mScreenshotFlash;

    private AnimatorSet mScreenshotAnimation;

    private int mNotificationIconSize;
    private float mBgPadding;
    private float mBgPaddingScale;

    private AsyncTask<Void, Void, Void> mSaveInBgTask;

    private MediaActionSound mCameraSound;


    /**
     * @param context everything needs a context :(
     */
    public GlobalScreenshot(Context context) {
        Resources r = context.getResources();
        mContext = context;
        LayoutInflater layoutInflater = (LayoutInflater)
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        // Inflate the screenshot layout
        mDisplayMatrix = new Matrix();
        mScreenshotLayout = layoutInflater.inflate(R.layout.global_screenshot, null);
        mBackgroundView = (ImageView) mScreenshotLayout.findViewById(R.id.global_screenshot_background);
        mScreenshotView = (ImageView) mScreenshotLayout.findViewById(R.id.global_screenshot);
        mScreenshotFlash = (ImageView) mScreenshotLayout.findViewById(R.id.global_screenshot_flash);
        mScreenshotSelectorView = (ScreenshotSelectorView) mScreenshotLayout.findViewById(
                R.id.global_screenshot_selector);
        mScreenshotLayout.setFocusable(true);
        mScreenshotSelectorView.setFocusable(true);
        mScreenshotSelectorView.setFocusableInTouchMode(true);
        mScreenshotLayout.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // Intercept and ignore all touch events
                return true;
            }
        });

        // Setup the window that we are going to use
        mWindowLayoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0,
                WindowManager.LayoutParams.TYPE_SCREENSHOT,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT);
        mWindowLayoutParams.setTitle("ScreenshotAnimation");
        mWindowLayoutParams.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mNotificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mDisplay = mWindowManager.getDefaultDisplay();
        mDisplayMetrics = new DisplayMetrics();
        mDisplay.getRealMetrics(mDisplayMetrics);

        // Get the various target sizes
        mNotificationIconSize =
            r.getDimensionPixelSize(android.R.dimen.notification_large_icon_height);

        // Scale has to account for both sides of the bg
        mBgPadding = (float) r.getDimensionPixelSize(R.dimen.global_screenshot_bg_padding);
        mBgPaddingScale = mBgPadding /  mDisplayMetrics.widthPixels;

        // determine the optimal preview size
        int panelWidth = 0;
        try {
            panelWidth = r.getDimensionPixelSize(R.dimen.notification_panel_width);
        } catch (Resources.NotFoundException e) {
        }
        if (panelWidth <= 0) {
            // includes notification_panel_width==match_parent (-1)
            panelWidth = mDisplayMetrics.widthPixels;
        }
        mPreviewWidth = panelWidth;
        mPreviewHeight = r.getDimensionPixelSize(R.dimen.notification_max_height);

        // Setup the Camera shutter sound
        mCameraSound = new MediaActionSound();
        mCameraSound.load(MediaActionSound.SHUTTER_CLICK);
    }

    /**
     * Creates a new worker thread and saves the screenshot to the media store.
     */
    private void saveScreenshotInWorkerThread(Runnable finisher) {
        SaveImageInBackgroundData data = new SaveImageInBackgroundData();
        data.context = mContext;
        data.image = mScreenBitmap;
        data.iconSize = mNotificationIconSize;
        data.finisher = finisher;
        data.previewWidth = mPreviewWidth;
        data.previewheight = mPreviewHeight;
        if (mSaveInBgTask != null) {
            mSaveInBgTask.cancel(false);
        }
        mSaveInBgTask = new SaveImageInBackgroundTask(mContext, data, mNotificationManager)
                .execute();
    }

    /**
     * @return the current display rotation in degrees
     */
    private float getDegreesForRotation(int value) {
        switch (value) {
        case Surface.ROTATION_90:
            return 360f - 90f;
        case Surface.ROTATION_180:
            return 360f - 180f;
        case Surface.ROTATION_270:
            return 360f - 270f;
        }
        return 0f;
    }

    /**
     * Takes a screenshot of the current display and shows an animation.
     */
    private void takeScreenshot(Runnable finisher, boolean statusBarVisible, boolean navBarVisible,
            Rect crop) {
        int rot = mDisplay.getRotation();
        int width = crop.width();
        int height = crop.height();

        // Take the screenshot
        mScreenBitmap = SurfaceControl.screenshot(crop, width, height, rot);
        if (mScreenBitmap == null) {
            notifyScreenshotError(mContext, mNotificationManager,
                    R.string.screenshot_failed_to_capture_text);
            finisher.run();
            return;
        }

        // Optimizations
        mScreenBitmap.setHasAlpha(false);
        mScreenBitmap.prepareToDraw();

        // Start the post-screenshot animation
        startAnimation(finisher, mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels,
                statusBarVisible, navBarVisible);
    }

    void takeScreenshot(Runnable finisher, boolean statusBarVisible, boolean navBarVisible) {
        mDisplay.getRealMetrics(mDisplayMetrics);
        takeScreenshot(finisher, statusBarVisible, navBarVisible,
                new Rect(0, 0, mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels));
    }

    /**
     * Displays a screenshot selector
     */
    void takeScreenshotPartial(final Runnable finisher, final boolean statusBarVisible,
            final boolean navBarVisible) {
        mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);
        mScreenshotSelectorView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                ScreenshotSelectorView view = (ScreenshotSelectorView) v;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        view.startSelection((int) event.getX(), (int) event.getY());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        view.updateSelection((int) event.getX(), (int) event.getY());
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.setVisibility(View.GONE);
                        mWindowManager.removeView(mScreenshotLayout);
                        final Rect rect = view.getSelectionRect();
                        if (rect != null) {
                            if (rect.width() != 0 && rect.height() != 0) {
                                // Need mScreenshotLayout to handle it after the view disappears
                                mScreenshotLayout.post(new Runnable() {
                                    public void run() {
                                        takeScreenshot(finisher, statusBarVisible, navBarVisible,
                                                rect);
                                    }
                                });
                            }
                        }

                        view.stopSelection();
                        return true;
                }

                return false;
            }
        });
        mScreenshotLayout.post(new Runnable() {
            @Override
            public void run() {
                mScreenshotSelectorView.setVisibility(View.VISIBLE);
                mScreenshotSelectorView.requestFocus();
            }
        });
    }

    /**
     * Cancels screenshot request
     */
    void stopScreenshot() {
        // If the selector layer still presents on screen, we remove it and resets its state.
        if (mScreenshotSelectorView.getSelectionRect() != null) {
            mWindowManager.removeView(mScreenshotLayout);
            mScreenshotSelectorView.stopSelection();
        }
    }

    /**
     * Starts the animation after taking the screenshot
     */
    private void startAnimation(final Runnable finisher, int w, int h, boolean statusBarVisible,
            boolean navBarVisible) {
        // If power save is on, show a toast so there is some visual indication that a screenshot
        // has been taken.
        PowerManager powerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        if (powerManager.isPowerSaveMode()) {
            Toast.makeText(mContext, R.string.screenshot_saved_title, Toast.LENGTH_SHORT).show();
        }

        // Add the view for the animation
        mScreenshotView.setImageBitmap(mScreenBitmap);
        mScreenshotLayout.requestFocus();

        // Setup the animation with the screenshot just taken
        if (mScreenshotAnimation != null) {
            if (mScreenshotAnimation.isStarted()) {
                mScreenshotAnimation.end();
            }
            mScreenshotAnimation.removeAllListeners();
        }

        mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);
        ValueAnimator screenshotDropInAnim = createScreenshotDropInAnimation();
        ValueAnimator screenshotFadeOutAnim = createScreenshotDropOutAnimation(w, h,
                statusBarVisible, navBarVisible);
        mScreenshotAnimation = new AnimatorSet();
        mScreenshotAnimation.playSequentially(screenshotDropInAnim, screenshotFadeOutAnim);
        mScreenshotAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Save the screenshot once we have a bit of time now
                saveScreenshotInWorkerThread(finisher);
                mWindowManager.removeView(mScreenshotLayout);

                // Clear any references to the bitmap
                mScreenBitmap = null;
                mScreenshotView.setImageBitmap(null);
            }
        });
        mScreenshotLayout.post(new Runnable() {
            @Override
            public void run() {
                if (Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.OMNI_SCREENSHOT_SHUTTER_SOUND, 1, UserHandle.USER_CURRENT) == 1) {
                    // Play the shutter sound to notify that we've taken a screenshot
                    mCameraSound.play(MediaActionSound.SHUTTER_CLICK);
                }

                mScreenshotView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                mScreenshotView.buildLayer();
                mScreenshotAnimation.start();
            }
        });
    }
    private ValueAnimator createScreenshotDropInAnimation() {
        final float flashPeakDurationPct = ((float) (SCREENSHOT_FLASH_TO_PEAK_DURATION)
                / SCREENSHOT_DROP_IN_DURATION);
        final float flashDurationPct = 2f * flashPeakDurationPct;
        final Interpolator flashAlphaInterpolator = new Interpolator() {
            @Override
            public float getInterpolation(float x) {
                // Flash the flash view in and out quickly
                if (x <= flashDurationPct) {
                    return (float) Math.sin(Math.PI * (x / flashDurationPct));
                }
                return 0;
            }
        };
        final Interpolator scaleInterpolator = new Interpolator() {
            @Override
            public float getInterpolation(float x) {
                // We start scaling when the flash is at it's peak
                if (x < flashPeakDurationPct) {
                    return 0;
                }
                return (x - flashDurationPct) / (1f - flashDurationPct);
            }
        };
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(SCREENSHOT_DROP_IN_DURATION);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mBackgroundView.setAlpha(0f);
                mBackgroundView.setVisibility(View.VISIBLE);
                mScreenshotView.setAlpha(0f);
                mScreenshotView.setTranslationX(0f);
                mScreenshotView.setTranslationY(0f);
                mScreenshotView.setScaleX(SCREENSHOT_SCALE + mBgPaddingScale);
                mScreenshotView.setScaleY(SCREENSHOT_SCALE + mBgPaddingScale);
                mScreenshotView.setVisibility(View.VISIBLE);
                mScreenshotFlash.setAlpha(0f);
                mScreenshotFlash.setVisibility(View.VISIBLE);
            }
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                mScreenshotFlash.setVisibility(View.GONE);
            }
        });
        anim.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (Float) animation.getAnimatedValue();
                float scaleT = (SCREENSHOT_SCALE + mBgPaddingScale)
                    - scaleInterpolator.getInterpolation(t)
                        * (SCREENSHOT_SCALE - SCREENSHOT_DROP_IN_MIN_SCALE);
                mBackgroundView.setAlpha(scaleInterpolator.getInterpolation(t) * BACKGROUND_ALPHA);
                mScreenshotView.setAlpha(t);
                mScreenshotView.setScaleX(scaleT);
                mScreenshotView.setScaleY(scaleT);
                mScreenshotFlash.setAlpha(flashAlphaInterpolator.getInterpolation(t));
            }
        });
        return anim;
    }
    private ValueAnimator createScreenshotDropOutAnimation(int w, int h, boolean statusBarVisible,
            boolean navBarVisible) {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setStartDelay(SCREENSHOT_DROP_OUT_DELAY);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBackgroundView.setVisibility(View.GONE);
                mScreenshotView.setVisibility(View.GONE);
                mScreenshotView.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        });

        if (!statusBarVisible || !navBarVisible) {
            // There is no status bar/nav bar, so just fade the screenshot away in place
            anim.setDuration(SCREENSHOT_FAST_DROP_OUT_DURATION);
            anim.addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float t = (Float) animation.getAnimatedValue();
                    float scaleT = (SCREENSHOT_DROP_IN_MIN_SCALE + mBgPaddingScale)
                            - t * (SCREENSHOT_DROP_IN_MIN_SCALE - SCREENSHOT_FAST_DROP_OUT_MIN_SCALE);
                    mBackgroundView.setAlpha((1f - t) * BACKGROUND_ALPHA);
                    mScreenshotView.setAlpha(1f - t);
                    mScreenshotView.setScaleX(scaleT);
                    mScreenshotView.setScaleY(scaleT);
                }
            });
        } else {
            // In the case where there is a status bar, animate to the origin of the bar (top-left)
            final float scaleDurationPct = (float) SCREENSHOT_DROP_OUT_SCALE_DURATION
                    / SCREENSHOT_DROP_OUT_DURATION;
            final Interpolator scaleInterpolator = new Interpolator() {
                @Override
                public float getInterpolation(float x) {
                    if (x < scaleDurationPct) {
                        // Decelerate, and scale the input accordingly
                        return (float) (1f - Math.pow(1f - (x / scaleDurationPct), 2f));
                    }
                    return 1f;
                }
            };

            // Determine the bounds of how to scale
            float halfScreenWidth = (w - 2f * mBgPadding) / 2f;
            float halfScreenHeight = (h - 2f * mBgPadding) / 2f;
            final float offsetPct = SCREENSHOT_DROP_OUT_MIN_SCALE_OFFSET;
            final PointF finalPos = new PointF(
                -halfScreenWidth + (SCREENSHOT_DROP_OUT_MIN_SCALE + offsetPct) * halfScreenWidth,
                -halfScreenHeight + (SCREENSHOT_DROP_OUT_MIN_SCALE + offsetPct) * halfScreenHeight);

            // Animate the screenshot to the status bar
            anim.setDuration(SCREENSHOT_DROP_OUT_DURATION);
            anim.addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float t = (Float) animation.getAnimatedValue();
                    float scaleT = (SCREENSHOT_DROP_IN_MIN_SCALE + mBgPaddingScale)
                        - scaleInterpolator.getInterpolation(t)
                            * (SCREENSHOT_DROP_IN_MIN_SCALE - SCREENSHOT_DROP_OUT_MIN_SCALE);
                    mBackgroundView.setAlpha((1f - t) * BACKGROUND_ALPHA);
                    mScreenshotView.setAlpha(1f - scaleInterpolator.getInterpolation(t));
                    mScreenshotView.setScaleX(scaleT);
                    mScreenshotView.setScaleY(scaleT);
                    mScreenshotView.setTranslationX(t * finalPos.x);
                    mScreenshotView.setTranslationY(t * finalPos.y);
                }
            });
        }
        return anim;
    }

    static void notifyScreenshotError(Context context, NotificationManager nManager, int msgResId) {
        Resources r = context.getResources();
        String errorMsg = r.getString(msgResId);

        // Repurpose the existing notification to notify the user of the error
        Notification.Builder b = new Notification.Builder(context, NotificationChannels.ALERTS)
            .setTicker(r.getString(R.string.screenshot_failed_title))
            .setContentTitle(r.getString(R.string.screenshot_failed_title))
            .setContentText(errorMsg)
            .setSmallIcon(R.drawable.stat_notify_image_error)
            .setWhen(System.currentTimeMillis())
            .setVisibility(Notification.VISIBILITY_PUBLIC) // ok to show outside lockscreen
            .setCategory(Notification.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color));
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        final Intent intent = dpm.createAdminSupportIntent(
                DevicePolicyManager.POLICY_DISABLE_SCREEN_CAPTURE);
        if (intent != null) {
            final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(
                    context, 0, intent, 0, null, UserHandle.CURRENT);
            b.setContentIntent(pendingIntent);
        }

        SystemUI.overrideNotificationAppName(context, b, true);

        Notification n = new Notification.BigTextStyle(b)
                .bigText(errorMsg)
                .build();
        nManager.notify(SystemMessage.NOTE_GLOBAL_SCREENSHOT, n);
    }

    /**
     * Receiver to proxy the share or edit intent.
     */
    public static class ScreenshotActionReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                ActivityManager.getService().closeSystemDialogs(SYSTEM_DIALOG_REASON_SCREENSHOT);
            } catch (RemoteException e) {
            }

            Intent actionIntent = intent.getParcelableExtra(SHARING_INTENT);

            // If this is an edit & default editor exists, route straight there.
            String editorPackage = context.getResources().getString(R.string.config_screenshotEditor);
            if (actionIntent.getAction() == Intent.ACTION_EDIT &&
                    editorPackage != null && editorPackage.length() > 0) {
                actionIntent.setComponent(ComponentName.unflattenFromString(editorPackage));
                final NotificationManager nm =
                        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.cancel(SystemMessage.NOTE_GLOBAL_SCREENSHOT);
            } else {
                PendingIntent chooseAction = PendingIntent.getBroadcast(context, 0,
                        new Intent(context, GlobalScreenshot.TargetChosenReceiver.class),
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT);
                actionIntent = Intent.createChooser(actionIntent, null,
                        chooseAction.getIntentSender())
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            }

            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setDisallowEnterPictureInPictureWhileLaunching(true);

            context.startActivityAsUser(actionIntent, opts.toBundle(), UserHandle.CURRENT);
        }
    }

    /**
     * Removes the notification for a screenshot after a share or edit target is chosen.
     */
    public static class TargetChosenReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Clear the notification
            final NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(SystemMessage.NOTE_GLOBAL_SCREENSHOT);
        }
    }

    /**
     * Removes the last screenshot.
     */
    public static class DeleteScreenshotReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.hasExtra(SCREENSHOT_URI_ID)) {
                return;
            }

            // Clear the notification
            final NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            final Uri uri = Uri.parse(intent.getStringExtra(SCREENSHOT_URI_ID));
            nm.cancel(SystemMessage.NOTE_GLOBAL_SCREENSHOT);

            // And delete the image from the media store
            new DeleteImageInBackgroundTask(context).execute(uri);
        }
    }
}
