/*
 * Copyright (C) 2011-2012 The Android Open Source Project
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

package com.android.testapp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import com.android.scenegraph.*;
import com.android.scenegraph.SceneManager.SceneLoadedCallback;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.renderscript.*;
import android.renderscript.Program.TextureType;
import android.util.Log;

// This is where the scenegraph and the rendered objects are initialized and used
public class TestAppRS {

    private static String modelName = "orientation_test.dae";
    private static String TAG = "TestAppRS";
    private static String mFilePath = "";

    int mWidth;
    int mHeight;

    boolean mUseBlur;

    TestAppLoadingScreen mLoadingScreen;

    // Used to asynchronously load scene elements like meshes and transform hierarchies
    SceneLoadedCallback mLoadedCallback = new SceneLoadedCallback() {
        public void run() {
            prepareToRender(mLoadedScene);
        }
    };

    // Top level class that initializes all the elements needed to use the scene graph
    SceneManager mSceneManager;

    // Used to move the camera around in the 3D world
    TouchHandler mTouchHandler;

    private Resources mRes;
    private RenderScriptGL mRS;

    // Shaders
    private FragmentShader mPaintF;
    private FragmentShader mLightsF;
    private FragmentShader mLightsDiffF;
    private FragmentShader mAluminumF;
    private FragmentShader mPlasticF;
    private FragmentShader mDiffuseF;
    private FragmentShader mTextureF;
    private VertexShader mGenericV;

    Scene mActiveScene;

    // This is a part of the test app, it's used to tests multiple render passes and is toggled
    // on and off in the menu, off by default
    void toggleBlur() {
        mUseBlur = !mUseBlur;

        mActiveScene.clearRenderPasses();
        initRenderPasses();
        mActiveScene.initRenderPassRS(mRS, mSceneManager);

        // This is just a hardcoded object in the scene that gets turned on and off for the demo
        // to make things look a bit better. This could be deleted in the cleanup
        Renderable plane = (Renderable)mActiveScene.getRenderableByName("pPlaneShape1");
        if (plane != null) {
            plane.setVisible(!mUseBlur);
        }
    }

    public void init(RenderScriptGL rs, Resources res, int width, int height) {
        mUseBlur = false;
        mRS = rs;
        mRes = res;
        mWidth = width;
        mHeight = height;

        mTouchHandler = new TouchHandler();

        mSceneManager = SceneManager.getInstance();
        // Initializes all the RS specific scenegraph elements
        mSceneManager.initRS(mRS, mRes, mWidth, mHeight);

        mLoadingScreen = new TestAppLoadingScreen(mRS, mRes);

        // Initi renderscript stuff specific to the app. This will need to be abstracted out later.
        FullscreenBlur.createRenderTargets(mRS, mWidth, mHeight);
        initPaintShaders();

        // Load a scene to render
        mSceneManager.loadModel(mFilePath + modelName, mLoadedCallback);
    }

    // When a new model file is selected from the UI, this function gets called to init everything
    void loadModel(String path) {
        mLoadingScreen.showLoadingScreen(true);
        mActiveScene.destroyRS();
        mSceneManager.loadModel(path, mLoadedCallback);
    }

    public void onActionDown(float x, float y) {
        mTouchHandler.onActionDown(x, y);
    }

    public void onActionScale(float scale) {
        mTouchHandler.onActionScale(scale);
    }

    public void onActionMove(float x, float y) {
        mTouchHandler.onActionMove(x, y);
    }

    FragmentShader createFromResource(int id, boolean addCubemap, Type constType) {
        FragmentShader.Builder fb = new FragmentShader.Builder(mRS);
        fb.setShaderConst(constType);
        fb.setShader(mRes, id);
        fb.addTexture(TextureType.TEXTURE_2D, "diffuse");
        if (addCubemap) {
            fb.addShaderTexture(TextureType.TEXTURE_CUBE, "reflection");
        }
        FragmentShader pf = fb.create();
        pf.getProgram().bindSampler(Sampler.WRAP_LINEAR_MIP_LINEAR(mRS), 0);
        if (addCubemap) {
            pf.getProgram().bindSampler(Sampler.CLAMP_LINEAR_MIP_LINEAR(mRS), 1);
        }
        return pf;
    }

    private void initPaintShaders() {
        mGenericV = SceneManager.getDefaultVS();

        ScriptField_CameraParams camParams = new ScriptField_CameraParams(mRS, 1);
        Type camParamType = camParams.getAllocation().getType();
        ScriptField_LightParams lightParams = new ScriptField_LightParams(mRS, 1);

        mPaintF = createFromResource(R.raw.paintf, true, camParamType);
        // Assign a reflection map
        TextureCube envCube = new TextureCube("sdcard/scenegraph/", "cube_env.png");
        mPaintF.appendSourceParams(new TextureParam("reflection", envCube));

        mAluminumF = createFromResource(R.raw.metal, true, camParamType);
        TextureCube diffCube = new TextureCube("sdcard/scenegraph/", "cube_spec.png");
        mAluminumF.appendSourceParams(new TextureParam("reflection", diffCube));

        mPlasticF = createFromResource(R.raw.plastic, false, camParamType);
        mDiffuseF = createFromResource(R.raw.diffuse, false, camParamType);
        mTextureF = SceneManager.getTextureFS();

        FragmentShader.Builder fb = new FragmentShader.Builder(mRS);
        fb.setObjectConst(lightParams.getAllocation().getType());
        fb.setShader(mRes, R.raw.plastic_lights);
        mLightsF = fb.create();

        fb = new FragmentShader.Builder(mRS);
        fb.setObjectConst(lightParams.getAllocation().getType());
        fb.setShader(mRes, R.raw.diffuse_lights);
        mLightsDiffF = fb.create();

        FullscreenBlur.initShaders(mRes, mRS);
    }

    void initRenderPasses() {
        ArrayList<RenderableBase> allDraw = mActiveScene.getRenderables();
        int numDraw = allDraw.size();

        if (mUseBlur) {
            FullscreenBlur.addBlurPasses(mActiveScene, mRS, mTouchHandler.getCamera());
        }

        RenderPass mainPass = new RenderPass();
        mainPass.setClearColor(new Float4(1.0f, 1.0f, 1.0f, 1.0f));
        mainPass.setShouldClearColor(true);
        mainPass.setClearDepth(1.0f);
        mainPass.setShouldClearDepth(true);
        mainPass.setCamera(mTouchHandler.getCamera());
        for (int i = 0; i < numDraw; i ++) {
            mainPass.appendRenderable((Renderable)allDraw.get(i));
        }
        mActiveScene.appendRenderPass(mainPass);

        if (mUseBlur) {
            FullscreenBlur.addCompositePass(mActiveScene, mRS, mTouchHandler.getCamera());
        }
    }

    private void addShadersToScene() {
        mActiveScene.appendShader(mPaintF);
        mActiveScene.appendShader(mLightsF);
        mActiveScene.appendShader(mLightsDiffF);
        mActiveScene.appendShader(mAluminumF);
        mActiveScene.appendShader(mPlasticF);
        mActiveScene.appendShader(mDiffuseF);
        mActiveScene.appendShader(mTextureF);
    }

    public void prepareToRender(Scene s) {
        mSceneManager.setActiveScene(s);
        mActiveScene = s;
        mTouchHandler.init(mActiveScene);
        addShadersToScene();
        RenderState plastic = new RenderState(mGenericV, mPlasticF, null, null);
        RenderState diffuse = new RenderState(mGenericV, mDiffuseF, null, null);
        RenderState paint = new RenderState(mGenericV, mPaintF, null, null);
        RenderState aluminum = new RenderState(mGenericV, mAluminumF, null, null);
        RenderState lights = new RenderState(mGenericV, mLightsF, null, null);
        RenderState diff_lights = new RenderState(mGenericV, mLightsDiffF, null, null);
        RenderState diff_lights_no_cull = new RenderState(mGenericV, mLightsDiffF, null,
                                                          ProgramRaster.CULL_NONE(mRS));
        RenderState glassTransp = new RenderState(mGenericV, mPaintF,
                                                  ProgramStore.BLEND_ALPHA_DEPTH_TEST(mRS), null);
        RenderState texState = new RenderState(mGenericV, mTextureF, null, null);

        initRenderPasses();

        mActiveScene.assignRenderState(plastic);

        mActiveScene.assignRenderStateToMaterial(diffuse, "lambert2$");

        mActiveScene.assignRenderStateToMaterial(paint, "^Paint");
        mActiveScene.assignRenderStateToMaterial(paint, "^Carbon");
        mActiveScene.assignRenderStateToMaterial(paint, "^Glass");
        mActiveScene.assignRenderStateToMaterial(paint, "^MainGlass");

        mActiveScene.assignRenderStateToMaterial(aluminum, "^Metal");
        mActiveScene.assignRenderStateToMaterial(aluminum, "^Brake");

        mActiveScene.assignRenderStateToMaterial(glassTransp, "^GlassLight");

        mActiveScene.assignRenderStateToMaterial(lights, "^LightBlinn");
        mActiveScene.assignRenderStateToMaterial(diff_lights, "^LightLambert");
        mActiveScene.assignRenderStateToMaterial(diff_lights_no_cull, "^LightLambertNoCull");
        mActiveScene.assignRenderStateToMaterial(texState, "^TextureOnly");

        Renderable plane = (Renderable)mActiveScene.getRenderableByName("pPlaneShape1");
        if (plane != null) {
            plane.setRenderState(texState);
            plane.setVisible(!mUseBlur);
        }

        long start = System.currentTimeMillis();
        mActiveScene.initRS();
        long end = System.currentTimeMillis();
        Log.v("TIMER", "Scene init time: " + (end - start));

        mLoadingScreen.showLoadingScreen(false);
    }
}
