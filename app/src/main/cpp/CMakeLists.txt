# SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
# Nahrazuje third_party/chiaki-ng/android/app/CMakeLists.txt (kopíruje se tam před sestavením).
# Oproti originálu: bez zvuku (oboe) a bez nativního dekodéru videa.

cmake_minimum_required(VERSION 3.10)

add_library(chiaki-jni SHARED
        src/main/cpp/chiaki-jni.c
        src/main/cpp/chiaki-jni.h
        src/main/cpp/log.h
        src/main/cpp/log.c)
target_link_libraries(chiaki-jni chiaki-lib)

find_library(ANDROID_LIB_LOG log)
find_library(ANDROID_LIB_ANDROID android)
target_link_libraries(chiaki-jni "${ANDROID_LIB_LOG}" "${ANDROID_LIB_ANDROID}")
