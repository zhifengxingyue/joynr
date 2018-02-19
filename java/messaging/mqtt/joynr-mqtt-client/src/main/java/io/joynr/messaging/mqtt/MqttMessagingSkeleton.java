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
package io.joynr.messaging.mqtt;

import static io.joynr.messaging.ConfigurableMessagingSettings.PROPERTY_BACKPRESSURE_ENABLED;
import static io.joynr.messaging.ConfigurableMessagingSettings.PROPERTY_BACKPRESSURE_MAX_INCOMING_MQTT_MESSAGES_IN_QUEUE;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.google.inject.Inject;
import com.google.inject.name.Named;

import io.joynr.messaging.FailureAction;
import io.joynr.messaging.JoynrMessageProcessor;
import io.joynr.messaging.RawMessagingPreprocessor;
import io.joynr.messaging.routing.MessageProcessedListener;
import io.joynr.messaging.routing.MessageRouter;
import io.joynr.messaging.routing.TimedDelayed;
import io.joynr.smrf.EncodingException;
import io.joynr.smrf.UnsuppportedVersionException;
import joynr.ImmutableMessage;
import joynr.system.RoutingTypes.MqttAddress;

/**
 * Connects to the MQTT broker
 */
public class MqttMessagingSkeleton implements IMqttMessagingSkeleton, MessageProcessedListener {

    private static final Logger LOG = LoggerFactory.getLogger(MqttMessagingSkeleton.class);
    private final int maxMqttMessagesInQueue;
    private MessageRouter messageRouter;
    private JoynrMqttClient mqttClient;
    private MqttClientFactory mqttClientFactory;
    private MqttAddress ownAddress;
    private ConcurrentMap<String, AtomicInteger> multicastSubscriptionCount = Maps.newConcurrentMap();
    private MqttTopicPrefixProvider mqttTopicPrefixProvider;
    private RawMessagingPreprocessor rawMessagingPreprocessor;
    private Set<JoynrMessageProcessor> messageProcessors;
    private Map<String, MqttAckInformation> processingMessages;
    private DelayQueue<DelayedMessageId> processedMessagesQueue;
    private final boolean backpressureEnabled;

    private static class MqttAckInformation {
        private int mqttId;
        private int mqttQos;

        MqttAckInformation(int mqttId, int mqttQos) {
            this.mqttId = mqttId;
            this.mqttQos = mqttQos;
        }

        public int getMqttId() {
            return mqttId;
        }

        public int getMqttQos() {
            return mqttQos;
        }
    }

    private static class DelayedMessageId extends TimedDelayed {

        private String messageId;

        public DelayedMessageId(String messageId, long delayMs) {
            super(delayMs);
            this.messageId = messageId;
        }

        public String getMessageId() {
            return messageId;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = super.hashCode();
            result = prime * result + ((messageId == null) ? 0 : messageId.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null)
                return false;
            if (getClass() != obj.getClass()) {
                return false;
            }
            DelayedMessageId other = (DelayedMessageId) obj;
            if (messageId == null) {
                if (other.messageId != null) {
                    return false;
                }
            } else if (!messageId.equals(other.messageId)) {
                return false;
            }
            return true;
        }
    }

    @Inject
    // CHECKSTYLE IGNORE ParameterNumber FOR NEXT 2 LINES
    public MqttMessagingSkeleton(@Named(MqttModule.PROPERTY_MQTT_GLOBAL_ADDRESS) MqttAddress ownAddress,
                                 @Named(PROPERTY_BACKPRESSURE_MAX_INCOMING_MQTT_MESSAGES_IN_QUEUE) int maxMqttMessagesInQueue,
                                 @Named(PROPERTY_BACKPRESSURE_ENABLED) boolean backpressureEnabled,
                                 MessageRouter messageRouter,
                                 MqttClientFactory mqttClientFactory,
                                 MqttTopicPrefixProvider mqttTopicPrefixProvider,
                                 RawMessagingPreprocessor rawMessagingPreprocessor,
                                 Set<JoynrMessageProcessor> messageProcessors) {
        this.backpressureEnabled = backpressureEnabled;
        this.ownAddress = ownAddress;
        this.maxMqttMessagesInQueue = maxMqttMessagesInQueue;
        this.messageRouter = messageRouter;
        this.mqttClientFactory = mqttClientFactory;
        this.mqttTopicPrefixProvider = mqttTopicPrefixProvider;
        this.rawMessagingPreprocessor = rawMessagingPreprocessor;
        this.messageProcessors = messageProcessors;
        this.processingMessages = new HashMap<>();
        this.processedMessagesQueue = new DelayQueue<>();
    }

