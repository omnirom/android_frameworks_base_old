/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.os.storage;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.os.Environment;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.util.Log;
import android.os.Environment;
import android.os.FileUtils;
import android.os.storage.OnObbStateChangeListener;
import android.os.storage.StorageManager;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.InputStream;
import java.io.IOException;
import java.io.StringReader;

public class StorageManagerBaseTest extends InstrumentationTestCase {

    protected Context mContext = null;
    protected StorageManager mSm = null;
    private static String LOG_TAG = "StorageManagerBaseTest";
    protected static final long MAX_WAIT_TIME = 120*1000;
    protected static final long WAIT_TIME_INCR = 5*1000;
    protected static String OBB_FILE_1 = "obb_file1.obb";
    protected static String OBB_FILE_1_CONTENTS_1 = "OneToOneThousandInts.bin";
    protected static String OBB_FILE_2 = "obb_file2.obb";
    protected static String OBB_FILE_3 = "obb_file3.obb";
    protected static String OBB_FILE_1_PASSWORD = "password1";
    protected static String OBB_FILE_1_ENCRYPTED = "obb_enc_file100_orig1.obb";
    protected static String OBB_FILE_2_UNSIGNED = "obb_file2_nosign.obb";
    protected static String OBB_FILE_3_PASSWORD = "password3";
    protected static String OBB_FILE_3_ENCRYPTED = "obb_enc_file100_orig3.obb";
    protected static String OBB_FILE_3_BAD_PACKAGENAME = "obb_file3_bad_packagename.obb";

    protected static boolean FORCE = true;
    protected static boolean DONT_FORCE = false;

    private static final String SAMPLE1_TEXT = "This is sample text.\n\nTesting 1 2 3.";

    private static final String SAMPLE2_TEXT =
        "We the people of the United States, in order to form a more perfect union,\n"
        + "establish justice, insure domestic tranquility, provide for the common\n"
        + "defense, promote the general welfare, and secure the blessings of liberty\n"
        + "to ourselves and our posterity, do ordain and establish this Constitution\n"
        + "for the United States of America.\n\n";

    class MountingObbThread extends Thread {
        boolean mStop = false;
        volatile boolean mFileOpenOnObb = false;
        private String mObbFilePath = null;
        private String mPathToContentsFile = null;
        private String mOfficialObbFilePath = null;

        /**
         * Constructor
         *
         * @param obbFilePath path to the OBB image file
         * @param pathToContentsFile path to a file on the mounted OBB volume to open after the OBB
         *      has been mounted
         */
        public MountingObbThread (String obbFilePath, String pathToContentsFile) {
            assertTrue("obbFilePath cannot be null!", obbFilePath != null);
            mObbFilePath = obbFilePath;
            assertTrue("path to contents file cannot be null!", pathToContentsFile != null);
            mPathToContentsFile = pathToContentsFile;
        }

        /**
         * Runs the thread
         *
         * Mounts OBB_FILE_1, and tries to open a file on the mounted OBB (specified in the
         * constructor). Once it's open, it waits until someone calls its doStop(), after which it
         * closes the opened file.
         */
        public void run() {
            // the official OBB file path and the mount-request file path should be the same, but
            // let's distinguish the two as they may make for some interesting tests later
            mOfficialObbFilePath = mountObb(mObbFilePath);
            assertEquals("Expected and actual OBB file paths differ!", mObbFilePath,
                    mOfficialObbFilePath);

            // open a file on OBB 1...
            DataInputStream inputFile = openFileOnMountedObb(mOfficialObbFilePath,
                    mPathToContentsFile);
            assertTrue("Failed to open file!", inputFile != null);

            synchronized (this) {
                mFileOpenOnObb = true;
                notifyAll();
            }

            while (!mStop) {
                try {
                    Thread.sleep(WAIT_TIME_INCR);
                } catch (InterruptedException e) {
                    // nothing special to be done for interruptions
                }
            }
            try {
                inputFile.close();
            } catch (IOException e) {
                fail("Failed to close file on OBB due to error: " + e.toString());
            }
        }

        /**
         * Tells whether a file has yet been successfully opened on the OBB or not
         *
         * @return true if the specified file on the OBB was opened; false otherwise
         */
        public boolean isFileOpenOnObb() {
            return mFileOpenOnObb;
        }

        /**
         * Returns the official path of the OBB file that was mounted
         *
         * This is not the mount path, but the normalized path to the actual OBB file
         *
         * @return a {@link String} representation of the path to the OBB file that was mounted
         */
        public String officialObbFilePath() {
            return mOfficialObbFilePath;
        }

