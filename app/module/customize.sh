# SPDX-FileCopyrightText: 2023 Andrew Gunnerson
# SPDX-FileContributor: Modified by Pixel Updater contributors
# SPDX-License-Identifier: GPL-3.0-only

# The Android gradle plugin doesn't have a way to build native executables and
# make the outputs available to other tasks. Ideally, the executables would just
# be added to the module zip, but instead, we're forced to rename the files with
# a .so extension and bundle them into the .apk. We'll extract those here.

app_name=$(grep '^name=' "${MODPATH}/module.prop" | cut -d= -f2)
app_id=$(grep '^id=' "${MODPATH}/module.prop" | cut -d= -f2)
apk=$(find "${MODPATH}"/system/priv-app/"${app_name}"/ -name '*.apk')
abi=$(getprop ro.product.cpu.abi)

echo "App Name: ${app_name}"
echo "App ID: ${app_id}"
echo "APK: ${apk}"
echo "ABI: ${abi}"

run() {
    echo 'Extracting pixelupdater_selinux executable from APK'
    if ! (unzip "${apk}" -p lib/"${abi}"/libpixelupdater_selinux.so \
            > "${MODPATH}"/pixelupdater_selinux \
            && chmod -v +x "${MODPATH}"/pixelupdater_selinux); then
        echo "Failed to extract pixelupdater_selinux"
        return 1
    fi
}

if ! run 2>&1; then
    rm -rv "${MODPATH}" 2>&1
    exit 1
fi
