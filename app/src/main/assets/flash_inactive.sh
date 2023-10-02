#!/system/bin/sh
echo -------------------------

# -----------------------------------------------------------------------------
# See if we have boot / init_boot / ota_support
# -----------------------------------------------------------------------------
HAS_INIT_BOOT=0
AB_OTA_PARTITIONS=$(/bin/getprop ro.product.ab_ota_partitions)
if [[ "$AB_OTA_PARTITIONS" == *,init_boot,* ]]; then
    HAS_INIT_BOOT=1
    echo "HAS_INIT_BOOT:     $HAS_INIT_BOOT"
    STOCK=init_boot
elif [[ "$AB_OTA_PARTITIONS" == *,boot,* ]]; then
    HAS_BOOT=1
    echo "HAS_BOOT:          $HAS_BOOT"
    STOCK=boot
else
    echo "ERROR: Not a valid system"
    Exit 1
fi


# -----------------------------------------------------------------------------
# Get slots
# -----------------------------------------------------------------------------
ACTIVE_SLOT=$(/bin/getprop ro.boot.slot_suffix)
echo "ACTIVE_SLOT:       $ACTIVE_SLOT"
if [ "$ACTIVE_SLOT" = "_a" ]; then
    INACTIVE_SLOT="_b"
elif [ "$ACTIVE_SLOT" = "_b" ]; then
    INACTIVE_SLOT="_a"
else
    echo "ERROR: Unable to determine Current Slot"
    Exit 1
fi
echo "INACTIVE_SLOT:     $INACTIVE_SLOT"


# -----------------------------------------------------------------------------
# extract inactive slot's init_boot / boot partition
# -----------------------------------------------------------------------------
dd if=/dev/block/bootdevice/by-name/${STOCK}${INACTIVE_SLOT} of=/data/local/tmp/stock.img


# -----------------------------------------------------------------------------
# Create a patch
# -----------------------------------------------------------------------------
cd /data/adb/magisk
MAGISK_VERSION=$(magisk -c)
STOCK_SHA1=$(./magiskboot sha1 /data/local/tmp/stock.img | cut -c-8)
RECOVERYMODE=false
SYSTEM_ROOT=false
grep ' / ' /proc/mounts | grep -qv 'rootfs' && SYSTEM_ROOT=true
. ./util_functions.sh
get_flags
echo "MAGISK_VERSION:    $MAGISK_VERSION"
echo "STOCK_SHA1:        $STOCK_SHA1"
echo "SYSTEM_ROOT:       $SYSTEM_ROOT"
echo "KEEPVERITY:        $KEEPVERITY"
echo "KEEPFORCEENCRYPT:  $KEEPFORCEENCRYPT"
echo "RECOVERYMODE:      $RECOVERYMODE"
echo "PATCHVBMETAFLAG:   $PATCHVBMETAFLAG"
echo "ISENCRYPTED:       $ISENCRYPTED"
echo "VBMETAEXIST:       $VBMETAEXIST"
export KEEPVERITY KEEPFORCEENCRYPT RECOVERYMODE PATCHVBMETAFLAG ISENCRYPTED VBMETAEXIST SYSTEM_ROOT
echo -------------------------
echo "Creating a patch ..."
./magiskboot cleanup
./boot_patch.sh /data/local/tmp/stock.img
PATCH_SHA1=$(./magiskboot sha1 new-boot.img | cut -c-8)
echo "PATCH_SHA1:     $PATCH_SHA1"
PATCH_FILENAME=magisk_patched_${STOCK_SHA1}_${PATCH_SHA1}.img
echo "PATCH_FILENAME: $PATCH_FILENAME"
mv new-boot.img /data/local/tmp/${PATCH_FILENAME}
if [[ -s /data/local/tmp/${PATCH_FILENAME} ]]; then
    echo "Patching succeeded"
else
    echo "ERROR: Patching failed!"
fi


# -----------------------------------------------------------------------------
# Flash patch to INACTIVE_SLOT
# -----------------------------------------------------------------------------
echo Flashing ${STOCK}${INACTIVE_SLOT} partition ...
flash_image /data/local/tmp/$PATCH_FILENAME /dev/block/bootdevice/by-name/${STOCK}${INACTIVE_SLOT}
