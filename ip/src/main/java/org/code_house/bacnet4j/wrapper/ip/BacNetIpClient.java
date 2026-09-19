/*
 * (C) Copyright 2018 Code-House and others.
 *
 * bacnet4j-wrapper is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 *     https://www.gnu.org/licenses/gpl-3.0.txt
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Foobar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.code_house.bacnet4j.wrapper.ip;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.npdu.ip.IpNetwork;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkBuilder;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkUtils;
import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.bacnet4j.type.constructed.ReadAccessResult;
import com.serotonin.bacnet4j.type.constructed.ReadAccessResult.Result;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.code_house.bacnet4j.wrapper.api.BacNetClientBase;
import org.code_house.bacnet4j.wrapper.api.BacNetObject;
import org.code_house.bacnet4j.wrapper.api.Device;
import org.code_house.bacnet4j.wrapper.api.Type;

/**
 * Implementation of bacnet client based on IpNetwork/UDP transport.
 *
 * @author Łukasz Dywicki &lt;luke@code-house.org&gt;
 */
public class BacNetIpClient extends BacNetClientBase {

    public BacNetIpClient(IpNetwork network, int deviceId) {
        super(new LocalDevice(deviceId, new DefaultTransport(network)));
    }

    public BacNetIpClient(String ip, String broadcast, int port, int deviceId, boolean reuseAddress) {
        this(new IpNetworkBuilder().withLocalBindAddress(ip).withBroadcast(broadcast, 24).withPort(port)
            .withReuseAddress(reuseAddress).build(), deviceId);
    }

    public BacNetIpClient(String ip, String broadcast, int port, int deviceId) {
        this(new IpNetworkBuilder().withLocalBindAddress(ip).withBroadcast(broadcast, 24).withPort(port).build(), deviceId);
    }

    public BacNetIpClient(String broadcast, int port, int deviceId) {
        this(new IpNetworkBuilder().withBroadcast(broadcast, 24).withPort(port).build(), deviceId);
    }

    public BacNetIpClient(String ip, String broadcast, int deviceId) {
        this(new IpNetworkBuilder().withLocalBindAddress(ip).withBroadcast(broadcast, 24).build(), deviceId);
    }

    public BacNetIpClient(String broadcast, int deviceId) {
        this(new IpNetworkBuilder().withBroadcast(broadcast, 24).build(), deviceId);
    }

    /**
     * Explicitly register a network router which is known beforehand.
     *
     * This method is introduced for situations where network is fixed and all details are known to
     * caller. Adding network router early on allow to communicate other networks without pre-flight
     * discovery requests to populate routing table.
     *
     * @param network Network number.
     * @param ipAddress Router ip address.
     * @param port Router port.
     */
    public void addNetworkRouter(int network, String ipAddress, int port) {
        OctetString address = IpNetworkUtils.toOctetString(ipAddress, port);
        this.localDevice.getNetwork().getTransport().addNetworkRouter(network, address);
    }

    /**
     * Enable BBMD operation and install a static Broadcast Distribution Table (BDT).
     *
     * <p>The BACnet/IP socket itself is intentionally left unchanged. In particular, callers do
     * not need to bind BACnet4J to a concrete interface address. This preserves the working
     * wildcard-bind behaviour used for local BACnet broadcasts on Linux.</p>
     *
     * <p>BACnet4J 6.0.0 exposes BBMD operation but not a public BDT setter. The table is therefore
     * installed through the standard BVLC Write-Broadcast-Distribution-Table service sent to the
     * local BBMD socket.</p>
     *
     * @param localAddress IPv4 address by which this BBMD is reachable by other BBMDs.
     * @param port BACnet/IP UDP port of this BBMD.
     * @param peers remote BBMD peers. The local BBMD entry is added automatically.
     */
    public void enableBbmd(String localAddress, int port, List<BbmdEntry> peers) {
        validateIpv4(localAddress, "Local BBMD address");
        validatePort(port);

        List<BbmdEntry> table = new ArrayList<>();
        table.add(new BbmdEntry(localAddress, port));

        for (BbmdEntry peer : peers == null ? Collections.<BbmdEntry>emptyList() : peers) {
            if (peer == null) {
                continue;
            }
            if (localAddress.equals(peer.address) && port == peer.port) {
                continue;
            }
            table.add(peer);
        }

        network().enableBBMD();
        writeBdt(new InetSocketAddress(localAddress, port), table);
    }

