cmake_minimum_required(VERSION 3.13)

project(appmodules)

# The packaged React Native release library is built with production flags.
# Keep generated Fabric/codegen objects ABI-compatible with it while retaining
# the app's existing assertion workaround for the release native startup fix.
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O2 -g -UNDEBUG -DREACT_NATIVE_PRODUCTION" CACHE STRING "" FORCE)

include(${REACT_ANDROID_DIR}/cmake-utils/ReactNative-application.cmake)
