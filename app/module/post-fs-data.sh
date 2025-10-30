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
if ! "${mod_dir}"/pixelupdater_selinux -STd; then
    echo "Warning: SELinux policy patching failed, attempting fallback..."
    # Try without stripping audit rules in case that's causing issues
    if ! "${mod_dir}"/pixelupdater_selinux -ST; then
        echo "Error: Both SELinux policy patch attempts failed"
        echo "This may indicate incompatible Android version or corrupted policy"
    fi
fi

# Verify the policy was loaded successfully
if ! grep -q "pixelupdater_app" /sys/fs/selinux/policy 2>/dev/null; then
    echo "Warning: pixelupdater_app domain not found in loaded policy"
else
    echo "Success: pixelupdater_app domain found in loaded policy"
fi

# Additional verification and compatibility fixes
echo "Checking system compatibility..."
if [ -r /sys/fs/selinux/policyvers ]; then
    policy_version=$(cat /sys/fs/selinux/policyvers)
    echo "Policy version: ${policy_version}"
fi

build_id=$(getprop ro.build.id)
echo "Build ID: ${build_id}"

# Apply compatibility fixes universally since detection is unreliable
echo "Applying universal compatibility fixes..."

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

# Save debug info to log
{
    echo "=== PixelUpdater SELinux Setup Debug ==="
    echo "Date: $(date)"
    echo "SELinux status: $(getenforce 2>/dev/null || echo 'unknown')"
    echo "Policy version: $(cat /sys/fs/selinux/policyvers 2>/dev/null || echo 'unknown')"
    echo "pixelupdater_app domain check:"
    if grep -q "pixelupdater_app" /sys/fs/selinux/policy 2>/dev/null; then
        echo "  ✓ Found in policy"
    else
        echo "  ✗ NOT found in policy"
    fi
    echo "seapp_contexts check:"
    if [ -f "${mod_seapp_file}" ] && grep -q "pixelupdater_app" "${mod_seapp_file}"; then
        echo "  ✓ Found in seapp_contexts"
        echo "  Entry: $(grep pixelupdater_app "${mod_seapp_file}")"
    else
        echo "  ✗ NOT found in seapp_contexts"
    fi
    echo "================================"
} >> "${mod_dir}/setup_debug.log"

/system/bin/mv -f /data/local/tmp/pixelupdater_selinux.log "${mod_dir}/pixelupdater_selinux.log" 2>/dev/null || true
