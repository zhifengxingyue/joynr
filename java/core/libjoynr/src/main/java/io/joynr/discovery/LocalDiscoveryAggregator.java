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
package io.joynr.discovery;

import static io.joynr.util.VersionUtil.getVersionFromAnnotation;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.name.Named;

import io.joynr.exceptions.JoynrRuntimeException;
import io.joynr.messaging.MessagingQos;
import io.joynr.provider.ProviderAnnotations;
import io.joynr.proxy.Callback;
import io.joynr.proxy.Future;
import io.joynr.proxy.ProxyBuilderFactory;
import io.joynr.runtime.SystemServicesSettings;
import joynr.system.DiscoveryAsync;
import joynr.system.DiscoveryProvider;
import joynr.system.DiscoveryProxy;
import joynr.system.Routing;
import joynr.system.RoutingProvider;
import joynr.types.DiscoveryEntry;
import joynr.types.DiscoveryEntryWithMetaInfo;
import joynr.types.DiscoveryQos;
import joynr.types.ProviderQos;
import joynr.types.ProviderScope;

public class LocalDiscoveryAggregator implements DiscoveryAsync {

    private static final Logger logger = LoggerFactory.getLogger(LocalDiscoveryAggregator.class);

    private static final long NO_EXPIRY = Long.MAX_VALUE;
    private HashMap<String, DiscoveryEntryWithMetaInfo> provisionedDiscoveryEntries = new HashMap<>();
    private DiscoveryProxy discoveryProxy;
    private ProxyBuilderFactory proxyBuilderFactory;
    private String systemServiceDomain;

    @Inject
    public LocalDiscoveryAggregator(@Named(SystemServicesSettings.PROPERTY_SYSTEM_SERVICES_DOMAIN) String systemServicesDomain,
                                    @Named(SystemServicesSettings.PROPERTY_CC_DISCOVERY_PROVIDER_PARTICIPANT_ID) String discoveryProviderParticipantId,
                                    @Named(SystemServicesSettings.PROPERTY_CC_ROUTING_PROVIDER_PARTICIPANT_ID) String routingProviderParticipantId,
                                    ProxyBuilderFactory proxyBuilderFactory) {
        ProviderQos providerQos = new ProviderQos();
        providerQos.setScope(ProviderScope.LOCAL);
        String defaultPublicKeyId = "";
        provisionedDiscoveryEntries.put(systemServicesDomain
                + ProviderAnnotations.getInterfaceName(DiscoveryProvider.class),
                                        new DiscoveryEntryWithMetaInfo(getVersionFromAnnotation(DiscoveryProvider.class),
                                                                       systemServicesDomain,
                                                                       ProviderAnnotations.getInterfaceName(DiscoveryProvider.class),
                                                                       discoveryProviderParticipantId,
                                                                       providerQos,
                                                                       System.currentTimeMillis(),
                                                                       NO_EXPIRY,
                                                                       defaultPublicKeyId,
                                                                       false));
        // provision routing provider to prevent lookup via discovery proxy during startup.
        provisionedDiscoveryEntries.put(systemServicesDomain + Routing.INTERFACE_NAME,
                                        new DiscoveryEntryWithMetaInfo(getVersionFromAnnotation(RoutingProvider.class),
                                                                       systemServicesDomain,
                                                                       Routing.INTERFACE_NAME,
                                                                       routingProviderParticipantId,
                                                                       providerQos,
                                                                       System.currentTimeMillis(),
                                                                       NO_EXPIRY,
                                                                       defaultPublicKeyId,
                                                                       true));

        this.proxyBuilderFactory = proxyBuilderFactory;
        this.systemServiceDomain = systemServicesDomain;
    }

    @Override
    public Future<Void> add(Callback<Void> callback, DiscoveryEntry discoveryEntry) {
        return getDefaultDiscoveryProxy().add(callback, discoveryEntry, false);
    }

    @Override
    public Future<Void> add(Callback<Void> callback, DiscoveryEntry discoveryEntry, Boolean awaitGlobalRegistration) {
        return getDefaultDiscoveryProxy().add(callback, discoveryEntry, awaitGlobalRegistration);
    }