        /**
         * Requests the thread to stop running
         *
         * Closes the opened file and returns
         */
        public void doStop() {
            mStop = true;
        }
    }

    public class ObbListener extends OnObbStateChangeListener {
        private String LOG_TAG = "StorageManagerBaseTest.ObbListener";

        String mOfficialPath = null;
        boolean mDone = false;
        int mState = -1;

        /**
         * {@inheritDoc}
         */
        @Override
        public void onObbStateChange(String path, int state) {
            Log.i(LOG_TAG, "Storage state changing to: " + state);

            synchronized (this) {
                Log.i(LOG_TAG, "OfficialPath is now: " + path);
                mState = state;
                mOfficialPath = path;
                mDone = true;
                notifyAll();
            }
        }

        /**
         * Tells whether we are done or not (system told us the OBB has changed state)
         *
         * @return true if the system has told us this OBB's state has changed, false otherwise
         */
        public boolean isDone() {
            return mDone;
        }

        /**
         * The last state of the OBB, according to the system
         *
         * @return A {@link String} representation of the state of the OBB
         */
        public int state() {
            return mState;
        }

        /**
         * The normalized, official path to the OBB file (according to the system)
         *
         * @return A {@link String} representation of the official path to the OBB file
         */
        public String officialPath() {
            return mOfficialPath;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUp() throws Exception {
        mContext = getInstrumentation().getContext();
        mSm = (StorageManager)mContext.getSystemService(android.content.Context.STORAGE_SERVICE);

    }

    /**
     * Helper to copy a raw resource file to an actual specified file
     *
     * @param rawResId The raw resource ID of the OBB resource file
     * @param outFile A File representing the file we want to copy the OBB to
     * @throws NotFoundException If the resource file could not be found
     */
    private void copyRawToFile(int rawResId, File outFile) throws NotFoundException {
        Resources res = mContext.getResources();
        InputStream is = null;
        try {
            is = res.openRawResource(rawResId);
        } catch (NotFoundException e) {
            Log.i(LOG_TAG, "Failed to load resource with id: " + rawResId);
            throw e;
        }
        FileUtils.setPermissions(outFile.getPath(), FileUtils.S_IRWXU | FileUtils.S_IRWXG
                | FileUtils.S_IRWXO, -1, -1);
        assertTrue(FileUtils.copyToFile(is, outFile));
        FileUtils.setPermissions(outFile.getPath(), FileUtils.S_IRWXU | FileUtils.S_IRWXG
                | FileUtils.S_IRWXO, -1, -1);
    }

    /**
     * Creates an OBB file (with the given name), into the app's standard files directory
     *
     * @param name The name of the OBB file we want to create/write to
     * @param rawResId The raw resource ID of the OBB file in the package
     * @return A {@link File} representing the file to write to
     */
    protected File createObbFile(String name, int rawResId) {
        File outFile = null;
        try {
            final File filesDir = mContext.getFilesDir();
            outFile = new File(filesDir, name);
            copyRawToFile(rawResId, outFile);
        } catch (NotFoundException e) {
            if (outFile != null) {
                outFile.delete();
            }
        }
        return outFile;
    }

    /**
     * Mounts an OBB file and opens a file located on it
     *
     * @param obbPath Path to OBB image
     * @param fileName The full name and path to the file on the OBB to open once the OBB is mounted
     * @return The {@link DataInputStream} representing the opened file, if successful in opening
     *      the file, or null of unsuccessful.
     */
    protected DataInputStream openFileOnMountedObb(String obbPath, String fileName) {

        // get mSm obb mount path
        assertTrue("Cannot open file when OBB is not mounted!", mSm.isObbMounted(obbPath));

        String path = mSm.getMountedObbPath(obbPath);
        assertTrue("Path should not be null!", path != null);

        File inFile = new File(path, fileName);
        DataInputStream inStream = null;
        try {
            inStream = new DataInputStream(new FileInputStream(inFile));
            Log.i(LOG_TAG, "Opened file: " + fileName + " for read at path: " + path);
        } catch (FileNotFoundException e) {
            Log.e(LOG_TAG, e.toString());
            return null;
        } catch (SecurityException e) {
            Log.e(LOG_TAG, e.toString());
            return null;
        }
        return inStream;
    }

    /**
     * Mounts an OBB file
     *
     * @param obbFilePath The full path to the OBB file to mount
     * @param key (optional) The key to use to unencrypt the OBB; pass null for no encryption
     * @param expectedState The expected state resulting from trying to mount the OBB
     * @return A {@link String} representing the normalized path to OBB file that was mounted
     */
    protected String mountObb(String obbFilePath, String key, int expectedState) {
        return doMountObb(obbFilePath, key, expectedState);
    }

    /**
     * Mounts an OBB file with default options (no encryption, mounting succeeds)
     *
     * @param obbFilePath The full path to the OBB file to mount
     * @return A {@link String} representing the normalized path to OBB file that was mounted
     */
    protected String mountObb(String obbFilePath) {
        return doMountObb(obbFilePath, null, OnObbStateChangeListener.MOUNTED);
    }

    /**
     * Synchronously waits for an OBB listener to be signaled of a state change, but does not throw
     *
     * @param obbListener The listener for the OBB file
     * @return true if the listener was signaled of a state change by the system, else returns
     *      false if we time out.
     */
    protected boolean doWaitForObbStateChange(ObbListener obbListener) {
        synchronized(obbListener) {
            long waitTimeMillis = 0;
            while (!obbListener.isDone()) {
                try {
                    Log.i(LOG_TAG, "Waiting for listener...");
                    obbListener.wait(WAIT_TIME_INCR);
                    Log.i(LOG_TAG, "Awoke from waiting for listener...");
                    waitTimeMillis += WAIT_TIME_INCR;
                    if (waitTimeMillis > MAX_WAIT_TIME) {
                        fail("Timed out waiting for OBB state to change!");
                    }
                } catch (InterruptedException e) {
                    Log.i(LOG_TAG, e.toString());
                }
            }
            return obbListener.isDone();
            }
    }

    /**
     * Synchronously waits for an OBB listener to be signaled of a state change
     *
     * @param obbListener The listener for the OBB file
     * @return true if the listener was signaled of a state change by the system; else a fail()
     *      is triggered if we timed out
     */
    protected String doMountObb_noThrow(String obbFilePath, String key, int expectedState) {
        Log.i(LOG_TAG, "doMountObb() on " + obbFilePath + " using key: " + key);
        assertTrue ("Null path was passed in for OBB file!", obbFilePath != null);
        assertTrue ("Null path was passed in for OBB file!", obbFilePath != null);

        ObbListener obbListener = new ObbListener();
        boolean success = mSm.mountObb(obbFilePath, key, obbListener);
        success &= obbFilePath.equals(doWaitForObbStateChange(obbListener));
        success &= (expectedState == obbListener.state());

        if (OnObbStateChangeListener.MOUNTED == expectedState) {
            success &= obbFilePath.equals(obbListener.officialPath());
            success &= mSm.isObbMounted(obbListener.officialPath());
        } else {
            success &= !mSm.isObbMounted(obbListener.officialPath());
        }

        if (success) {
            return obbListener.officialPath();
        } else {
            return null;
        }
    }

    /**
     * Mounts an OBB file without throwing and synchronously waits for it to finish mounting
     *
     * @param obbFilePath The full path to the OBB file to mount
     * @param key (optional) The key to use to unencrypt the OBB; pass null for no encryption
     * @param expectedState The expected state resulting from trying to mount the OBB
     * @return A {@link String} representing the actual normalized path to OBB file that was
     *      mounted, or null if the mounting failed
     */
    protected String doMountObb(String obbFilePath, String key, int expectedState) {
        Log.i(LOG_TAG, "doMountObb() on " + obbFilePath + " using key: " + key);
        assertTrue ("Null path was passed in for OBB file!", obbFilePath != null);

        ObbListener obbListener = new ObbListener();
        assertTrue("mountObb call failed", mSm.mountObb(obbFilePath, key, obbListener));
        assertTrue("Failed to get OBB mount status change for file: " + obbFilePath,
                doWaitForObbStateChange(obbListener));
        assertEquals("OBB mount state not what was expected!", expectedState, obbListener.state());

        if (OnObbStateChangeListener.MOUNTED == expectedState) {
            assertEquals(obbFilePath, obbListener.officialPath());
            assertTrue("Obb should be mounted, but SM reports it is not!",
                    mSm.isObbMounted(obbListener.officialPath()));
        } else if (OnObbStateChangeListener.UNMOUNTED == expectedState) {
            assertFalse("Obb should not be mounted, but SM reports it is!",
                    mSm.isObbMounted(obbListener.officialPath()));
        }

        assertEquals("Mount state is not what was expected!", expectedState, obbListener.state());
        return obbListener.officialPath();
    }

    /**
     * Unmounts an OBB file without throwing, and synchronously waits for it to finish unmounting
     *
     * @param obbFilePath The full path to the OBB file to mount
     * @param force true if we shuold force the unmount, false otherwise
     * @return true if the unmount was successful, false otherwise
     */
    protected boolean unmountObb_noThrow(String obbFilePath, boolean force) {
        Log.i(LOG_TAG, "doUnmountObb_noThrow() on " + obbFilePath);
        assertTrue ("Null path was passed in for OBB file!", obbFilePath != null);
        boolean success = true;

        ObbListener obbListener = new ObbListener();
        assertTrue("unmountObb call failed", mSm.unmountObb(obbFilePath, force, obbListener));

        boolean stateChanged = doWaitForObbStateChange(obbListener);
        if (force) {
            success &= stateChanged;
            success &= (OnObbStateChangeListener.UNMOUNTED == obbListener.state());
            success &= !mSm.isObbMounted(obbFilePath);
        }
        return success;
    }

    /**
     * Unmounts an OBB file and synchronously waits for it to finish unmounting
     *
     * @param obbFilePath The full path to the OBB file to mount
     * @param force true if we shuold force the unmount, false otherwise
     */
    protected void unmountObb(String obbFilePath, boolean force) {
        Log.i(LOG_TAG, "doUnmountObb() on " + obbFilePath);
        assertTrue ("Null path was passed in for OBB file!", obbFilePath != null);

        ObbListener obbListener = new ObbListener();
        assertTrue("unmountObb call failed", mSm.unmountObb(obbFilePath, force, obbListener));

        boolean stateChanged = doWaitForObbStateChange(obbListener);
        if (force) {
            assertTrue("Timed out waiting to unmount OBB file " + obbFilePath, stateChanged);
            assertEquals("OBB failed to unmount", OnObbStateChangeListener.UNMOUNTED,
                    obbListener.state());
            assertFalse("Obb should NOT be mounted, but SM reports it is!", mSm.isObbMounted(
                    obbFilePath));
        }
    }

    /**
     * Helper to validate the contents of an "int" file in an OBB.
     *
     * The format of the files are sequential int's, in the range of: [start..end)
     *
     * @param path The full path to the file (path to OBB)
     * @param filename The filename containing the ints to validate
     * @param start The first int expected to be found in the file
     * @param end The last int + 1 expected to be found in the file
     */
    protected void doValidateIntContents(String path, String filename, int start, int end) {
        File inFile = new File(path, filename);
        DataInputStream inStream = null;
        Log.i(LOG_TAG, "Validating file " + filename + " at " + path);
        try {
            inStream = new DataInputStream(new FileInputStream(inFile));

            for (int i = start; i < end; ++i) {
                if (inStream.readInt() != i) {
                    fail("Unexpected value read in OBB file");
                }
            }
            if (inStream != null) {
                inStream.close();
            }
            Log.i(LOG_TAG, "Successfully validated file " + filename);
        } catch (FileNotFoundException e) {
            fail("File " + inFile + " not found: " + e.toString());
        } catch (IOException e) {
            fail("IOError with file " + inFile + ":" + e.toString());
        }
    }

    /**
     * Helper to validate the contents of a text file in an OBB
     *
     * @param path The full path to the file (path to OBB)
     * @param filename The filename containing the ints to validate
     * @param contents A {@link String} containing the expected contents of the file
     */
    protected void doValidateTextContents(String path, String filename, String contents) {
        File inFile = new File(path, filename);
        BufferedReader fileReader = null;
        BufferedReader textReader = null;
        Log.i(LOG_TAG, "Validating file " + filename + " at " + path);
        try {
            fileReader = new BufferedReader(new FileReader(inFile));
            textReader = new BufferedReader(new StringReader(contents));
            String actual = null;
            String expected = null;
            while ((actual = fileReader.readLine()) != null) {
                expected = textReader.readLine();
                if (!actual.equals(expected)) {
                    fail("File " + filename + " in OBB " + path + " does not match expected value");
                }
            }
            fileReader.close();
            textReader.close();
            Log.i(LOG_TAG, "File " + filename + " successfully verified.");
        } catch (IOException e) {
            fail("IOError with file " + inFile + ":" + e.toString());
        }
    }

    /**
     * Helper to validate the contents of a "long" file on our OBBs
     *
     * The format of the files are sequential 0's of type long
     *
     * @param path The full path to the file (path to OBB)
     * @param filename The filename containing the ints to validate
     * @param size The number of zero's expected in the file
     * @param checkContents If true, the contents of the file are actually verified; if false,
     *      we simply verify that the file can be opened
     */
    protected void doValidateZeroLongFile(String path, String filename, long size,
            boolean checkContents) {
        File inFile = new File(path, filename);
        DataInputStream inStream = null;
        Log.i(LOG_TAG, "Validating file " + filename + " at " + path);
        try {
            inStream = new DataInputStream(new FileInputStream(inFile));

            if (checkContents) {
                for (long i = 0; i < size; ++i) {
                    if (inStream.readLong() != 0) {
                        fail("Unexpected value read in OBB file" + filename);
                    }
                }
            }

            if (inStream != null) {
                inStream.close();
            }
            Log.i(LOG_TAG, "File " + filename + " successfully verified for " + size + " zeros");
        } catch (IOException e) {
            fail("IOError with file " + inFile + ":" + e.toString());
        }
    }

    /**
     * Helper to synchronously wait until we can get a path for a given OBB file
     *
     * @param filePath The full normalized path to the OBB file
     * @return The mounted path of the OBB, used to access contents in it
     */
    protected String doWaitForPath(String filePath) {
        String path = null;

        long waitTimeMillis = 0;
        assertTrue("OBB " + filePath + " is not currently mounted!", mSm.isObbMounted(filePath));
        while (path == null) {
            try {
                Thread.sleep(WAIT_TIME_INCR);
                waitTimeMillis += WAIT_TIME_INCR;
                if (waitTimeMillis > MAX_WAIT_TIME) {
                    fail("Timed out waiting to get path of OBB file " + filePath);
                }
            } catch (InterruptedException e) {
                // do nothing
            }
            path = mSm.getMountedObbPath(filePath);
        }
        Log.i(LOG_TAG, "Got OBB path: " + path);
        return path;
    }

    /**
     * Verifies the pre-defined contents of our first OBB (OBB_FILE_1)
     *
     * The OBB contains 4 files and no subdirectories
     *
     * @param filePath The normalized path to the already-mounted OBB file
     */
    protected void verifyObb1Contents(String filePath) {
        String path = null;
        path = doWaitForPath(filePath);

        // Validate contents of 2 files in this obb
        doValidateIntContents(path, "OneToOneThousandInts.bin", 0, 1000);
        doValidateIntContents(path, "SevenHundredInts.bin", 0, 700);
        doValidateZeroLongFile(path, "FiveLongs.bin", 5, true);
    }

    /**
     * Verifies the pre-defined contents of our second OBB (OBB_FILE_2)
     *
     * The OBB contains 2 files and no subdirectories
     *
     * @param filePath The normalized path to the already-mounted OBB file
     */
    protected void verifyObb2Contents(String filename) {
        String path = null;
        path = doWaitForPath(filename);

        // Validate contents of file
        doValidateTextContents(path, "sample.txt", SAMPLE1_TEXT);
        doValidateTextContents(path, "sample2.txt", SAMPLE2_TEXT);
    }

    /**
     * Verifies the pre-defined contents of our third OBB (OBB_FILE_3)
     *
     * The OBB contains nested files and subdirectories
     *
     * @param filePath The normalized path to the already-mounted OBB file
     */
    protected void verifyObb3Contents(String filename) {
        String path = null;
        path = doWaitForPath(filename);

        // Validate contents of file
        doValidateIntContents(path, "OneToOneThousandInts.bin", 0, 1000);
        doValidateZeroLongFile(path, "TwoHundredLongs", 200, true);

        // validate subdirectory 1
        doValidateZeroLongFile(path + File.separator + "subdir1", "FiftyLongs", 50, true);

        // validate subdirectory subdir2/
        doValidateIntContents(path + File.separator + "subdir2", "OneToOneThousandInts", 0, 1000);

        // validate subdirectory subdir2/subdir2a/
        doValidateZeroLongFile(path + File.separator + "subdir2" + File.separator + "subdir2a",
                "TwoHundredLongs", 200, true);

        // validate subdirectory subdir2/subdir2a/subdir2a1/
        doValidateIntContents(path + File.separator + "subdir2" + File.separator + "subdir2a"
                + File.separator + "subdir2a1", "OneToOneThousandInts", 0, 1000);
    }
}