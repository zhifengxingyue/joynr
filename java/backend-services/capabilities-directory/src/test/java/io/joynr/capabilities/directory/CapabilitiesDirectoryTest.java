/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2017 BMW Car IT GmbH
 * %%
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
 * #L%
 */
package io.joynr.capabilities.directory;

import static org.junit.Assert.assertArrayEquals;

import java.util.Properties;
import java.util.UUID;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.joynr.provider.PromiseKeeper;
import joynr.system.RoutingTypes.Address;
import joynr.system.RoutingTypes.ChannelAddress;
import joynr.types.CustomParameter;
import joynr.types.GlobalDiscoveryEntry;
import joynr.types.ProviderQos;
import joynr.types.ProviderScope;
import joynr.types.Version;

public class CapabilitiesDirectoryTest {

    private static final CustomParameter[] CUSTOM_PARAMETERS = {};

    private static final long ONE_DAY_IN_MS = 24 * 60 * 60 * 1000;

    private static CapabilitiesDirectoryImpl capabilitiesDirectory;

    String channelId = "capabilitiesProvider";
    String url = "http://testUrl";
    Address channelAddres = new ChannelAddress(url, channelId);
    String channelAddresSerialized;
    String domain = "com";
    String interface1 = "interface1";
    String interface2 = "interface2";
    String interface3 = "Interface3";
    String interface4 = "Interface4";

    ProviderQos providerQos = new ProviderQos(CUSTOM_PARAMETERS, 1L, ProviderScope.GLOBAL, true);
    GlobalDiscoveryEntry discoveryEntry1;
    GlobalDiscoveryEntry discoveryEntry2;
    GlobalDiscoveryEntry discoveryEntry3;
    GlobalDiscoveryEntry discoveryEntry4;
    GlobalDiscoveryEntry discoveryEntry4FromAnotherNodeInCluster;
    String postFix = "" + System.currentTimeMillis();

    @BeforeClass
    public static void start() {
        capabilitiesDirectory = startCapabilitiesDirectory();
    }

    @Before
    public void setUp() throws Exception {
        channelAddresSerialized = new ObjectMapper().writeValueAsString(channelAddres);
        String participantId1 = "testParticipantId1_" + UUID.randomUUID().toString();
        String participantId2 = "testParticipantId2_" + UUID.randomUUID().toString();
        String participantId3 = "testParticipantId3_" + UUID.randomUUID().toString();
        String participantId4 = "testParticipantId4_" + UUID.randomUUID().toString();
        String publicKeyId = "publicKeyId";
        String publicKeyIdFromAnotherNodeInCluster = "publicKeyIdAnotherNode";

        long lastSeenDateMs = System.currentTimeMillis();
        long expiryDateMs = System.currentTimeMillis() + ONE_DAY_IN_MS;
        discoveryEntry1 = new GlobalDiscoveryEntry(new Version(47, 11),
                                                   domain,
                                                   interface1,
                                                   participantId1,
                                                   providerQos,
                                                   lastSeenDateMs,
                                                   expiryDateMs,
                                                   publicKeyId,
                                                   channelAddresSerialized);
        discoveryEntry2 = new GlobalDiscoveryEntry(new Version(47, 11),
                                                   domain,
                                                   interface2,
                                                   participantId2,
                                                   providerQos,
                                                   lastSeenDateMs,
                                                   expiryDateMs,
                                                   publicKeyId,
                                                   channelAddresSerialized);
        discoveryEntry3 = new GlobalDiscoveryEntry(new Version(47, 11),
                                                   domain,
                                                   interface3,
                                                   participantId3,
                                                   providerQos,
                                                   lastSeenDateMs,
                                                   expiryDateMs,
                                                   publicKeyId,
                                                   channelAddresSerialized);
        discoveryEntry4 = new GlobalDiscoveryEntry(new Version(47, 11),
                                                   domain,
                                                   interface4,
                                                   participantId4,
                                                   providerQos,
                                                   lastSeenDateMs,
                                                   expiryDateMs,
                                                   publicKeyId,
                                                   channelAddresSerialized);
        discoveryEntry4FromAnotherNodeInCluster = new GlobalDiscoveryEntry(new Version(47, 11),
                                                                           domain,
                                                                           interface4,
                                                                           participantId4,
                                                                           providerQos,
                                                                           lastSeenDateMs + 5000,
                                                                           expiryDateMs + 5000,
                                                                           publicKeyIdFromAnotherNodeInCluster,
                                                                           channelAddresSerialized);

    }

    private static CapabilitiesDirectoryImpl startCapabilitiesDirectory() {
        CapabilitiesDirectoryLauncher.start(new Properties());
        return CapabilitiesDirectoryLauncher.getCapabilitiesDirectory();
    }

    @AfterClass
    public static void tearDown() {
        CapabilitiesDirectoryLauncher.stop();
    }

    @Test
    public void registerMultipleCapabilitiesAsArray() throws InterruptedException {

        GlobalDiscoveryEntry[] interfaces2And3 = { discoveryEntry2, discoveryEntry3 };
        capabilitiesDirectory.add(interfaces2And3);

        PromiseKeeper lookupCapInfo2 = new PromiseKeeper();
        capabilitiesDirectory.lookup(new String[]{ domain }, interface2).then(lookupCapInfo2);
        assertArrayEquals(new GlobalDiscoveryEntry[]{ discoveryEntry2 },
                          (GlobalDiscoveryEntry[]) lookupCapInfo2.getValues()[0]);

        PromiseKeeper lookupCapInfo3 = new PromiseKeeper();
        capabilitiesDirectory.lookup(new String[]{ domain }, interface3).then(lookupCapInfo3);

        GlobalDiscoveryEntry[] passedDiscoveryEntries = (GlobalDiscoveryEntry[]) lookupCapInfo3.getValues()[0];
        assertArrayEquals(new GlobalDiscoveryEntry[]{ discoveryEntry3 }, passedDiscoveryEntries);
    }

    @Test
    public void registerProviderAndRequestChannels() throws Exception {
        capabilitiesDirectory.add(discoveryEntry1);

        PromiseKeeper lookupCapInfo1 = new PromiseKeeper();
        capabilitiesDirectory.lookup(new String[]{ domain }, interface1).then(lookupCapInfo1);
        lookupCapInfo1.waitForSettlement();
        assertArrayEquals(new GlobalDiscoveryEntry[]{ discoveryEntry1 },
                          (GlobalDiscoveryEntry[]) lookupCapInfo1.getValues()[0]);

    }

    @Test
    public void registerSameProviderMultipleTimesFromClusteredApplication() throws InterruptedException {
        capabilitiesDirectory.add(discoveryEntry4);
        capabilitiesDirectory.add(discoveryEntry4FromAnotherNodeInCluster);

        PromiseKeeper lookupCapInfo4 = new PromiseKeeper();
        capabilitiesDirectory.lookup(new String[]{ domain }, interface4).then(lookupCapInfo4);
        lookupCapInfo4.waitForSettlement();
        assertArrayEquals(new GlobalDiscoveryEntry[]{ discoveryEntry4FromAnotherNodeInCluster },
                          (GlobalDiscoveryEntry[]) lookupCapInfo4.getValues()[0]);
    }
}
