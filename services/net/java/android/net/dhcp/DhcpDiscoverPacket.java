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

package android.net.dhcp;

import java.net.Inet4Address;
import java.nio.ByteBuffer;

/**
 * This class implements the DHCP-DISCOVER packet.
 */
class DhcpDiscoverPacket extends DhcpPacket {
    /**
     * Generates a DISCOVER packet with the specified parameters.
     */
    DhcpDiscoverPacket(int transId, short secs, byte[] clientMac, boolean broadcast) {
        super(transId, secs, INADDR_ANY, INADDR_ANY, INADDR_ANY, INADDR_ANY, clientMac, broadcast);
    }
    DhcpDiscoverPacket(int transId, short secs, byte[] clientMac, boolean broadcast,
                       boolean rapidCommit) {
        super(transId, secs, INADDR_ANY, INADDR_ANY, INADDR_ANY, INADDR_ANY,
              clientMac, broadcast, rapidCommit);
    }

    public String toString() {
        String s = super.toString();
        return s + " DISCOVER " +
                (mBroadcast ? "broadcast " : "unicast ");
    }

    /**
     * Fills in a packet with the requested DISCOVER parameters.
     */
    public ByteBuffer buildPacket(int encap, short destUdp, short srcUdp) {
        ByteBuffer result = ByteBuffer.allocate(MAX_LENGTH);
        fillInPacket(encap, INADDR_BROADCAST, INADDR_ANY, destUdp,
                srcUdp, result, DHCP_BOOTREQUEST, mBroadcast);
        result.flip();
        return result;
    }

    /**
     * Adds optional parameters to a DISCOVER packet.
     */
    void finishPacket(ByteBuffer buffer) {
        addTlv(buffer, DHCP_MESSAGE_TYPE, DHCP_MESSAGE_TYPE_DISCOVER);
        addTlv(buffer, DHCP_CLIENT_IDENTIFIER, getClientId());
        addCommonClientTlvs(buffer);
        addTlv(buffer, DHCP_PARAMETER_LIST, mRequestedParams);
        if (mRapidCommit) {
            addTlv(buffer, DHCP_OPTION_RAPID_COMMIT);
        }
        addTlvEnd(buffer);
    }
}