    private IpNetwork network() {
        return (IpNetwork) localDevice.getNetwork();
    }

    private void writeBdt(InetSocketAddress localBbmd, List<BbmdEntry> entries) {
        byte[] request = new byte[4 + entries.size() * 10];
        request[0] = (byte) 0x81; // BACnet/IP BVLC
        request[1] = 0x01;        // Write-Broadcast-Distribution-Table
        request[2] = (byte) ((request.length >>> 8) & 0xff);
        request[3] = (byte) (request.length & 0xff);

        int offset = 4;
        try {
            for (BbmdEntry entry : entries) {
                byte[] address = InetAddress.getByName(entry.address).getAddress();
                System.arraycopy(address, 0, request, offset, 4);
                offset += 4;

                request[offset++] = (byte) ((entry.port >>> 8) & 0xff);
                request[offset++] = (byte) (entry.port & 0xff);

                // 255.255.255.255 means BBMD-to-BBMD unicast distribution.
                request[offset++] = (byte) 0xff;
                request[offset++] = (byte) 0xff;
                request[offset++] = (byte) 0xff;
                request[offset++] = (byte) 0xff;
            }

            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(3000);
                socket.send(new DatagramPacket(request, request.length, localBbmd));

                byte[] response = new byte[6];
                DatagramPacket packet = new DatagramPacket(response, response.length);
                socket.receive(packet);

                if (packet.getLength() != 6
                    || response[0] != (byte) 0x81
                    || response[1] != 0x00) {
                    throw new IllegalStateException("Unexpected BVLC response while writing BBMD BDT");
                }

                int result = ((response[4] & 0xff) << 8) | (response[5] & 0xff);
                if (result != 0) {
                    throw new IllegalStateException(
                        "BBMD rejected BDT write, BVLC result=0x" + Integer.toHexString(result));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to configure BACnet BBMD BDT", e);
        }
    }

    public static final class BbmdEntry {
        private final String address;
        private final int port;

        public BbmdEntry(String address, int port) {
            validateIpv4(address, "BBMD address");
            validatePort(port);
            this.address = address;
            this.port = port;
        }

        public String getAddress() {
            return address;
        }

        public int getPort() {
            return port;
        }
    }

    private static void validatePort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("BACnet/IP port out of range: " + port);
        }
    }

    private static void validateIpv4(String value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }

        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException(label + " is not a valid IPv4 address: " + value);
        }

        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                throw new IllegalArgumentException(label + " is not a valid IPv4 address: " + value);
            }
            try {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) {
                    throw new IllegalArgumentException(label + " is not a valid IPv4 address: " + value);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(label + " is not a valid IPv4 address: " + value, e);
            }
        }
    }

    @Override
    protected BacNetObject createObject(Device device, int instance, Type type, SequenceOf<ReadAccessResult> readAccessResults) {
        if (readAccessResults.size() == 1) {
            SequenceOf<Result> results = readAccessResults.get(0).getListOfResults();
            if (results.size() == 4) {
                String name = results.get(2).toString();
                String units = results.get(1).toString();
                String description = results.get(3).toString();
                return new BacNetObject(device, instance, type, name, description, units);
            }
            throw new IllegalStateException("Unsupported response structure " + readAccessResults);
        }
        String name = getReadValue(readAccessResults.get(2));
        String units = getReadValue(readAccessResults.get(1));
        String description = getReadValue(readAccessResults.get(3));
        return new BacNetObject(device, instance, type, name, description, units);
    }

    private String getReadValue(ReadAccessResult readAccessResult) {
        // first index contains 0 value.. I know it is weird, but that's how bacnet4j works
        return readAccessResult.getListOfResults().get(0).getReadResult().toString();
    }

}