    @Override
    public void init() {
        LOG.debug("Initializing MQTT skeleton ...");

        messageRouter.registerMessageProcessedListener(this);

        mqttClient = mqttClientFactory.create();
        mqttClient.setMessageListener(this);
        mqttClient.start();
        subscribe();
    }

    /**
     * Performs standard subscription to the {@link #ownAddress own address'} topic; override this method to perform
     * custom subscriptions. One use-case could be to subscribe to one topic for incoming messages and another topic for
     * replies.
     */
    protected void subscribe() {
        mqttClient.subscribe(ownAddress.getTopic() + "/#");
    }

    @Override
    public void shutdown() {
        mqttClient.shutdown();
    }

    @Override
    public void registerMulticastSubscription(String multicastId) {
        multicastSubscriptionCount.putIfAbsent(multicastId, new AtomicInteger());
        int numberOfSubscriptions = multicastSubscriptionCount.get(multicastId).incrementAndGet();
        if (numberOfSubscriptions == 1) {
            mqttClient.subscribe(getSubscriptionTopic(multicastId));
        }
    }

    @Override
    public void unregisterMulticastSubscription(String multicastId) {
        AtomicInteger subscribersCount = multicastSubscriptionCount.get(multicastId);
        if (subscribersCount != null) {
            int remainingCount = subscribersCount.decrementAndGet();
            if (remainingCount == 0) {
                mqttClient.unsubscribe(getSubscriptionTopic(multicastId));
            }
        }
    }

    private String translateWildcard(String multicastId) {
        String topic = multicastId;
        if (topic.endsWith("/*")) {
            topic = topic.replaceFirst("/\\*$", "/#");
        }
        return topic;
    }

    @Override
    public void transmit(byte[] serializedMessage, int mqttId, int mqttQos, FailureAction failureAction) {
        try {
            HashMap<String, Serializable> context = new HashMap<String, Serializable>();
            byte[] processedMessage = rawMessagingPreprocessor.process(serializedMessage, context);

            ImmutableMessage message = new ImmutableMessage(processedMessage);
            message.setContext(context);

            LOG.debug("<<< INCOMING <<< {}", message);

            if (messageProcessors != null) {
                for (JoynrMessageProcessor processor : messageProcessors) {
                    message = processor.processIncoming(message);
                }
            }

            if (dropMessage(message)) {
                return;
            }

            message.setReceivedFromGlobal(true);

            try {
                messageRouter.route(message);
            } catch (Exception e) {
                LOG.error("Error processing incoming message. Message will be dropped: {} ", e.getMessage());
                handleMessageProcessed(message.getId(), mqttId, mqttQos);
                failureAction.execute(e);
            }
        } catch (UnsuppportedVersionException | EncodingException | NullPointerException e) {
            LOG.error("Message: \"{}\", could not be deserialized, exception: {}", serializedMessage, e.getMessage());
            failureAction.execute(e);
        }
    }

    private boolean dropMessage(ImmutableMessage message) {
        return false;
    }

    protected JoynrMqttClient getClient() {
        return mqttClient;
    }

    protected MqttAddress getOwnAddress() {
        return ownAddress;
    }

    private String getSubscriptionTopic(String multicastId) {
        return mqttTopicPrefixProvider.getMulticastTopicPrefix() + translateWildcard(multicastId);
    }

    private void removeProcessedMessageInformation() {
        DelayedMessageId delayedMessageId;
        while ((delayedMessageId = processedMessagesQueue.poll()) != null) {
            LOG.debug("Message {} removed from list of processed messages", delayedMessageId.getMessageId());
            processingMessages.remove(delayedMessageId.getMessageId());
        }
    }

    //TODO method will be rewritten with the new backpressure mechanism
    private void handleMessageProcessed(String messageId, int mqttId, int mqttQos) {
        DelayedMessageId delayedMessageId = new DelayedMessageId(messageId, 0);
        if (!processedMessagesQueue.contains(delayedMessageId)) {
            LOG.debug("Message {} was processed", messageId);
            processedMessagesQueue.put(delayedMessageId);
        }
    }

    @Override
    public void messageProcessed(String messageId) {
        synchronized (processingMessages) {
            MqttAckInformation info = processingMessages.get(messageId);
            if (info == null) {
                LOG.debug("Message {} was processed but it is unkown", messageId);
                return;
            }
            handleMessageProcessed(messageId, info.getMqttId(), info.getMqttQos());
            removeProcessedMessageInformation();
        }
    }

}
