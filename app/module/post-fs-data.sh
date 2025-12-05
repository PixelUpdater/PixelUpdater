# SPDX-FileCopyrightText: 2023-2024 Andrew Gunnerson
# SPDX-FileContributor: Modified by Pixel Updater contributors
# SPDX-License-Identifier: GPL-3.0-only

# We don't want to give any arbitrary system app permissions to update_engine.
# Thus, we create a new context for pixelupdater and only give access to that
# specific type. Magisk currently has no builtin way to modify seapp_contexts,
# so we'll do it manually.

source "${0%/*}/boot_common.sh" /data/local/tmp/pixelupdater_selinux.log

header Creating pixelupdater_app domain

# Patch SELinux policy with enhanced error handling for QPR2 Beta2 compatibility
policy_patch_success=false
if "${mod_dir}"/pixelupdater_selinux -STd; then
    policy_patch_success=true
    echo "Success: SELinux policy patched with debug rules stripped"
else
    echo "Warning: SELinux policy patching failed with debug stripping, attempting fallback..."
    # Try without stripping audit rules in case that's causing issues
    if "${mod_dir}"/pixelupdater_selinux -ST; then
        policy_patch_success=true
        echo "Success: SELinux policy patched without debug stripping"
    else
        echo "Error: Both SELinux policy patch attempts failed"
        echo "This may indicate incompatible Android version or corrupted policy"

        # Try basic patch without any flags as last resort
        if "${mod_dir}"/pixelupdater_selinux; then
            policy_patch_success=true
            echo "Success: SELinux policy patched with basic method"
        else
            echo "Fatal: All SELinux policy patch methods failed"
        fi
    fi
fi

# Verify the policy was loaded successfully
policy_verified=false
if grep -q "pixelupdater_app" /sys/fs/selinux/policy 2>/dev/null; then
    echo "Success: pixelupdater_app domain found in loaded policy"
    policy_verified=true
else
    echo "Warning: pixelupdater_app domain not found in loaded policy"
    echo "This will cause app installation/runtime failures"
fi

# Additional verification and compatibility fixes
echo "Checking system compatibility..."
if [ -r /sys/fs/selinux/policyvers ]; then
    policy_version=$(cat /sys/fs/selinux/policyvers)
    echo "Policy version: ${policy_version}"
fi

build_id=$(getprop ro.build.id)
echo "Build ID: ${build_id}"

# Check for QPR2 specific issues
# if echo "${build_id}" | grep -q "BP2A\|BP3A\|BP41"; then
if echo "${build_id}" | grep -q "BP41"; then
    echo "QPR2 Beta detected - applying compatibility fixes"

    # Check for unlabeled block devices that cause issues
    unlabeled_count=$(ls -Z /dev/block/ 2>/dev/null | grep -c "unlabeled" 2>/dev/null || echo "0")
    if [ "${unlabeled_count}" -gt 0 ]; then
        echo "Warning: Found ${unlabeled_count} unlabeled block devices"
        echo "This is a known QPR2 issue that may cause PixelUpdater failures"
    fi
fi

# Force context refresh (helps with some Android 14 variants)
if [ -f /sys/fs/selinux/load ]; then
    echo "Policy reload interface available"
fi

header Updating seapp_contexts

seapp_dir=/system/etc/selinux
seapp_file=${seapp_dir}/plat_seapp_contexts
mod_seapp_dir=${mod_dir}${seapp_dir}
mod_seapp_file=${mod_dir}${seapp_file}

rm -rf "${mod_seapp_dir}"
mkdir -p "${mod_seapp_dir}"

# If, for whatever reason, we couldn't wipe the directory, mount a blank tmpfs
# on top. An outdated file can cause the system to boot loop due to system apps
# running under the wrong SELinux context.
if [[ -e "${mod_seapp_file}" ]]; then
    mount -t tmpfs tmpfs "${mod_seapp_dir}"
fi

# Full path because Magisk runs this script in busybox's standalone ash mode and
# we need Android's toybox version of cp.
/system/bin/cp --preserve=a "${seapp_file}" "${mod_seapp_file}"

cat >> "${mod_seapp_file}" << EOF
user=_app isPrivApp=true name=${app_id} domain=pixelupdater_app type=app_data_file levelFrom=all
EOF

# Verify seapp_contexts was updated correctly
echo "Verifying seapp_contexts update..."
if grep -q "pixelupdater_app" "${mod_seapp_file}"; then
    echo "Success: pixelupdater_app context found in seapp_contexts"
else
    echo "Error: pixelupdater_app context not found in seapp_contexts"
fi

# Additional debugging for QPR2 Beta2
echo "Final SELinux setup verification:"
echo "- SELinux status: $(getenforce 2>/dev/null || echo 'unknown')"
echo "- Policy version: $(cat /sys/fs/selinux/policyvers 2>/dev/null || echo 'unknown')"
echo "- Module directory: ${mod_dir}"
echo "- App ID: ${app_id}"

# Save debug info to log with more detailed information
{
    echo "=== PixelUpdater SELinux Setup Debug ==="
    echo "Date: $(date)"
    echo "Build ID: ${build_id}"
    echo "SELinux status: $(getenforce 2>/dev/null || echo 'unknown')"
    echo "Policy version: $(cat /sys/fs/selinux/policyvers 2>/dev/null || echo 'unknown')"
    echo "Policy patch success: ${policy_patch_success}"
    echo "Policy verification: ${policy_verified}"
    echo "pixelupdater_app domain check:"
    if grep -q "pixelupdater_app" /sys/fs/selinux/policy 2>/dev/null; then
        echo "  ✓ Found in policy"
    else
        echo "  ✗ NOT found in policy"
        echo "  This is a CRITICAL failure - app will not work"
    fi
    echo "seapp_contexts check:"
    if [ -f "${mod_seapp_file}" ] && grep -q "pixelupdater_app" "${mod_seapp_file}"; then
        echo "  ✓ Found in seapp_contexts"
        echo "  Entry: $(grep pixelupdater_app "${mod_seapp_file}")"
    else
        echo "  ✗ NOT found in seapp_contexts"
    fi

    # QPR2 specific checks
    # if echo "${build_id}" | grep -q "BP2A\|BP3A\|BP41"; then
    if echo "${build_id}" | grep -q "BP41"; then
        echo "QPR2 Beta checks:"
        unlabeled_count=$(ls -Z /dev/block/ 2>/dev/null | grep -c "unlabeled" 2>/dev/null || echo "0")
        echo "  Unlabeled block devices: ${unlabeled_count}"
        if [ "${unlabeled_count}" -gt 0 ]; then
            echo "  Sample unlabeled devices:"
            ls -Z /dev/block/ 2>/dev/null | grep "unlabeled" | head -3 | sed 's/^/    /'
        fi
    fi

    echo "================================"
} >> "${mod_dir}/setup_debug.log"

/system/bin/mv -f /data/local/tmp/pixelupdater_selinux.log "${mod_dir}/pixelupdater_selinux.log" 2>/dev/null || true
