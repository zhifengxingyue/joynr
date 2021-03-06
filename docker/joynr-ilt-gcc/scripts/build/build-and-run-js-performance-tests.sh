#!/bin/bash
./java-clean-build skipTests

./javascript-clean-build --skipTests
./cpp-clean-build.sh --buildtests OFF --enableclangformatter OFF --buildtype RELEASE --additionalcmakeargs "-DUSE_PLATFORM_MUESLI=OFF"

./run-js-performance-tests.sh
