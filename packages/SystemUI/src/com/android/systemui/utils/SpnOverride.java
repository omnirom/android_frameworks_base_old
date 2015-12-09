/*
 * Copyright (C) 2014 The MoKee OpenSource Project
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

package com.android.systemui.utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.os.Environment;
import android.telephony.Rlog;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

public class SpnOverride {
    private HashMap<String, String> mCarrierSpnMap;


    static final String LOG_TAG = "SpnOverride";
    static final String PARTNER_SPN_OVERRIDE_PATH ="etc/spn-conf.xml";

    public SpnOverride () {
        mCarrierSpnMap = new HashMap<String, String>();
        loadSpnOverrides();
    }

    public boolean containsCarrier(String carrier) {
        return mCarrierSpnMap.containsKey(carrier);
    }

    public String getSpn(String carrier) {
        return mCarrierSpnMap.get(carrier);
    }

    private void loadSpnOverrides() {
        FileReader spnReader;

        final File spnFile = new File(Environment.getRootDirectory(),
                PARTNER_SPN_OVERRIDE_PATH);

        try {
            spnReader = new FileReader(spnFile);
        } catch (FileNotFoundException e) {
            Rlog.w(LOG_TAG, "Can not open " +
                    Environment.getRootDirectory() + "/" + PARTNER_SPN_OVERRIDE_PATH);
            return;
        }

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(spnReader);

            XmlUtils.beginDocument(parser, "spnOverrides");

            while (true) {
                XmlUtils.nextElement(parser);

                String name = parser.getName();
                if (!"spnOverride".equals(name)) {
                    break;
                }

                String numeric = parser.getAttributeValue(null, "numeric");
                String data    = parser.getAttributeValue(null, "spn");

                mCarrierSpnMap.put(numeric, data);
            }
            spnReader.close();
        } catch (XmlPullParserException e) {
            Rlog.w(LOG_TAG, "Exception in spn-conf parser " + e);
        } catch (IOException e) {
            Rlog.w(LOG_TAG, "Exception in spn-conf parser " + e);
        }
    }

}
