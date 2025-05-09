#!/system/bin/sh
# SPDX-FileCopyrightText: 2023-2024 Pixel Updater contributors
# SPDX-License-Identifier: GPL-3.0-only

# This script will be executed when Magisk removes the module

# Create secure log location
LOGDIR="/data/adb/PixelUpdater"
mkdir -p $LOGDIR 2>/dev/null
LOGFILE="$LOGDIR/uninstall.log"
rm -f $LOGFILE

# Redirect stdout and stderr to log file
exec > $LOGFILE 2>&1

echo "Starting PixelUpdater uninstall process at $(date)"

# Define package info
APP_PACKAGE="com.github.pixelupdater.pixelupdater"
APP_UID=$(grep $APP_PACKAGE /data/system/packages.list | cut -d " " -f 2)
APP_DATA_DIR=$(dumpsys package $APP_PACKAGE | grep -E 'dataDir=' | cut -d'=' -f2)
APP_EXTERNAL_DATA_DIR="/storage/emulated/0/Android/data/${APP_PACKAGE}"
echo "Package UID: $APP_UID"
echo "Data directory: $APP_DATA_DIR"
echo "External data directory: $APP_EXTERNAL_DATA_DIR"
echo "Log directory: $LOGDIR"
echo "Log file: $LOGFILE"

# Check write permissions before proceeding
TESTFILE="$LOGDIR/write_test"
if ! touch "$TESTFILE" 2>/dev/null; then
    echo "ERROR: Unable to write to filesystem, aborting uninstall cleanup"
    exit 1
else
    rm -f "$TESTFILE"
    echo "Write permissions verified"
fi

# Actively kill any running processes
for pid in $(ps -ef | grep pixelupdater | grep -v grep | awk '{print $2}'); do
    echo "Killing process $pid"
    kill -9 "$pid" 2>/dev/null
done

echo "Cleaning up system files"
find /data/dalvik-cache -name 'system@priv-app@PixelUpdater@PixelUpdater.apk@*' | xargs rm -f
find /data/system/package_cache -name 'PixelUpdater-*' | xargs rm -f

if [ -n "$APP_UID" ]; then
    echo "Cleaning OTA packages for UID $APP_UID"
    find /data/ota_package -user $APP_UID | xargs rm -f
fi

# Clean up JobScheduler entries
echo "Cleaning JobScheduler entries"

# Known locations for job files
JOB_LOCATIONS="/data/system/job /data/system/users/*/jobscheduler /data/system/jobscheduler"
for location in $JOB_LOCATIONS; do
    for job_file in $(find $location -name "*.xml" 2>/dev/null); do
        if grep -q "pixelupdater" "$job_file" 2>/dev/null; then
            echo "Found and cleaning references in $job_file"
            # Create targeted backups in our secure log directory
            cp "$job_file" "$LOGDIR/$(basename $job_file).bak" 2>/dev/null
            sed -i '/pixelupdater/d' "$job_file"
            chown system:system "$job_file"
            chmod 600 "$job_file"
        fi
    done
done

# Try to cancel jobs via service call only if service is available
if dumpsys jobscheduler > /dev/null 2>&1; then
    echo "JobScheduler service is available, attempting to cancel jobs"
    service call jobscheduler 21 s16 "com.github.pixelupdater.pixelupdater" > /dev/null 2>&1
else
    echo "JobScheduler service not available at this time"
fi

# Clean up app data
echo "Removing app data"
if [ -n "$APP_DATA_DIR" ]; then
    rm -rf $APP_DATA_DIR
fi

if [ -d "/data/data/$APP_PACKAGE" ]; then
    rm -rf /data/data/$APP_PACKAGE
fi

if [ -d $APP_EXTERNAL_DATA_DIR ]; then
    rm -rf $APP_EXTERNAL_DATA_DIR
fi

# Clean notification preferences
echo "Cleaning notification preferences"
NOTIF_FILES="/data/system/notification_policy.xml"
for file in $NOTIF_FILES; do
    if [ -f "$file" ] && grep -q "pixelupdater" "$file" 2>/dev/null; then
        echo "Cleaning $file"
        cp "$file" "$LOGDIR/$(basename $file).bak" 2>/dev/null
        sed -i '/pixelupdater/d' "$file"
        chown system:system "$file"
        chmod 600 "$file"
    fi
done

# Clean processed packages journal
echo "Cleaning processed packages journal"
JOURNAL_FILE="/data/system/processed_packages_journal"
if [ -f "$JOURNAL_FILE" ]; then
    cp "$JOURNAL_FILE" "$LOGDIR/processed_packages_journal.bak" 2>/dev/null
    # Create empty journal file (safer than deleting)
    echo "" > "$JOURNAL_FILE"
    chown system:system "$JOURNAL_FILE"
    chmod 600 "$JOURNAL_FILE"
fi

# Clean app hibernation state
echo "Cleaning app hibernation state"
HIB_FILES=$(find /data/system -name "*hibernation*" -type f 2>/dev/null)
for file in $HIB_FILES; do
    if grep -q "pixelupdater" "$file" 2>/dev/null; then
        echo "Cleaning $file"
        cp "$file" "$LOGDIR/$(basename $file).bak" 2>/dev/null
        sed -i '/<package.*pixelupdater/,/<\/package>/d' "$file"
        chown system:system "$file"
        chmod 600 "$file"
    fi
done

echo "PixelUpdater uninstall completed at $(date)"
echo "A reboot is recommended to fully clear all references"

exit 0
