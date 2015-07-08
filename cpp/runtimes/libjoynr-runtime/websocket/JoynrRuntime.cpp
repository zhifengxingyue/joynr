/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2013 BMW Car IT GmbH
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
#include "joynr/JoynrRuntime.h"
#include "libjoynr-runtime/LibJoynrRuntime.h"
#include "JoynrWebSocketRuntimeExecutor.h"

#include "joynr/SettingsMerger.h"

namespace joynr
{

JoynrRuntime* JoynrRuntime::createRuntime(const std::string& pathToLibjoynrSettings,
                                          const std::string& pathToMessagingSettings)
{
    QSettings* settings =
            SettingsMerger::mergeSettings(QString::fromStdString(pathToLibjoynrSettings));
    SettingsMerger::mergeSettings(QString::fromStdString(pathToMessagingSettings), settings);

    return LibJoynrRuntime::create(new JoynrWebSocketRuntimeExecutor(settings));
}

} // namespace joynr
