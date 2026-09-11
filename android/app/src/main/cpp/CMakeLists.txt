cmake_minimum_required(VERSION 3.22.1)
project(flibrary_android LANGUAGES CXX)

add_library(flibrary_android SHARED native_bridge.cpp)

target_compile_features(flibrary_android PRIVATE cxx_std_20)
