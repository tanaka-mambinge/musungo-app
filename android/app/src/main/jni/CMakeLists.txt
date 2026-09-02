cmake_minimum_required(VERSION 3.13)

project(appmodules)

# Keep React Native's native assertions enabled in the release C++ build.
# RN 0.87's generated Fabric descriptors otherwise crash during startup when
# the standard RelWithDebInfo flags define NDEBUG.
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O2 -g -UNDEBUG" CACHE STRING "" FORCE)

include(${REACT_ANDROID_DIR}/cmake-utils/ReactNative-application.cmake)
