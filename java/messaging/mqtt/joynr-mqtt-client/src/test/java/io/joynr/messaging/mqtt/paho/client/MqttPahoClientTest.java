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
package io.joynr.messaging.mqtt.paho.client;

import static com.google.inject.util.Modules.override;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URISyntaxException;
import java.net.URL;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;

import io.joynr.common.JoynrPropertiesModule;
import io.joynr.exceptions.JoynrIllegalStateException;
import io.joynr.exceptions.JoynrMessageNotSentException;
import io.joynr.messaging.FailureAction;
import io.joynr.messaging.JoynrMessageProcessor;
import io.joynr.messaging.MessagingPropertyKeys;
import io.joynr.messaging.NoOpRawMessagingPreprocessor;
import io.joynr.messaging.RawMessagingPreprocessor;
import io.joynr.messaging.mqtt.IMqttMessagingSkeleton;
import io.joynr.messaging.mqtt.JoynrMqttClient;
import io.joynr.messaging.mqtt.MqttClientFactory;
import io.joynr.messaging.mqtt.MqttClientIdProvider;
import io.joynr.messaging.mqtt.MqttModule;
import io.joynr.messaging.mqtt.settings.LimitAndBackpressureSettings;
import io.joynr.messaging.mqtt.statusmetrics.MqttStatusReceiver;
import io.joynr.messaging.routing.MessageRouter;
import joynr.system.RoutingTypes.MqttAddress;

public class MqttPahoClientTest {

    private static int mqttBrokerPort;
    private static final String KEYSTORE_PASSWORD = "password";
    private static final boolean NON_SECURE_CONNECTION = false;
    private static Process mosquittoProcess;
    private Injector injector;
    private MqttClientFactory mqttClientFactory;
    private MqttAddress ownTopic;
    @Mock
    private IMqttMessagingSkeleton mockReceiver;
    @Mock
    private MessageRouter mockMessageRouter;
    private JoynrMqttClient joynrMqttClient;
    private Properties properties;
    private byte[] serializedMessage;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @BeforeClass
    public static void startBroker() throws Exception {
        mqttBrokerPort = 1883;
        String path = System.getProperty("path") != null ? System.getProperty("path") : "";
        ProcessBuilder processBuilder = new ProcessBuilder(path + "mosquitto", "-p", Integer.toString(mqttBrokerPort));
        mosquittoProcess = processBuilder.start();
    }

