/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.transitiontests;

import android.app.Activity;
import android.os.Bundle;
import android.transition.ChangeBounds;
import android.view.View;
import android.view.ViewGroup;
import android.transition.Fade;
import android.transition.Recolor;
import android.transition.Scene;
import android.transition.TransitionSet;
import android.transition.TransitionManager;


public class ScenesTestv21 extends Activity {
    ViewGroup mSceneRoot;
    static Scene mCurrentScene;
    TransitionManager mTransitionManager = null;
    Scene mResultsScreen, mSearchScreen;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.search_screen);

        View container = (View) findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

        mSearchScreen = Scene.getSceneForLayout(mSceneRoot, R.layout.search_screen, this);
        mResultsScreen = Scene.getSceneForLayout(mSceneRoot, R.layout.results_screen, this);

        TransitionSet transitionToResults = new TransitionSet();
        Fade fade = new Fade();
        fade.addTarget(R.id.resultsText).addTarget(R.id.resultsList);
        fade.setStartDelay(300);
        transitionToResults.addTransition(fade);
        transitionToResults.addTransition(new ChangeBounds().addTarget(R.id.searchContainer));
        transitionToResults.addTransition(new Recolor().addTarget(R.id.container));

        TransitionSet transitionToSearch = new TransitionSet();
        transitionToSearch.addTransition(new Fade().addTarget(R.id.resultsText).
                addTarget(R.id.resultsList));
        transitionToSearch.addTransition(new ChangeBounds().addTarget(R.id.searchContainer));
        transitionToSearch.addTransition(new Recolor().addTarget(R.id.container));
        mTransitionManager = new TransitionManager();
        mTransitionManager.setTransition(mSearchScreen, transitionToSearch);
        mTransitionManager.setTransition(mResultsScreen, transitionToResults);
    }

    public void sendMessage(View view) {
        if (mCurrentScene == mResultsScreen) {
            mTransitionManager.transitionTo(mSearchScreen);
            mCurrentScene = mSearchScreen;
        } else {
            mTransitionManager.transitionTo(mResultsScreen);
            mCurrentScene = mResultsScreen;
        }
    }
}
