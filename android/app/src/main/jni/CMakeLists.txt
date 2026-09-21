cmake_minimum_required(VERSION 3.13)

project(appmodules)

# Keep React Native's native assertions and debug string-convertible Fabric
# types consistent across the release C++ build. RN 0.87's generated Fabric
# descriptors otherwise crash at link time or startup when only the app
# module has NDEBUG removed.
set(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-O2 -g -UNDEBUG" CACHE STRING "" FORCE)
set(REACT_NATIVE_DEBUG_OPTIMIZED True CACHE BOOL "" FORCE)

include(${REACT_ANDROID_DIR}/cmake-utils/ReactNative-application.cmake)