    @AfterClass
    public static void stopBroker() throws Exception {
        mosquittoProcess.destroy();
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        properties = new Properties();

        properties.put(MqttModule.PROPERTY_KEY_MQTT_RECONNECT_SLEEP_MS, "100");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_KEEP_ALIVE_TIMER_SEC, "60");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_CONNECTION_TIMEOUT_SEC, "30");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_TIME_TO_WAIT_MS, "-1");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_ENABLE_SHARED_SUBSCRIPTIONS, "false");
        properties.put(MessagingPropertyKeys.MQTT_TOPIC_PREFIX_MULTICAST, "");
        properties.put(MessagingPropertyKeys.MQTT_TOPIC_PREFIX_REPLYTO, "");
        properties.put(MessagingPropertyKeys.MQTT_TOPIC_PREFIX_UNICAST, "");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_MAX_MSGS_INFLIGHT, "100");
        properties.put(MessagingPropertyKeys.CHANNELID, "myChannelId");
        properties.put(LimitAndBackpressureSettings.PROPERTY_MAX_INCOMING_MQTT_REQUESTS, "0");
        properties.put(LimitAndBackpressureSettings.PROPERTY_BACKPRESSURE_ENABLED, "false");
        properties.put(LimitAndBackpressureSettings.PROPERTY_BACKPRESSURE_INCOMING_MQTT_REQUESTS_UPPER_THRESHOLD, "80");
        properties.put(LimitAndBackpressureSettings.PROPERTY_BACKPRESSURE_INCOMING_MQTT_REQUESTS_LOWER_THRESHOLD, "20");
        properties.put(MqttModule.PROPERTY_MQTT_CLEAN_SESSION, "false");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_MAX_MESSAGE_SIZE_BYTES, "0");
        serializedMessage = new byte[10];
    }

    @After
    public void tearDown() {
        if (joynrMqttClient != null) {
            joynrMqttClient.shutdown();
        }
    }

    private void createJoynrMqttClient() {
        try {
            createJoynrMqttClient(NON_SECURE_CONNECTION);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Get the path of the test resources
    private static String getResourcePath(String filename) throws URISyntaxException {
        URL resource = ClassLoader.getSystemClassLoader().getResource(filename);
        return resource.getPath();
    }

    private void createJoynrMqttClient(boolean isSecureConnection) {
        joynrMqttClient = createMqttClientWithoutSubscription(isSecureConnection, null);

        ownTopic = injector.getInstance((Key.get(MqttAddress.class,
                                                 Names.named(MqttModule.PROPERTY_MQTT_GLOBAL_ADDRESS))));
        joynrMqttClient.subscribe(ownTopic.getTopic());
    }

    private JoynrMqttClient createMqttClientWithoutSubscription() {
        return createMqttClientWithoutSubscription(NON_SECURE_CONNECTION, null);
    }

    private JoynrMqttClient createMqttClientWithoutSubscription(boolean isSecureConnection,
                                                                final MqttStatusReceiver mqttStatusReceiver) {
        if (isSecureConnection) {
            properties.put(MqttModule.PROPERTY_KEY_MQTT_BROKER_URI, "ssl://localhost:8883");
        } else {
            properties.put(MqttModule.PROPERTY_KEY_MQTT_BROKER_URI, "tcp://localhost:1883");
        }
        JoynrMqttClient client = createMqttClientInternal(mqttStatusReceiver);
        client.start();
        return client;
    }

    private JoynrMqttClient createMqttClientInternal(final MqttStatusReceiver mqttStatusReceiver) {
        // always create a new Factory because the factory caches its client.
        createMqttClientFactory(mqttStatusReceiver);

        JoynrMqttClient client = mqttClientFactory.createSender();
        client.setMessageListener(mockReceiver);
        return client;
    }

    private void createMqttClientFactory(final MqttStatusReceiver mqttStatusReceiver) {
        injector = Guice.createInjector(override(new MqttPahoModule()).with(new AbstractModule() {
            @Override
            protected void configure() {
                if (mqttStatusReceiver != null) {
                    bind(MqttStatusReceiver.class).toInstance(mqttStatusReceiver);
                }
            }
        }), new JoynrPropertiesModule(properties), new AbstractModule() {
            @Override
            protected void configure() {
                bind(MessageRouter.class).toInstance(mockMessageRouter);
                bind(ScheduledExecutorService.class).annotatedWith(Names.named(MessageRouter.SCHEDULEDTHREADPOOL))
                                                    .toInstance(Executors.newScheduledThreadPool(10));
                bind(RawMessagingPreprocessor.class).to(NoOpRawMessagingPreprocessor.class);
                Multibinder.newSetBinder(binder(), new TypeLiteral<JoynrMessageProcessor>() {
                });
            }
        });

        mqttClientFactory = injector.getInstance(MqttClientFactory.class);
    }

    @Test
    public void mqttClientTestWithTwoConnections() throws Exception {
        final boolean separateConnections = true;
        final MqttStatusReceiver mqttStatusReceiver = mock(MqttStatusReceiver.class);
        properties.put(MqttModule.PROPERTY_KEY_MQTT_SEPARATE_CONNECTIONS, String.valueOf(separateConnections));
        properties.put(MqttModule.PROPERTY_MQTT_CLEAN_SESSION, "true");
        createMqttClientFactory(mqttStatusReceiver);
        ownTopic = injector.getInstance((Key.get(MqttAddress.class,
                                                 Names.named(MqttModule.PROPERTY_MQTT_GLOBAL_ADDRESS))));
        JoynrMqttClient clientSender = mqttClientFactory.createSender();
        JoynrMqttClient clientReceiver = mqttClientFactory.createReceiver();
        assertNotEquals(clientSender, clientReceiver);

        clientReceiver.setMessageListener(mockReceiver);

        clientSender.start();
        clientReceiver.start();
        verify(mqttStatusReceiver,
               times(2)).notifyConnectionStatusChanged(MqttStatusReceiver.ConnectionStatus.CONNECTED);

        clientReceiver.subscribe(ownTopic.getTopic());

        clientSender.publishMessage(ownTopic.getTopic(), serializedMessage);
        verify(mockReceiver, timeout(500).times(1)).transmit(eq(serializedMessage), any(FailureAction.class));

        clientReceiver.shutdown();
        clientSender.shutdown();
        verify(mqttStatusReceiver,
               timeout(500).times(2)).notifyConnectionStatusChanged(MqttStatusReceiver.ConnectionStatus.NOT_CONNECTED);
    }

    @Test
    public void mqttClientTestWithOneConnection() throws Exception {
        final MqttStatusReceiver mqttStatusReceiver = mock(MqttStatusReceiver.class);
        createMqttClientFactory(mqttStatusReceiver);

        JoynrMqttClient clientSender = mqttClientFactory.createSender();
        JoynrMqttClient clientReceiver = mqttClientFactory.createReceiver();

        assertEquals(clientSender, clientReceiver);

        clientSender.start();
        clientReceiver.start();
        verify(mqttStatusReceiver,
               times(1)).notifyConnectionStatusChanged(MqttStatusReceiver.ConnectionStatus.CONNECTED);

        clientReceiver.shutdown();
        clientSender.shutdown();
        verify(mqttStatusReceiver,
               timeout(500).times(1)).notifyConnectionStatusChanged(MqttStatusReceiver.ConnectionStatus.NOT_CONNECTED);
    }

    private void joynrMqttClientPublishAndVerifyReceivedMessage(byte[] serializedMessage) {
        joynrMqttClient.publishMessage(ownTopic.getTopic(), serializedMessage);
        verify(mockReceiver, timeout(100).times(1)).transmit(eq(serializedMessage), any(FailureAction.class));
    }

    @Test
    public void mqttClientTestWithEnabledMessageSizeCheck() throws Exception {
        final int maxMessageSize = 100;
        properties.put(MqttModule.PROPERTY_KEY_MQTT_MAX_MESSAGE_SIZE_BYTES, String.valueOf(maxMessageSize));
        createJoynrMqttClient();

        byte[] shortSerializedMessage = new byte[maxMessageSize];
        joynrMqttClientPublishAndVerifyReceivedMessage(shortSerializedMessage);

        byte[] largeSerializedMessage = new byte[maxMessageSize + 1];
        thrown.expect(JoynrMessageNotSentException.class);
        thrown.expectMessage("MQTT Publish failed: maximum allowed message size of " + maxMessageSize
                + " bytes exceeded, actual size is " + largeSerializedMessage.length + " bytes");
        joynrMqttClient.publishMessage(ownTopic.getTopic(), largeSerializedMessage);
    }

    private void mqttClientTestWithDisabledMessageSizeCheck(boolean isSecureConnection) throws Exception {
        final int initialMessageSize = 100;
        properties.put(MqttModule.PROPERTY_KEY_MQTT_MAX_MESSAGE_SIZE_BYTES, "0");
        createJoynrMqttClient(isSecureConnection);

        byte[] shortSerializedMessage = new byte[initialMessageSize];
        joynrMqttClientPublishAndVerifyReceivedMessage(shortSerializedMessage);

        byte[] largeSerializedMessage = new byte[initialMessageSize + 1];
        joynrMqttClientPublishAndVerifyReceivedMessage(largeSerializedMessage);
    }

    @Test
    public void mqttClientTestWithDisabledMessageSizeCheckWithoutTls() throws Exception {
        final boolean isSecureConnection = false;
        mqttClientTestWithDisabledMessageSizeCheck(isSecureConnection);
    }

    @Test
    public void mqttClientTestWithDisabledMessageSizeCheckWithTlsAndDefaultJksStore() throws Exception {
        final String keyStorePath = getResourcePath("clientkeystore.jks");
        final String trustStorePath = getResourcePath("catruststore.jks");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_PATH, keyStorePath);
        properties.put(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_PATH, trustStorePath);
        properties.put(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_PWD, KEYSTORE_PASSWORD);
        properties.put(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_PWD, KEYSTORE_PASSWORD);

        final boolean isSecureConnection = true;
        mqttClientTestWithDisabledMessageSizeCheck(isSecureConnection);
    }

    @Test
    public void mqttClientTestWithDisabledMessageSizeCheckWithTlsAndP12Store() throws Exception {
        final String keyStorePath = getResourcePath("clientkeystore.p12");
        final String trustStorePath = getResourcePath("catruststore.p12");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_PATH, keyStorePath);
        properties.put(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_PATH, trustStorePath);
        properties.put(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_TYPE, "PKCS12");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_TYPE, "PKCS12");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_PWD, KEYSTORE_PASSWORD);
        properties.put(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_PWD, KEYSTORE_PASSWORD);

        final boolean isSecureConnection = true;
        mqttClientTestWithDisabledMessageSizeCheck(isSecureConnection);
    }

    private void testCreateMqttClientFailsWithJoynrIllegalArgumentException() {
        final boolean isSecureConnection = true;
        try {
            createJoynrMqttClient(isSecureConnection);
            fail("Expected JoynrIllegalStateException");
        } catch (JoynrIllegalStateException e) {
            // expected behaviour
        }
    }

    @Test
    public void mqttClientTLSCreationFailsIfKeystorePasswordIsWrongOrMissing() throws URISyntaxException {
        final String wrongPassword = "wrongPassword";

        final String keyStorePath = getResourcePath("clientkeystore.jks");
        final String trustStorePath = getResourcePath("catruststore.jks");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_PATH, keyStorePath);
        properties.put(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_PATH, trustStorePath);

        // test missing keystore password
        properties.remove(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_PWD);
        properties.put(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_PWD, KEYSTORE_PASSWORD);

        testCreateMqttClientFailsWithJoynrIllegalArgumentException();

        // test wrong keystore password
        properties.put(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_PWD, wrongPassword);
        testCreateMqttClientFailsWithJoynrIllegalArgumentException();
    }

    @Test
    public void mqttClientTLSCreationFailsIfTrustorePasswordIsWrongOrMissing() throws URISyntaxException {
        final String wrongPassword = "wrongPassword";

        final String keyStorePath = getResourcePath("clientkeystore.jks");
        final String trustStorePath = getResourcePath("catruststore.jks");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_PATH, keyStorePath);
        properties.put(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_PATH, trustStorePath);

        // test missing truststore password
        properties.put(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_PWD, KEYSTORE_PASSWORD);
        properties.remove(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_PWD);

        testCreateMqttClientFailsWithJoynrIllegalArgumentException();

        // test wrong truststore password
        properties.put(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_PWD, wrongPassword);
        testCreateMqttClientFailsWithJoynrIllegalArgumentException();
    }

    @Test
    public void mqttClientTLSCreationFailsIfKeystorePathIsWrongOrMissing() throws URISyntaxException {
        final String wrongKeyStorePath = getResourcePath("clientkeystore.jks") + "42";

        final String trustStorePath = getResourcePath("catruststore.jks");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_PATH, trustStorePath);
        properties.put(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_PWD, KEYSTORE_PASSWORD);
        properties.put(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_PWD, KEYSTORE_PASSWORD);

        // test missing keystore path
        properties.remove(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_PATH);

        testCreateMqttClientFailsWithJoynrIllegalArgumentException();

        // test wrong keystore path
        properties.put(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_PATH, wrongKeyStorePath);
        testCreateMqttClientFailsWithJoynrIllegalArgumentException();
    }

    @Test
    public void mqttClientTLSCreationFailsIfTrustorePathIsWrongOrMissing() throws URISyntaxException {
        final String wrongTrustStorePath = getResourcePath("catruststore.jks") + "42";

        final String keyStorePath = getResourcePath("clientkeystore.jks");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_PATH, keyStorePath);
        properties.put(MqttModule.PROPERTY_KEY_MQTT_KEYSTORE_PWD, KEYSTORE_PASSWORD);
        properties.put(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_PWD, KEYSTORE_PASSWORD);

        // test missing truststore path
        properties.remove(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_PATH);

        testCreateMqttClientFailsWithJoynrIllegalArgumentException();

        // test wrong truststore path
        properties.put(MqttModule.PROPERTY_KEY_MQTT_TRUSTSTORE_PATH, wrongTrustStorePath);
        testCreateMqttClientFailsWithJoynrIllegalArgumentException();
    }

    @Test
    public void mqttClientTestWithDisabledCleanSession() throws Exception {
        properties.put(MqttModule.PROPERTY_MQTT_CLEAN_SESSION, "false");
        String topic = "otherTopic";

        // create a MqttClient which was subscribed on the topic and shut it down.
        joynrMqttClient = createMqttClientWithoutSubscription();
        joynrMqttClient.subscribe(topic);
        joynrMqttClient.shutdown();

        // use another MqttClient to publish a message for the first topic
        joynrMqttClient = createMqttClientWithoutSubscription();
        joynrMqttClient.publishMessage(topic, serializedMessage);
        Thread.sleep(100);
        joynrMqttClient.shutdown();

        // create a MqttClient and subscribe to the same topic as the first one
        // MqttClient will receive message if cleanSession is disabled
        joynrMqttClient = createMqttClientWithoutSubscription();
        joynrMqttClient.subscribe(topic);

        Thread.sleep(100);
        verify(mockReceiver, atLeast(1)).transmit(eq(serializedMessage), any(FailureAction.class));
    }

    @Test
    public void mqttClientTestWithEnabledCleanSession() throws Exception {
        properties.put(MqttModule.PROPERTY_MQTT_CLEAN_SESSION, "true");
        String topic = "otherTopic1";

        // create a MqttClient which was subscribed on the topic and shut it down.
        joynrMqttClient = createMqttClientWithoutSubscription();
        joynrMqttClient.subscribe(topic);
        joynrMqttClient.shutdown();

        // use another MqttClient to publish a message for the first topic
        joynrMqttClient = createMqttClientWithoutSubscription();
        joynrMqttClient.publishMessage(topic, serializedMessage);
        Thread.sleep(100);
        joynrMqttClient.shutdown();

        // create a MqttClient and subscribe to the same topic as the first one
        // MqttClient will receive message if cleanSession is disabled
        joynrMqttClient = createMqttClientWithoutSubscription();
        joynrMqttClient.subscribe(topic);

        Thread.sleep(100);
        verify(mockReceiver, times(0)).transmit(eq(serializedMessage), any(FailureAction.class));
    }

    @Test
    public void mqttClientTestResubscriptionWithCleanRestartEnabled() throws Exception {
        properties.put(MqttModule.PROPERTY_KEY_MQTT_BROKER_URI, "tcp://localhost:1883");
        injector = Guice.createInjector(new MqttPahoModule(),
                                        new JoynrPropertiesModule(properties),
                                        new AbstractModule() {

                                            @Override
                                            protected void configure() {
                                                bind(MessageRouter.class).toInstance(mockMessageRouter);
                                                bind(ScheduledExecutorService.class).annotatedWith(Names.named(MessageRouter.SCHEDULEDTHREADPOOL))
                                                                                    .toInstance(Executors.newScheduledThreadPool(10));
                                                bind(RawMessagingPreprocessor.class).to(NoOpRawMessagingPreprocessor.class);
                                                Multibinder.newSetBinder(binder(),
                                                                         new TypeLiteral<JoynrMessageProcessor>() {
                                                                         });
                                            }
                                        });

        ownTopic = injector.getInstance((Key.get(MqttAddress.class,
                                                 Names.named(MqttModule.PROPERTY_MQTT_GLOBAL_ADDRESS))));

        ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(10);
        MqttClientIdProvider mqttClientIdProvider = injector.getInstance(MqttClientIdProvider.class);

        String clientId = mqttClientIdProvider.getClientId();
        String brokerUri = "tcp://localhost:1883";
        int reconnectSleepMs = 100;
        int keepAliveTimerSec = 60;
        int connectionTimeoutSec = 60;
        int timeToWaitMs = -1;
        int maxMsgsInflight = 100;
        int maxMsgSizeBytes = 0;
        boolean cleanSession = true;
        final boolean isReceiver = true;
        final boolean separateConnections = false;

        MqttClient mqttClient = new MqttClient(brokerUri, clientId, new MemoryPersistence(), scheduledExecutorService);
        joynrMqttClient = new MqttPahoClient(mqttClient,
                                             reconnectSleepMs,
                                             keepAliveTimerSec,
                                             connectionTimeoutSec,
                                             timeToWaitMs,
                                             maxMsgsInflight,
                                             maxMsgSizeBytes,
                                             cleanSession,
                                             isReceiver,
                                             separateConnections,
                                             "",
                                             "",
                                             "",
                                             "",
                                             "",
                                             "",
                                             mock(MqttStatusReceiver.class));

        joynrMqttClient.start();
        joynrMqttClient.setMessageListener(mockReceiver);
        joynrMqttClient.subscribe(ownTopic.getTopic());

        // manually call disconnect and connectionLost
        mqttClient.disconnect(500);
        MqttException exeption = new MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT);
        MqttPahoClient mqttPahoClient = (MqttPahoClient) joynrMqttClient;
        mqttPahoClient.connectionLost(exeption);

        joynrMqttClientPublishAndVerifyReceivedMessage(serializedMessage);
    }

    // This test was disabled, because it runs perfectly on a local machine but not in the CI.
    // Further investigations are required to stabilize this test.
    @Test
    @Ignore
    public void testClientNotifiesStatusReceiverAboutBrokerDisconnect() throws Exception {
        final MqttStatusReceiver mqttStatusReceiver = mock(MqttStatusReceiver.class);
        @SuppressWarnings("unused")
        final JoynrMqttClient mqttClient = createMqttClientWithoutSubscription(false, mqttStatusReceiver);

        verify(mqttStatusReceiver).notifyConnectionStatusChanged(MqttStatusReceiver.ConnectionStatus.CONNECTED);

        stopBroker();
        Thread.sleep(1000);
        verify(mqttStatusReceiver).notifyConnectionStatusChanged(MqttStatusReceiver.ConnectionStatus.NOT_CONNECTED);

        startBroker();
        Thread.sleep(2000);
        verify(mqttStatusReceiver,
               times(2)).notifyConnectionStatusChanged(MqttStatusReceiver.ConnectionStatus.CONNECTED);
    }

    @Test
    public void testClientNotifiesStatusReceiverAboutShutdownDisconnect() throws Exception {
        final MqttStatusReceiver mqttStatusReceiver = mock(MqttStatusReceiver.class);
        final JoynrMqttClient mqttClient = createMqttClientWithoutSubscription(false, mqttStatusReceiver);

        verify(mqttStatusReceiver).notifyConnectionStatusChanged(MqttStatusReceiver.ConnectionStatus.CONNECTED);

        mqttClient.shutdown();
        verify(mqttStatusReceiver).notifyConnectionStatusChanged(MqttStatusReceiver.ConnectionStatus.NOT_CONNECTED);
    }

    @Test
    public void mqttClientTestShutdownIfDisconnectFromMQTT() throws Exception {
        properties.put(MqttModule.PROPERTY_KEY_MQTT_BROKER_URI, "tcp://localhost:1111");
        properties.put(MqttModule.PROPERTY_KEY_MQTT_RECONNECT_SLEEP_MS, "100");
        // create and start client
        final JoynrMqttClient client = createMqttClientInternal(mock(MqttStatusReceiver.class));
        final Semaphore semaphoreBeforeStartMethod = new Semaphore(0);
        final Semaphore semaphoreAfterStartMethod = new Semaphore(0);
        final int timeout = 500;
        Runnable myRunnable = new Runnable() {
            @Override
            public void run() {
                semaphoreBeforeStartMethod.release();
                client.start();
                semaphoreAfterStartMethod.release();
            }
        };
        new Thread(myRunnable).start();
        assertTrue(semaphoreBeforeStartMethod.tryAcquire(timeout, TimeUnit.MILLISECONDS));
        // sleep in order to increase the probability of the runnable
        // to be in the sleep part of the start method
        Thread.sleep(timeout);
        // At this point the semaphoreAfterStartMethod is supposed to be not released
        // because we expect to be still in start()
        assertFalse(semaphoreAfterStartMethod.tryAcquire());

        client.shutdown();
        assertTrue(semaphoreAfterStartMethod.tryAcquire(timeout, TimeUnit.MILLISECONDS));
    }

}
