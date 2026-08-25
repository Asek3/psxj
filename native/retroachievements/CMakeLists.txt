cmake_minimum_required(VERSION 3.20)
project(psxj_ra C)

set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}")
foreach(CONFIG DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_${CONFIG} "${CMAKE_BINARY_DIR}")
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY_${CONFIG} "${CMAKE_BINARY_DIR}")
endforeach()

include(FetchContent)
FetchContent_Declare(
    rcheevos
    URL https://github.com/RetroAchievements/rcheevos/archive/refs/tags/v12.4.0.tar.gz
    URL_HASH SHA256=7fb1a43b8edfe727219d054ed868cc985bca54f331f9c2410f818dd3143df5d3
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(rcheevos)

file(GLOB RC_ROOT_SOURCES "${rcheevos_SOURCE_DIR}/src/*.c")
file(GLOB RC_RUNTIME_SOURCES "${rcheevos_SOURCE_DIR}/src/rcheevos/*.c")
file(GLOB RC_API_SOURCES "${rcheevos_SOURCE_DIR}/src/rapi/*.c")
file(GLOB RC_HASH_SOURCES "${rcheevos_SOURCE_DIR}/src/rhash/*.c")
list(FILTER RC_ROOT_SOURCES EXCLUDE REGEX "(rc_client_external|rc_libretro)\\.c$")

add_library(psxj_ra SHARED
    psxj_ra_bridge.c
    ${RC_ROOT_SOURCES}
    ${RC_RUNTIME_SOURCES}
    ${RC_API_SOURCES}
    ${RC_HASH_SOURCES}
)
target_include_directories(psxj_ra PRIVATE
    "${rcheevos_SOURCE_DIR}/include"
    "${rcheevos_SOURCE_DIR}/src"
)
target_compile_definitions(psxj_ra PRIVATE RC_DISABLE_LUA RC_CLIENT_SUPPORTS_HASH)
set_target_properties(psxj_ra PROPERTIES C_STANDARD 99 C_STANDARD_REQUIRED YES)
if(WIN32 OR CMAKE_SYSTEM_NAME STREQUAL "MSYS")
    set_target_properties(psxj_ra PROPERTIES PREFIX "")
endif()

if(MSVC)
    target_compile_options(psxj_ra PRIVATE /O2)
else()
    target_compile_options(psxj_ra PRIVATE -O3 -fvisibility=hidden)
endif()
