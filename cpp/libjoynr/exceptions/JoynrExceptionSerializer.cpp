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
#include <chrono>

#include "joynr/exceptions/JoynrExceptionSerializer.h"

#include <ostream>

#include "joynr/SerializerRegistry.h"
#include "joynr/JoynrTypeId.h"
#include "joynr/IDeserializer.h"
#include "joynr/PrimitiveDeserializer.h"

namespace joynr
{

// Register the JoynrRuntimeException type id and serializer/deserializer
static const bool isJoynrRuntimeExceptionRegistered =
        SerializerRegistry::registerType<exceptions::JoynrRuntimeException>(
                exceptions::JoynrRuntimeException::TYPE_NAME());
// Register the ProviderRuntimeException type id and serializer/deserializer
static const bool isProviderRuntimeExceptionRegistered =
        SerializerRegistry::registerType<exceptions::ProviderRuntimeException>(
                exceptions::ProviderRuntimeException::TYPE_NAME());
// Register the DiscoveryException type id and serializer/deserializer
static const bool isDiscoveryExceptionRegistered =
        SerializerRegistry::registerType<exceptions::DiscoveryException>(
                exceptions::DiscoveryException::TYPE_NAME());
// Register the ApplicationException type id and serializer/deserializer
static const bool isApplicationExceptionRegistered =
        SerializerRegistry::registerType<exceptions::ApplicationException>(
                exceptions::ApplicationException::TYPE_NAME());
// Register the JoynrTimeOutException type id and serializer/deserializer
static const bool isJoynrTimeOutExceptionRegistered =
        SerializerRegistry::registerType<exceptions::JoynrTimeOutException>(
                exceptions::JoynrTimeOutException::TYPE_NAME());
// Register the PublicationMissedException type id and serializer/deserializer
static const bool isPublicationMissedExceptionRegistered =
        SerializerRegistry::registerType<exceptions::PublicationMissedException>(
                exceptions::PublicationMissedException::TYPE_NAME());
// Register the MethodInvocationException type id and serializer/deserializer
static const bool isMethodInvocationExceptionRegistered =
        SerializerRegistry::registerType<exceptions::MethodInvocationException>(
                exceptions::MethodInvocationException::TYPE_NAME());
static const bool isJoynrMessageNotSentExceptionRegistered =
        SerializerRegistry::registerType<joynr::exceptions::JoynrMessageNotSentException>(
                exceptions::JoynrMessageNotSentException::TYPE_NAME());
static const bool isJoynrJoynrDelayMessageExceptionRegistered =
        SerializerRegistry::registerType<joynr::exceptions::JoynrDelayMessageException>(
                exceptions::JoynrDelayMessageException::TYPE_NAME());

template <>
void ClassDeserializerImpl<exceptions::ApplicationException>::deserialize(
        exceptions::ApplicationException& t,
        IObject& o)
{
    while (o.hasNextField()) {
        IField& field = o.nextField();
        if (field.name() == "detailMessage") {
            t.setMessage(field.value());
        } else if (field.name() == "error") {
            IObject& error = field.value();
            std::shared_ptr<IPrimitiveDeserializer> deserializer;
            while (error.hasNextField()) {
                IField& errorField = error.nextField();
                if (errorField.name() == "_typeName") {
                    t.setErrorTypeName(errorField.value());
                    deserializer =
                            SerializerRegistry::getPrimitiveDeserializer(t.getErrorTypeName());
                } else if (errorField.name() == "name") {
                    t.setName(errorField.value());
                    // we assume that the _typeName is contained before the name field in the json
                    if (deserializer.get() != nullptr) {
                        t.setError(deserializer->deserializeVariant(errorField.value()));
                    } else {
                        throw joynr::exceptions::JoynrRuntimeException(
                                "Received ApplicationException does not contain a valid error "
                                "enumeration.");
                    }
                }
            }
        }
    }
}

template <>
void ClassDeserializerImpl<exceptions::MethodInvocationException>::deserialize(
        exceptions::MethodInvocationException& t,
        IObject& o)
{
    while (o.hasNextField()) {
        IField& field = o.nextField();
        if (field.name() == "detailMessage") {
            t.setMessage(field.value());
        } else if (field.name() == "providerVersion") {
            IObject& providerVersionObject = field.value();
            Version providerVersion;
            ClassDeserializerImpl<Version>::deserialize(providerVersion, providerVersionObject);
            t.setProviderVersion(providerVersion);
        }
    }
}

template <>
void ClassDeserializerImpl<exceptions::JoynrRuntimeException>::deserialize(
        exceptions::JoynrRuntimeException& t,
        IObject& o)
{
    while (o.hasNextField()) {
        IField& field = o.nextField();
        if (field.name() == "detailMessage") {
            t.setMessage(field.value());
        }
    }
}
template <>
void ClassDeserializerImpl<exceptions::ProviderRuntimeException>::deserialize(
        exceptions::ProviderRuntimeException& t,
        IObject& o)
{
    ClassDeserializerImpl<exceptions::JoynrRuntimeException>::deserialize(t, o);
}
template <>
void ClassDeserializerImpl<exceptions::DiscoveryException>::deserialize(
        exceptions::DiscoveryException& t,
        IObject& o)
{
    ClassDeserializerImpl<exceptions::JoynrRuntimeException>::deserialize(t, o);
}
template <>
void ClassDeserializerImpl<exceptions::JoynrTimeOutException>::deserialize(
        exceptions::JoynrTimeOutException& t,
        IObject& o)
{
    ClassDeserializerImpl<exceptions::JoynrRuntimeException>::deserialize(t, o);
}
template <>
void ClassDeserializerImpl<exceptions::PublicationMissedException>::deserialize(
        exceptions::PublicationMissedException& t,
        IObject& o)
{
    while (o.hasNextField()) {
        IField& field = o.nextField();
        if (field.name() == "subscriptionId") {
            t.setSubscriptionId(field.value());
        }
    }
}
template <>
void ClassDeserializerImpl<exceptions::JoynrMessageNotSentException>::deserialize(
        exceptions::JoynrMessageNotSentException& t,
        IObject& o)
{
    ClassDeserializerImpl<exceptions::JoynrRuntimeException>::deserialize(t, o);
}
template <>
void ClassDeserializerImpl<exceptions::JoynrDelayMessageException>::deserialize(
        exceptions::JoynrDelayMessageException& t,
        IObject& o)
{
    while (o.hasNextField()) {
        IField& field = o.nextField();
        if (field.name() == "detailMessage") {
            t.setMessage(field.value());
        } else if (field.name() == "delayMs") {
            t.setDelayMs(std::chrono::milliseconds(field.value().getIntType<int64_t>()));
        }
    }
}

void initSerialization(const std::string& typeName, std::ostream& stream)
{
    stream << R"({)";
    stream << R"("_typeName":")" << typeName << R"(",)";
}

void serializeExceptionWithDetailMessage(const std::string& typeName,
                                         const exceptions::JoynrException& exception,
                                         std::ostream& stream)
{
    initSerialization(typeName, stream);
    stream << R"("detailMessage": ")" << exception.getMessage() << R"(")";
    stream << "}";
}

template <>
void ClassSerializerImpl<exceptions::ApplicationException>::serialize(
        const exceptions::ApplicationException& exception,
        std::ostream& stream)
{
    initSerialization(JoynrTypeId<exceptions::ApplicationException>::getTypeName(), stream);
    if (!exception.getMessage().empty()) {
        stream << R"("detailMessage": ")" << exception.getMessage() << R"(",)";
    }
    stream << R"("error": {)";
    stream << R"("_typeName":")" << exception.getErrorTypeName() << R"(",)";
    stream << R"("name": ")" << exception.getName() << R"(")";
    stream << "}"; // error
    stream << "}"; // exception
}

template <>
void ClassSerializerImpl<exceptions::MethodInvocationException>::serialize(
        const exceptions::MethodInvocationException& exception,
        std::ostream& stream)
{
    initSerialization(JoynrTypeId<exceptions::MethodInvocationException>::getTypeName(), stream);
    if (!exception.getMessage().empty()) {
        stream << R"("detailMessage": ")" << exception.getMessage() << R"(",)";
    }
    stream << R"("providerVersion": )";
    ClassSerializerImpl<Version>::serialize(exception.getProviderVersion(), stream);
    stream << "}"; // exception
}

template <>
void ClassSerializerImpl<exceptions::JoynrRuntimeException>::serialize(
        const exceptions::JoynrRuntimeException& exception,
        std::ostream& stream)
{
    serializeExceptionWithDetailMessage(
            JoynrTypeId<exceptions::JoynrRuntimeException>::getTypeName(), exception, stream);
}
template <>
void ClassSerializerImpl<exceptions::ProviderRuntimeException>::serialize(
        const exceptions::ProviderRuntimeException& exception,
        std::ostream& stream)
{
    serializeExceptionWithDetailMessage(
            JoynrTypeId<exceptions::ProviderRuntimeException>::getTypeName(), exception, stream);
}
template <>
void ClassSerializerImpl<exceptions::DiscoveryException>::serialize(
        const exceptions::DiscoveryException& exception,
        std::ostream& stream)
{
    serializeExceptionWithDetailMessage(
            JoynrTypeId<exceptions::DiscoveryException>::getTypeName(), exception, stream);
}
template <>
void ClassSerializerImpl<exceptions::JoynrTimeOutException>::serialize(
        const exceptions::JoynrTimeOutException& exception,
        std::ostream& stream)
{
    serializeExceptionWithDetailMessage(
            JoynrTypeId<exceptions::JoynrTimeOutException>::getTypeName(), exception, stream);
}
template <>
void ClassSerializerImpl<exceptions::PublicationMissedException>::serialize(
        const exceptions::PublicationMissedException& exception,
        std::ostream& stream)
{
    initSerialization(JoynrTypeId<exceptions::PublicationMissedException>::getTypeName(), stream);
    stream << R"("subscriptionId": ")" << exception.getSubscriptionId() << R"(")";
    stream << "}";
}

template <>
void ClassSerializerImpl<exceptions::JoynrMessageNotSentException>::serialize(
        const exceptions::JoynrMessageNotSentException& exception,
        std::ostream& stream)
{
    serializeExceptionWithDetailMessage(
            JoynrTypeId<exceptions::JoynrMessageNotSentException>::getTypeName(),
            exception,
            stream);
}
template <>
void ClassSerializerImpl<exceptions::JoynrDelayMessageException>::serialize(
        const exceptions::JoynrDelayMessageException& exception,
        std::ostream& stream)
{
    initSerialization(JoynrTypeId<exceptions::JoynrDelayMessageException>::getTypeName(), stream);
    if (!exception.getMessage().empty()) {
        stream << R"("detailMessage": ")" << exception.getMessage() << R"(",)";
    }
    stream << R"("delayMs": ")" << exception.getDelayMs().count() << R"(")";
    stream << "}";
}

} // namespace joynr
