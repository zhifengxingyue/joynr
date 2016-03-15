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

#include <cstddef>
#include <string>
#include <memory>

#include <boost/filesystem/operations.hpp>
#include <boost/filesystem/path.hpp>
#include <boost/program_options.hpp>

#include "joynr/JoynrRuntime.h"

#include "../common/Enum.h"
#include "PerformanceConsumer.h"

JOYNR_ENUM(SyncMode, (SYNC)(ASYNC));
JOYNR_ENUM(TestCase, (SEND_STRING)(SEND_BYTEARRAY)(SEND_STRUCT));

int main(int argc, char* argv[])
{
    namespace po = boost::program_options;

    std::string domain;
    std::size_t runs;
    SyncMode syncMode;
    TestCase testCase;
    std::size_t byteArraySize;
    std::size_t stringLength;

    auto validateRuns = [](std::size_t value) {
        if (value == 0) {
            throw po::validation_error(
                    po::validation_error::invalid_option_value, "runs", std::to_string(value));
        }
    };

    po::options_description desc("Available options");
    desc.add_options()("help,h", "produce help message")(
            "domain,d", po::value(&domain)->required(), "domain")(
            "runs,r", po::value(&runs)->required()->notifier(validateRuns), "number of runs")(
            "testCase,t",
            po::value(&testCase)->required(),
            "SEND_STRING|SEND_BYTEARRAY|SEND_STRUCT")(
            "syncMode,s", po::value(&syncMode)->required(), "SYNC|ASYNC")(
            "stringLength,l", po::value(&stringLength)->required(), "length of string")(
            "byteArraySize,b", po::value(&byteArraySize)->required(), "size of bytearray");

    try {
        po::variables_map vm;
        po::store(po::parse_command_line(argc, argv, desc), vm);
        po::notify(vm);
        if (vm.count("help")) {
            std::cout << desc << std::endl;
            return EXIT_FAILURE;
        }

        boost::filesystem::path appFilename = boost::filesystem::path(argv[0]);
        std::string appDirectory =
                boost::filesystem::system_complete(appFilename).parent_path().string();
        std::string pathToSettings(appDirectory + "/resources/performancetest-provider.settings");

        std::unique_ptr<joynr::JoynrRuntime> runtime(
                joynr::JoynrRuntime::createRuntime(pathToSettings));
        std::unique_ptr<joynr::IPerformanceConsumer> consumer;

        if (syncMode == SyncMode::SYNC) {
            consumer = std::make_unique<joynr::SyncEchoConsumer>(
                    std::move(runtime), runs, stringLength, byteArraySize, domain);
        } else {
            consumer = std::make_unique<joynr::AsyncEchoConsumer>(
                    std::move(runtime), runs, stringLength, byteArraySize, domain);
        }

        switch (testCase) {
        case TestCase::SEND_BYTEARRAY:
            consumer->runByteArray();
            break;
        case TestCase::SEND_STRING:
            consumer->runString();
            break;
        case TestCase::SEND_STRUCT:
            consumer->runStruct();
            break;
        }
    } catch (const std::exception& e) {
        std::cerr << e.what();
        return EXIT_FAILURE;
    }

    return EXIT_SUCCESS;
}