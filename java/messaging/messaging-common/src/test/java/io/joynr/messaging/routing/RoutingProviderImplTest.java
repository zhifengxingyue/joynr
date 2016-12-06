package io.joynr.messaging.routing;

/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2016 BMW Car IT GmbH
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

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import java.util.concurrent.Semaphore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import io.joynr.provider.Deferred;
import io.joynr.provider.Promise;
import io.joynr.provider.PromiseListener;
import io.joynr.runtime.GlobalAddressProvider;
import joynr.system.RoutingSubscriptionPublisher;
import joynr.system.RoutingTypes.ChannelAddress;
import joynr.system.RoutingTypes.MqttAddress;
import joynr.system.RoutingTypes.RoutingTypesUtil;

@RunWith(MockitoJUnitRunner.class)
public class RoutingProviderImplTest {

    @Mock
    private MessageRouter mockMessageRouter;
    @Mock
    private GlobalAddressProvider mockGlobalAddressProvider;
    @Mock
    private PromiseListener mockPromiseListener;
    @Mock
    private RoutingSubscriptionPublisher mockRoutingSubscriptionPublisher;

    private MqttAddress expectedMqttAddress;
    private String expectedMqttAddressString;
    private ChannelAddress expectedChannelAddress;
    private String expectedChannelAddressString;
    private RoutingProviderImpl routingProvider;
    @Captor
    private ArgumentCaptor<TransportReadyListener> transportReadyListener;

    private Semaphore getGlobalAddressOnFulfillmentSemaphore;
    private Semaphore globalAddressChangedSemaphore;

    @Before
    public void setUp() {
        expectedMqttAddress = new MqttAddress("mqtt://test-broker-uri", "test-topic");
        expectedMqttAddressString = RoutingTypesUtil.toAddressString(expectedMqttAddress);
        expectedChannelAddress = new ChannelAddress("http://test-bounceproxy-url", "test-channelId");
        expectedChannelAddressString = RoutingTypesUtil.toAddressString(expectedChannelAddress);

        routingProvider = new RoutingProviderImpl(mockMessageRouter, mockGlobalAddressProvider);
        routingProvider.setSubscriptionPublisher(mockRoutingSubscriptionPublisher);

        getGlobalAddressOnFulfillmentSemaphore = new Semaphore(0);
        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                getGlobalAddressOnFulfillmentSemaphore.release();
                return null;
            }
        }).when(mockPromiseListener).onFulfillment(anyString());

        globalAddressChangedSemaphore = new Semaphore(0);
        doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                globalAddressChangedSemaphore.release();
                return null;
            }
        }).when(mockRoutingSubscriptionPublisher).globalAddressChanged(anyString());
    }

    @Test(timeout = 500)
    public void testGlobalAddressGetAfterTransportIsReady() throws InterruptedException {
        verify(mockGlobalAddressProvider).registerGlobalAddressesReadyListener(transportReadyListener.capture());
        transportReadyListener.getValue().transportReady(expectedMqttAddress);

        Promise<Deferred<String>> globalAddressPromise = routingProvider.getGlobalAddress();
        globalAddressPromise.then(mockPromiseListener);
        verify(mockPromiseListener).onFulfillment(expectedMqttAddressString);
        getGlobalAddressOnFulfillmentSemaphore.acquire();
    }

    @Test(timeout = 500)
    public void testGlobalAddressGetBeforeTransportIsReady() throws InterruptedException {
        Promise<Deferred<String>> globalAddressPromise = routingProvider.getGlobalAddress();

        verify(mockGlobalAddressProvider).registerGlobalAddressesReadyListener(transportReadyListener.capture());
        transportReadyListener.getValue().transportReady(expectedMqttAddress);

        globalAddressPromise.then(mockPromiseListener);
        verify(mockPromiseListener).onFulfillment(expectedMqttAddressString);
        getGlobalAddressOnFulfillmentSemaphore.acquire();
    }

    @Test(timeout = 500)
    public void testGlobalAddressOnChangeNotifications() throws InterruptedException {
        verify(mockGlobalAddressProvider).registerGlobalAddressesReadyListener(transportReadyListener.capture());
        transportReadyListener.getValue().transportReady(expectedMqttAddress);

        verify(mockRoutingSubscriptionPublisher).globalAddressChanged(expectedMqttAddressString);

        transportReadyListener.getValue().transportReady(expectedChannelAddress);

        verify(mockRoutingSubscriptionPublisher).globalAddressChanged(expectedChannelAddressString);

        globalAddressChangedSemaphore.acquire(2);
    }

}