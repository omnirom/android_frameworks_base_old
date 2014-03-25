/*
 * Copyright (C) 2014 ParanoidAndroid Project
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

package com.android.wallpapercropper;

import android.app.Activity;
import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Settings;
import android.renderscript.Allocation;
import android.renderscript.Allocation.MipmapControl;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

public class LockscreenWallpaper extends Activity {
    private static final String TAG = "CUSTOM_LOCKSCREEN";

    private static final String INTENT_LOCKSCREEN_WALLPAPER_CHANGED = "lockscreen_changed";
    private static final int REQUEST_CODE_BG_WALLPAPER = 1024;
    private static final int REQUEST_CODE_CROP_WALLPAPER = 1025;
    private static final int DEFAULT_BLUR = 12;

    private SeekBar sBar;
    private Context mContext;
    private Dialog mDialog;
    private File mWallpaperTemporary, mSavedLockscreen;
    private ActionBar actionBar;
    private ViewGroup mRoot;
    private ImageView mBackground, mPreview;
    private Drawable mBlurredImage;

    private String mPath;

    private boolean mBlurLevelUpdated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!enableRotation()) {
            setRequestedOrientation(Configuration.ORIENTATION_PORTRAIT);
        }

        mContext = getBaseContext();
        setContentView(R.layout.lockscreen_main);

        View mRoot = findViewById(R.id.lockscreen_root);
        View reset = findViewById(R.id.lockscreen_reset);
        View select = findViewById(R.id.lockscreen_select);
        View blur = findViewById(R.id.lockscreen_blur);

        mWallpaperTemporary = new File(mContext.getExternalCacheDir() + "/lockwallpaper.tmp");
        mSavedLockscreen =  new File(mContext.getExternalCacheDir() + "/lockwallpaper.sav");
        mBackground = (ImageView) findViewById(R.id.picker_background);
        mPreview = (ImageView) findViewById(R.id.lockscreen_preview);
        Drawable image = getCurrentWallpaper();

        if (mSavedLockscreen.exists()) {
            mPreview.setBackground(image);
        } else {
            mBackground.setBackground(image);
            resizeLayout(image);
        }

        LayoutInflater inflater = (LayoutInflater)this.getSystemService(LAYOUT_INFLATER_SERVICE);
        View dialogLayout = inflater.inflate(R.layout.blur_dialog,
                                (ViewGroup)findViewById(R.id.blur_dialog));

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.blur_radius)
               .setView(dialogLayout);

        mDialog = builder.create();

        sBar = (SeekBar) dialogLayout.findViewById(R.id.blur_seekbar);
        sBar.setOnSeekBarChangeListener(mSeekBarListener);

        actionBar = getActionBar();
        actionBar.setCustomView(R.layout.actionbar_set_lockscreen);
        actionBar.getCustomView().setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Backup blur setting
                        int seeThrough = Settings.System.getInt(mContext.getContentResolver(),
                                Settings.System.LOCKSCREEN_SEE_THROUGH, 0);
                        Settings.System.putInt(mContext.getContentResolver(),
                                Settings.System.LOCKSCREEN_SEE_THROUGH_BACKUP, seeThrough);
                        // Disable blur
                        Settings.System.putInt(mContext.getContentResolver(),
                                Settings.System.LOCKSCREEN_SEE_THROUGH, 0);

                        if (mBlurredImage == null) {
                            // copy temp image to save file
                            try {
                                moveFile(mWallpaperTemporary, mSavedLockscreen);
                            } catch (IOException e){
                                Log.e(TAG, "failed to move file");
                            }
                        } else {
                            // write directy to save file
                            try {
                                Bitmap bmp = ((BitmapDrawable)mBlurredImage).getBitmap();
                                FileOutputStream fileOut = new FileOutputStream(mSavedLockscreen);
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, fileOut);
                                fileOut.flush();
                                fileOut.close();
                            } catch (FileNotFoundException ex) {
                                Log.e(TAG, "file not found: " + ex.toString());
                            } catch (IOException e) {
                                Log.e(TAG, e.toString());
                            }
                        }

                        Intent intent = new Intent();
                        intent.setAction(INTENT_LOCKSCREEN_WALLPAPER_CHANGED);
                        sendBroadcast(intent);
                        finish();
                        Toast.makeText(mContext, R.string.lockscreen_image_set,
                            Toast.LENGTH_SHORT).show();
                    }
                });
        reset.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Restore blur setting
                        int seeThrough = Settings.System.getInt(mContext.getContentResolver(),
                                Settings.System.LOCKSCREEN_SEE_THROUGH_BACKUP, 0);
                        Settings.System.putInt(mContext.getContentResolver(),
                                Settings.System.LOCKSCREEN_SEE_THROUGH, seeThrough);
                        reset();
                        mWallpaperTemporary.delete();

                        if (mSavedLockscreen.delete()) {
                            Intent intent = new Intent();
                            intent.setAction(INTENT_LOCKSCREEN_WALLPAPER_CHANGED);
                            sendBroadcast(intent);
                            Toast.makeText(mContext, R.string.lockscreen_image_reset,
                                Toast.LENGTH_SHORT).show();
                        }
                        mPreview.setBackground(null);
                        mBackground.setBackground(null);
                        Drawable image = getCurrentWallpaper();
                        mBackground.setBackground(image);
                        resizeLayout(image);
                        actionBar.hide();
                    }
                });
        select.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickImage();
                    }
                });
        blur.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!mWallpaperTemporary.exists()) {
                            cropImage(getCurrentWallpaper());
                        } else {
                            if (!loadBlurDialog()) return;
                        }
                        actionBar.show();
                    }
                });
        actionBar.hide();
    }

    public boolean enableRotation() {
        return getResources().getBoolean(R.bool.allow_rotation);
    }

    private boolean loadBlurDialog() {
        Drawable preview = getPreview();
        if (preview == null) {
            Toast.makeText(mContext, R.string.lockscreen_image_absent,
                    Toast.LENGTH_SHORT).show();
            return false;
        } else {
            mDialog.show();
            String title = getResources().getString(R.string.blur_radius);
            mDialog.setTitle(title + ": " + Integer.toString(sBar.getProgress()));
            mBlurredImage = blurBitmap(preview, sBar.getProgress());
            mPreview.setBackground(mBlurredImage);
            return true;
        }
    }

    private void resizeLayout(Drawable image) {
        LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) mBackground.getLayoutParams();
        params.width = image.getIntrinsicWidth();
        params.height = image.getIntrinsicHeight();
        mBackground.setLayoutParams(params);
    }

    private void pickImage() {
        final Intent intent = new Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        intent.putExtra("crop", "true");
        intent.putExtra("scale", true);
        intent.putExtra("scaleUpIfNeeded", false);
        intent.putExtra("outputFormat", Bitmap.CompressFormat.PNG.toString());

        final Display display = getWindowManager().getDefaultDisplay();

        boolean isPortrait = getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_PORTRAIT;

        Point size = new Point();
        display.getSize(size);

        intent.putExtra("aspectX", isPortrait ? size.x : size.y);
        intent.putExtra("aspectY", isPortrait ? size.y : size.x);

        try {
            mWallpaperTemporary.deleteOnExit();
            mWallpaperTemporary.createNewFile();
            mWallpaperTemporary.setWritable(true, false);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(mWallpaperTemporary));
            intent.putExtra("return-data", false);
            startActivityForResult(intent, REQUEST_CODE_BG_WALLPAPER);
        } catch (IOException e) {
            // Do nothing
        } catch (ActivityNotFoundException e) {
            // Do nothing
        }
    }

    private void cropImage(Drawable image) {
        Bitmap bmp = ((BitmapDrawable)image).getBitmap();
        try {
            Intent cropIntent = new Intent("com.android.camera.action.CROP");
            mPath = Images.Media.insertImage(mContext.getContentResolver(), bmp, null, null);
            cropIntent.setDataAndType(Uri.parse(mPath), "image/*");
            cropIntent.putExtra("crop", "true");
            cropIntent.putExtra("scale", true);
            cropIntent.putExtra("scaleUpIfNeeded", false);

            final Display display = getWindowManager().getDefaultDisplay();

            boolean isPortrait = getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_PORTRAIT;

            Point size = new Point();
            display.getSize(size);

            cropIntent.putExtra("aspectX", isPortrait ? size.x : size.y);
            cropIntent.putExtra("aspectY", isPortrait ? size.y : size.x);
            cropIntent.putExtra("outputFormat", Bitmap.CompressFormat.PNG.toString());
            cropIntent.putExtra("return-data", false);

            cropIntent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(mWallpaperTemporary));

            startActivityForResult(cropIntent, REQUEST_CODE_CROP_WALLPAPER);
        }
        catch (ActivityNotFoundException e) {
            // activity not found
        }
    }

    private Drawable blurBitmap(Drawable image, int radius) {
        Bitmap bmp = scaleToFitWidth(((BitmapDrawable)image).getBitmap());
        Bitmap out = Bitmap.createBitmap(bmp);

        RenderScript rs = RenderScript.create(mContext);

        Allocation input = Allocation.createFromBitmap(
                rs, bmp, MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
        Allocation output = Allocation.createTyped(rs, input.getType());

        ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
        script.setInput(input);
        script.setRadius(radius < 1 ? 1 : radius);
        script.forEach(output);

        output.copyTo(out);

        rs.destroy();

        Drawable blurred = new BitmapDrawable(getResources(), out);

        return blurred;
    }

    private Drawable getCurrentWallpaper() {
        Drawable wallpaperDrawable = null;
        if (mSavedLockscreen.exists()) {
            Bitmap bmp = BitmapFactory.decodeFile(mSavedLockscreen.getAbsolutePath());
            wallpaperDrawable = new BitmapDrawable(getResources(), bmp);
        } else {
            WallpaperManager wallpaperManager = WallpaperManager.getInstance(this);
            wallpaperDrawable = wallpaperManager.getDrawable();
        }
        return wallpaperDrawable;
    }

    private Drawable getPreview() {
        if (!mWallpaperTemporary.exists()) return getCurrentWallpaper();

        Bitmap bmp = BitmapFactory.decodeFile(mWallpaperTemporary.getAbsolutePath());
        Drawable bg = new BitmapDrawable(getResources(), bmp);
        return  bg == null ? getCurrentWallpaper() : bg;
    }

    private OnSeekBarChangeListener mSeekBarListener = new OnSeekBarChangeListener() {
        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            mBlurredImage = blurBitmap(getPreview(), seekBar.getProgress());
            if (mWallpaperTemporary.exists()) {
                mPreview.setBackground(mBlurredImage);
            } else {
                mPreview.setBackground(null);
                mBackground.setBackground(mBlurredImage);
                resizeLayout(mBlurredImage);
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onProgressChanged(SeekBar seekBark, int progress, boolean fromUser) {
            mBlurLevelUpdated = true;
            String title = getResources().getString(R.string.blur_radius);
            mDialog.setTitle(title + ": " + Integer.toString(progress));
        }
    };

    protected void onActivityResult(int requestCode, int resultCode,
            Intent imageReturnedIntent) {
        if (requestCode == REQUEST_CODE_BG_WALLPAPER) {
            if (resultCode == Activity.RESULT_OK) {
                mPreview.setBackground(getPreview());
                actionBar.show();
                reset();
            }
        } else if (requestCode == REQUEST_CODE_CROP_WALLPAPER) {
            if (resultCode == Activity.RESULT_OK) {
                mContext.getContentResolver().delete(Uri.parse(mPath), null, null);
                Bitmap bmp = BitmapFactory.decodeFile(mWallpaperTemporary.getAbsolutePath());
                mPreview.setBackground(new BitmapDrawable(getResources(), bmp));
                loadBlurDialog();
            }
        }
    }

    public void reset() {
        mBlurredImage = null;
        mBlurLevelUpdated = false;
        if (sBar != null) sBar.setProgress(DEFAULT_BLUR);
    }

    public void moveFile(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);

        // Transfer bytes from in to out
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
    }

    static public Bitmap scaleToFitWidth(Bitmap b) {
        int scaledWidth = (int)Math.round(b.getWidth() / 4) * 4;
        float factor = b.getWidth() / scaledWidth;
        return Bitmap.createScaledBitmap(b, scaledWidth, (int) (b.getHeight() * factor), false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        reset();
    }
}
