/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.mediaframeworktest.stress;

import com.android.mediaframeworktest.MediaFrameworkTest;
import com.android.mediaframeworktest.MediaPlayerStressTestRunner;

import android.os.Bundle;
import android.os.Environment;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

import com.android.mediaframeworktest.MediaNames;
import com.android.mediaframeworktest.functional.CodecTest;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;

/**
 * Junit / Instrumentation test case for the media player
 */
public class MediaPlayerStressTest extends ActivityInstrumentationTestCase2<MediaFrameworkTest> {
    private String TAG = "MediaPlayerStressTest";
    private String mMediaSrc;

    public MediaPlayerStressTest() {
        super("com.android.mediaframeworktest", MediaFrameworkTest.class);
    }

    protected void setUp() throws Exception {
        //Insert a 2 second before launching the test activity. This is
        //the workaround for the race condition of requesting the updated surface.
        Thread.sleep(2000);
        getActivity();
        MediaPlayerStressTestRunner mRunner = (MediaPlayerStressTestRunner)getInstrumentation();
        Bundle arguments = mRunner.getArguments();
        mMediaSrc = arguments.getString("media-source");
        if (mMediaSrc == null) {
            mMediaSrc = MediaNames.MEDIA_SAMPLE_POOL;
        }
        super.setUp();
    }

    private int mTotalPlaybackError = 0;
    private int mTotalComplete = 0;
    private int mTotalInfoUnknown = 0;
    private int mTotalVideoTrackLagging = 0;
    private int mTotalBadInterleaving = 0;
    private int mTotalNotSeekable = 0;
    private int mTotalMetaDataUpdate = 0;

    //Test result output file
    private static final String PLAYBACK_RESULT = "PlaybackTestResult.txt";

    private void writeTestOutput(String filename, Writer output) throws Exception{
        output.write("File Name: " + filename);
        output.write(" Complete: " + CodecTest.onCompleteSuccess);
        output.write(" Error: " + CodecTest.mPlaybackError);
        output.write(" Unknown Info: " + CodecTest.mMediaInfoUnknownCount);
        output.write(" Track Lagging: " +  CodecTest.mMediaInfoVideoTrackLaggingCount);
        output.write(" Bad Interleaving: " + CodecTest.mMediaInfoBadInterleavingCount);
        output.write(" Not Seekable: " + CodecTest.mMediaInfoNotSeekableCount);
        output.write(" Info Meta data update: " + CodecTest.mMediaInfoMetdataUpdateCount);
        output.write("\n");
    }

    private void writeTestSummary(Writer output) throws Exception{
        output.write("Total Result:\n");
        output.write("Total Complete: " + mTotalComplete + "\n");
        output.write("Total Error: " + mTotalPlaybackError + "\n");
        output.write("Total Unknown Info: " + mTotalInfoUnknown + "\n");
        output.write("Total Track Lagging: " + mTotalVideoTrackLagging + "\n" );
        output.write("Total Bad Interleaving: " + mTotalBadInterleaving + "\n");
        output.write("Total Not Seekable: " + mTotalNotSeekable + "\n");
        output.write("Total Info Meta data update: " + mTotalMetaDataUpdate + "\n");
        output.write("\n");
    }

    private void updateTestResult(){
        if (CodecTest.onCompleteSuccess){
            mTotalComplete++;
        }
        else if (CodecTest.mPlaybackError){
            mTotalPlaybackError++;
        }
        mTotalInfoUnknown += CodecTest.mMediaInfoUnknownCount;
        mTotalVideoTrackLagging += CodecTest.mMediaInfoVideoTrackLaggingCount;
        mTotalBadInterleaving += CodecTest.mMediaInfoBadInterleavingCount;
        mTotalNotSeekable += CodecTest.mMediaInfoNotSeekableCount;
        mTotalMetaDataUpdate += CodecTest.mMediaInfoMetdataUpdateCount;
    }

    //Test that will start the playback for all the videos
    //under the samples folder
    @LargeTest
    public void testVideoPlayback() throws Exception {
        String fileWithError = "Filename:\n";
        File playbackOutput = new File(Environment.getExternalStorageDirectory(), PLAYBACK_RESULT);
        Writer output = new BufferedWriter(new FileWriter(playbackOutput, true));

        boolean testResult = true;
        // load directory files
        boolean onCompleteSuccess = false;
        String[] children = MediaNames.NETWORK_VIDEO_FILES;
        if (MediaNames.MEDIA_SAMPLE_POOL.equals(mMediaSrc)) {
            File dir = new File(mMediaSrc);
            children = dir.list();
        }
        if (children == null) {
            Log.v("MediaPlayerApiTest:testMediaSamples", "dir is empty");
            return;
        } else {
            for (int i = 0; i < children.length; i++) {
                //Get filename
                String filename = children[i];
                onCompleteSuccess =
                    CodecTest.playMediaSamples(mMediaSrc + filename);
                if (!onCompleteSuccess){
                    //Don't fail the test right away, print out the failure file.
                    fileWithError += filename + '\n';
                    Log.v(TAG, "Failure File : " + fileWithError);
                    testResult = false;
                }
                Thread.sleep(3000);
                //Write test result to an output file
                writeTestOutput(filename,output);
                //Get the summary
                updateTestResult();
            }
            writeTestSummary(output);
            output.close();
            assertTrue("testMediaSamples", testResult);
       }
    }
}