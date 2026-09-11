cmake_minimum_required(VERSION 3.22.1)
project(flibrary_android LANGUAGES CXX)

set(FLIBRARY_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../..")
set(FLIBRARY_INPX_DIR "${FLIBRARY_ROOT}/src/home/inpx")

add_library(flibrary_android SHARED
    native_bridge.cpp
)

target_compile_features(flibrary_android PRIVATE cxx_std_23)

target_include_directories(flibrary_android PRIVATE
    "${FLIBRARY_INPX_DIR}"
)

target_compile_definitions(flibrary_android PRIVATE
    FLIBRARY_ANDROID=1
)