    @Override
    public Future<DiscoveryEntryWithMetaInfo[]> lookup(final Callback<DiscoveryEntryWithMetaInfo[]> callback,
                                                       String[] domains,
                                                       String interfaceName,
                                                       DiscoveryQos discoveryQos) {
        final Set<DiscoveryEntryWithMetaInfo> discoveryEntries = new HashSet<>();
        Set<String> missingDomains = new HashSet<>();
        for (String domain : domains) {
            if (provisionedDiscoveryEntries.containsKey(domain + interfaceName)) {
                DiscoveryEntryWithMetaInfo discoveryEntry = provisionedDiscoveryEntries.get(domain + interfaceName);
                discoveryEntries.add(discoveryEntry);
            } else {
                missingDomains.add(domain);
            }
        }
        logger.trace("Found locally provisioned discovery entries: {}", discoveryEntries);
        final Future<DiscoveryEntryWithMetaInfo[]> discoveryEntryFuture = new Future<>();
        if (!missingDomains.isEmpty()) {
            logger.trace("Did not find entries for the following domains: {}", missingDomains);
            // discoveryProxy must not be garbage collected before the callback has been invoked
            // because otherwise the routingEntry might get removed early and the response from
            // CC cannot be routed anymore
            final DiscoveryProxy discoveryProxy = getDefaultDiscoveryProxy();
            MessagingQos messagingQos = new MessagingQos(discoveryQos.getDiscoveryTimeout());
            final ArrayList<DiscoveryProxy> keepReferenceArrayList = new ArrayList<DiscoveryProxy>();
            keepReferenceArrayList.add(discoveryProxy);

            Callback<DiscoveryEntryWithMetaInfo[]> newCallback = new Callback<DiscoveryEntryWithMetaInfo[]>() {

                @Override
                public void onFailure(JoynrRuntimeException error) {
                    keepReferenceArrayList.clear();
                    logger.trace("discoveryProxy.lookup onFailure: {}", error);
                    callback.onFailure(error);
                    discoveryEntryFuture.onFailure(error);
                }

                @Override
                public void onSuccess(DiscoveryEntryWithMetaInfo[] entries) {
                    keepReferenceArrayList.clear();
                    assert entries != null : "Entries must not be null.";
                    logger.trace("Globally found entries for missing domains: {}", Arrays.toString(entries));

                    Collections.addAll(discoveryEntries, entries);
                    resolveDiscoveryEntriesFutureWithEntries(discoveryEntryFuture, discoveryEntries, callback);
                }
            };
            String[] missingDomainsArray = new String[missingDomains.size()];
            missingDomains.toArray(missingDomainsArray);
            discoveryProxy.lookup(newCallback, missingDomainsArray, interfaceName, discoveryQos, messagingQos);
        } else {
            resolveDiscoveryEntriesFutureWithEntries(discoveryEntryFuture, discoveryEntries, callback);
        }
        return discoveryEntryFuture;
    }

    private void resolveDiscoveryEntriesFutureWithEntries(Future<DiscoveryEntryWithMetaInfo[]> future,
                                                          Set<DiscoveryEntryWithMetaInfo> discoveryEntries,
                                                          Callback<DiscoveryEntryWithMetaInfo[]> callback) {
        DiscoveryEntryWithMetaInfo[] discoveryEntriesArray = new DiscoveryEntryWithMetaInfo[discoveryEntries.size()];
        discoveryEntries.toArray(discoveryEntriesArray);
        future.resolve((Object) discoveryEntriesArray);
        callback.resolve((Object) discoveryEntriesArray);
    }

    @Override
    public Future<DiscoveryEntryWithMetaInfo> lookup(Callback<DiscoveryEntryWithMetaInfo> callback,
                                                     String participantId) {
        return getDefaultDiscoveryProxy().lookup(callback, participantId);
    }

    @Override
    public Future<Void> remove(Callback<Void> callback, String participantId) {
        return getDefaultDiscoveryProxy().remove(callback, participantId);
    }

    public void forceQueryOfDiscoveryProxy() {
        getDefaultDiscoveryProxy();
    }

    private DiscoveryProxy getDefaultDiscoveryProxy() {
        if (discoveryProxy == null) {
            // extend default ttl by 10 seconds to allow the cluster controller to handle timeout for
            // global discovery requests and send back the response to discoveryProxy.
            // Note that ConfigurableMessagingSettings.PROPERTY_MESSAGING_MAXIMUM_TTL_MS must be
            // larger than the resulting value here.
            MessagingQos internalMessagingQos = new MessagingQos();
            internalMessagingQos.setTtl_ms(internalMessagingQos.getRoundTripTtl_ms() + 10000);
            discoveryProxy = proxyBuilderFactory.get(systemServiceDomain, DiscoveryProxy.class)
                                                .setMessagingQos(internalMessagingQos)
                                                .build();
        }

        return discoveryProxy;
    }
}
