# SPDX-FileCopyrightText: 2023 Andrew Gunnerson
# SPDX-FileContributor: Modified by Pixel Updater contributors
# SPDX-License-Identifier: GPL-3.0-only

add_executable(
    pixelupdater_selinux
    src/main.cpp
)

target_include_directories(
    pixelupdater_selinux
    PRIVATE
    include
)

target_compile_options(
    pixelupdater_selinux
    PRIVATE
    $<$<CXX_COMPILER_ID:Clang>:-Wno-keyword-macro>
)

set_target_properties(
    pixelupdater_selinux
    PROPERTIES
    POSITION_INDEPENDENT_CODE TRUE
)

target_link_libraries(
    pixelupdater_selinux
    PRIVATE
    interface.global.CXXVersion
    sepol
)

link_executable_statically(pixelupdater_selinux)

# Make the file look like a library or else it won't be added to the APK.
if(ANDROID)
    set_target_properties(
        pixelupdater_selinux
        PROPERTIES
        OUTPUT_NAME libpixelupdater_selinux.so
    )
endif()
