cmake_minimum_required(VERSION 3.22.1)
project("smollm")

# llama.cpp must be cloned at the project root as a submodule
# git submodule add https://github.com/ggerganov/llama.cpp.git
# Dynamic ggml backends + Vulkan GPU offload on arm64-v8a
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    # NDK has no vulkan.hpp; point VULKAN_HEADERS_DIR at a Khronos Vulkan-Headers checkout.
    if (DEFINED ENV{VULKAN_HEADERS_DIR})
        set(Vulkan_INCLUDE_DIR "$ENV{VULKAN_HEADERS_DIR}/include" CACHE PATH "" FORCE)
    endif()
    if (DEFINED ENV{SPIRV_HEADERS_DIR})
        include_directories(SYSTEM "$ENV{SPIRV_HEADERS_DIR}/include")
        list(APPEND CMAKE_PREFIX_PATH "$ENV{SPIRV_HEADERS_DIR}")
    endif()
    file(GLOB NDK_GLSLC "${ANDROID_NDK}/shader-tools/*/glslc")
    if (NDK_GLSLC)
        list(GET NDK_GLSLC 0 NDK_GLSLC_PATH)
        set(Vulkan_GLSLC_EXECUTABLE "${NDK_GLSLC_PATH}" CACHE FILEPATH "" FORCE)
    endif()
endif()

add_subdirectory(../../../../llama.cpp llama.cpp)
set(LLAMA_DIR_RELATIVE "../../../../llama.cpp")
get_filename_component(LLAMA_DIR ${LLAMA_DIR_RELATIVE} ABSOLUTE)

set(GGML_DIR ${LLAMA_DIR}/ggml)
set(COMMON_DIR ${LLAMA_DIR}/common)
set(VENDOR_DIR ${LLAMA_DIR}/vendor)

target_compile_options(
        llama
        PUBLIC
        -fvisibility=hidden -fvisibility-inlines-hidden
)
target_compile_options(
        llama
        PUBLIC
        -ffunction-sections -fdata-sections
)
target_link_options(
        llama
        PRIVATE
        -Wl,--gc-sections -flto
        -Wl,--exclude-libs,ALL
)

function(build_library target_name)
    add_library(
            ${target_name}
            SHARED
            LLMInference.cpp
            smollm.cpp
    )
    target_include_directories(
            ${target_name}
            PUBLIC
            ${COMMON_DIR}
            ${GGML_DIR}/include
            ${GGML_DIR}/src
            ${GGML_DIR}/src/ggml-cpu
            ${LLAMA_DIR}/include
            ${VENDOR_DIR}
    )
    target_compile_options(
            ${target_name}
            PUBLIC
            -fvisibility=hidden -fvisibility-inlines-hidden
    )
    target_compile_options(
            ${target_name}
            PUBLIC
            -ffunction-sections -fdata-sections
    )
    target_link_libraries(
            ${target_name}
            android log llama common
    )
    target_link_options(
            ${target_name}
            PRIVATE
            -Wl,--gc-sections -flto
            -Wl,--exclude-libs,ALL
    )
endfunction()

function(build_library_arm64 target_name cpu_flags)
    build_library(${target_name})
    target_compile_options(
            ${target_name}
            PUBLIC
            -DGGML_USE_CPU -DGGML_USE_CPU_AARCH64 ${cpu_flags} -O3
    )
endfunction()

function(build_library_universal target_name)
    build_library(${target_name})
    target_compile_options(
            ${target_name}
            PUBLIC
            -DGGML_USE_CPU -O3
    )
endfunction()

build_library_universal("smollm")

# GGUFReader library
set(TARGET_NAME_GGUF_READER ggufreader)
add_library(${TARGET_NAME_GGUF_READER} SHARED GGUFReader.cpp)
target_include_directories(
        ${TARGET_NAME_GGUF_READER}
        PUBLIC
        ${GGML_DIR}/include
)
target_compile_options(
        ${TARGET_NAME_GGUF_READER}
        PUBLIC
        -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections
)
target_link_libraries(
        ${TARGET_NAME_GGUF_READER}
        ggml
)
target_link_options(
        ${TARGET_NAME_GGUF_READER}
        PRIVATE
        -Wl,--gc-sections -flto
        -Wl,--exclude-libs,ALL
)
