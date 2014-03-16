/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.net;

import android.net.NetworkStats;
import android.net.NetworkStatsHistory;
import android.net.NetworkTemplate;

/** {@hide} */
interface INetworkStatsSession {

    /** Return network layer usage summary for traffic that matches template. */
    NetworkStats getSummaryForNetwork(in NetworkTemplate template, long start, long end);
    /** Return historical network layer stats for traffic that matches template. */
    NetworkStatsHistory getHistoryForNetwork(in NetworkTemplate template, int fields);

    /** Return network layer usage summary per UID for traffic that matches template. */
    NetworkStats getSummaryForAllUid(in NetworkTemplate template, long start, long end, boolean includeTags);
    /** Return historical network layer stats for specific UID traffic that matches template. */
    NetworkStatsHistory getHistoryForUid(in NetworkTemplate template, int uid, int set, int tag, int fields);

    void close();

}
