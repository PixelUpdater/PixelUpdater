# SPDX-FileCopyrightText: 2023 Andrew Gunnerson
# SPDX-FileContributor: Modified by Pixel Updater contributors
# SPDX-License-Identifier: GPL-3.0-only

# We don't want to give any arbitrary system app permissions to update_engine.
# Thus, we create a new context for pixelupdater and only give access to that
# specific type. Magisk currently has no builtin way to modify seapp_contexts,
# so we'll do it manually.
#
# Android's fork of libselinux looks at /dev/selinux/apex_seapp_contexts. It's
# currently not used, but may be used in the future for selinux policy updates
# delivered via an apex image.

source "${0%/*}/boot_common.sh" /data/local/tmp/pixelupdater_selinux.log

header Creating pixelupdater_app domain

"${mod_dir}"/pixelupdater_selinux -ST

header Updating seapp_contexts

mkdir -p /dev/selinux

cat >> /dev/selinux/apex_seapp_contexts << EOF
user=_app isPrivApp=true name=${app_id} domain=pixelupdater_app type=app_data_file levelFrom=all
EOF

restorecon -Rv /dev/selinux
