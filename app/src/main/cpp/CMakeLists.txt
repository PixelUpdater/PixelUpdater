# SPDX-FileCopyrightText: 2023 Andrew Gunnerson
# SPDX-FileContributor: Modified by Pixel Updater contributors
# SPDX-License-Identifier: GPL-3.0-only

cmake_minimum_required(VERSION 3.22.1)

project(pixelupdater CXX C)

include(cmake/CompilerFlags.cmake)

add_subdirectory(external)
add_subdirectory(pixelupdater_selinux)
