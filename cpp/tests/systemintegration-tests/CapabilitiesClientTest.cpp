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
#include <memory>
#include <string>

#include <gtest/gtest.h>
#include <gmock/gmock.h>

#include "joynr/ClusterControllerSettings.h"
#include "joynr/PrivateCopyAssign.h"
#include "joynr/JoynrClusterControllerRuntime.h"
#include "joynr/infrastructure/IGlobalCapabilitiesDirectory.h"
#include "joynr/types/Version.h"
#include "joynr/Settings.h"

#include "libjoynrclustercontroller/capabilities-client/CapabilitiesClient.h"
#include "libjoynrclustercontroller/messaging/MessagingPropertiesPersistence.h"

#include "tests/JoynrTest.h"
#include "tests/utils/PtrUtils.h"

using namespace ::testing;
using namespace joynr;

static const std::string messagingPropertiesPersistenceFileName(
        "CapabilitiesClientTest-joynr.settings");
static const std::string libJoynrSettingsFilename(
        "test-resources/libjoynrSystemIntegration1.settings");

class GlobalCapabilitiesMock
{
public:
    MOCK_METHOD1(capabilitiesReceived,
                 void(const std::vector<joynr::types::GlobalDiscoveryEntry>& results));
};

class CapabilitiesClientTest : public TestWithParam<std::string>
{
public:
    ADD_LOGGER(CapabilitiesClientTest)
    std::shared_ptr<JoynrClusterControllerRuntime> runtime;
    std::unique_ptr<Settings> settings;
    MessagingSettings messagingSettings;
    ClusterControllerSettings clusterControllerSettings;

    CapabilitiesClientTest()
            : runtime(),
              settings(std::make_unique<Settings>(GetParam())),
              messagingSettings(*settings),
              clusterControllerSettings(*settings)
    {
        messagingSettings.setMessagingPropertiesPersistenceFilename(
                messagingPropertiesPersistenceFileName);
        MessagingPropertiesPersistence storage(
                messagingSettings.getMessagingPropertiesPersistenceFilename());
        Settings libjoynrSettings{libJoynrSettingsFilename};
        Settings::merge(libjoynrSettings, *settings, false);

        runtime = std::make_shared<JoynrClusterControllerRuntime>(std::move(settings));
        runtime->init();
    }

    void SetUp() override
    {
        runtime->start();
    }

    ~CapabilitiesClientTest() override
    {
        runtime->shutdown();
        test::util::resetAndWaitUntilDestroyed(runtime);

        // Delete persisted files
        test::util::removeAllCreatedSettingsAndPersistencyFiles();
    }

private:
    DISALLOW_COPY_AND_ASSIGN(CapabilitiesClientTest);
};

TEST_P(CapabilitiesClientTest, registerAndRetrieveCapability)
{
    std::shared_ptr<ProxyBuilder<infrastructure::GlobalCapabilitiesDirectoryProxy>>
            capabilitiesProxyBuilder =
                    runtime->createProxyBuilder<infrastructure::GlobalCapabilitiesDirectoryProxy>(
                            messagingSettings.getDiscoveryDirectoriesDomain());

    DiscoveryQos discoveryQos(10000);
    discoveryQos.setArbitrationStrategy(DiscoveryQos::ArbitrationStrategy::FIXED_PARTICIPANT);
    discoveryQos.addCustomParameter(
            "fixedParticipantId", messagingSettings.getCapabilitiesDirectoryParticipantId());
    const MessagingQos messagingQos(10000);
    std::shared_ptr<infrastructure::GlobalCapabilitiesDirectoryProxy> cabilitiesProxy(
            capabilitiesProxyBuilder->setMessagingQos(messagingQos)
                    ->setDiscoveryQos(discoveryQos)
                    ->build());

    std::unique_ptr<CapabilitiesClient> capabilitiesClient(
            std::make_unique<CapabilitiesClient>(clusterControllerSettings));
    capabilitiesClient->setProxy(cabilitiesProxy, messagingQos);

    std::string capDomain("testDomain");
    std::string capInterface("testInterface");
    types::ProviderQos capProviderQos;
    std::string capParticipantId("testParticipantId");
    std::string capPublicKeyId("publicKeyId");
    joynr::types::Version providerVersion(47, 11);
    std::int64_t capLastSeenMs = 0;
    std::int64_t capExpiryDateMs = 1000;
    std::string capSerializedChannelAddress("testChannelId");
    types::GlobalDiscoveryEntry globalDiscoveryEntry(providerVersion,
                                                     capDomain,
                                                     capInterface,
                                                     capParticipantId,
                                                     capProviderQos,
                                                     capLastSeenMs,
                                                     capExpiryDateMs,
                                                     capPublicKeyId,
                                                     capSerializedChannelAddress);

    JOYNR_LOG_DEBUG(logger(), "Registering capabilities");
    capabilitiesClient->add(globalDiscoveryEntry,
                            []() {},
                            [](const joynr::exceptions::JoynrRuntimeException& /*exception*/) {});
    JOYNR_LOG_DEBUG(logger(), "Registered capabilities");

    auto callback = std::make_shared<GlobalCapabilitiesMock>();

    // use a semaphore to wait for capabilities to be received
    Semaphore semaphore(0);
    EXPECT_CALL(
            *callback, capabilitiesReceived(A<const std::vector<types::GlobalDiscoveryEntry>&>()))
            .WillRepeatedly(DoAll(ReleaseSemaphore(&semaphore), Return()));
    std::function<void(const std::vector<types::GlobalDiscoveryEntry>&)> onSuccess =
            [&](const std::vector<types::GlobalDiscoveryEntry>& capabilities) {
        callback->capabilitiesReceived(capabilities);
    };

    JOYNR_LOG_DEBUG(logger(), "get capabilities");
    std::int64_t defaultDiscoveryMessageTtl = messagingSettings.getDiscoveryMessagesTtl();
    capabilitiesClient->lookup({capDomain}, capInterface, defaultDiscoveryMessageTtl, onSuccess);
    semaphore.waitFor(std::chrono::seconds(10));
    JOYNR_LOG_DEBUG(logger(), "finished get capabilities");
}

using namespace std::string_literals;

INSTANTIATE_TEST_CASE_P(DISABLED_Http,
                        CapabilitiesClientTest,
                        testing::Values("test-resources/HttpSystemIntegrationTest1.settings"s));

INSTANTIATE_TEST_CASE_P(Mqtt,
                        CapabilitiesClientTest,
                        testing::Values("test-resources/MqttSystemIntegrationTest1.settings"s));
