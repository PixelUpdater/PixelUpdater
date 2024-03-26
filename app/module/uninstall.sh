APP_PACKAGE="com.github.pixelupdater.pixelupdater"
APP_UID=$(grep $APP_PACKAGE /data/system/packages.list | cut -d " " -f 2)
APP_DATA_DIR=$(dumpsys package $APP_PACKAGE | grep -E 'dataDir=' | cut -d'=' -f2)

if [ -d "/storage/emulated/0/Android/data/$APP_PACKAGE" ]; then
    EXTERNAL_DATA_DIR="/storage/emulated/0/Android/data/"
elif [ -d "/data/media/0/Android/data/$APP_PACKAGE" ]; then
    EXTERNAL_DATA_DIR="/data/media/0/Android/data/"
fi
APP_EXTERNAL_DATA_DIR="${EXTERNAL_DATA_DIR}${APP_PACKAGE}"

find /data/dalvik-cache -iname '*pixelupdater*' | xargs rm -f
find /data/system/package_cache -iname '*pixelupdater*' | xargs rm -f

if [ -n "$APP_UID" ]; then
    find /data/ota_package -user $APP_UID -exec rm -rf {} \;
fi

if [ -n "$APP_DATA_DIR" ]; then
    rm -rf $APP_DATA_DIR
fi

if [ -d "/data/data/$APP_PACKAGE" ]; then
    rm -rf /data/data/$APP_PACKAGE
fi

if [ -d $APP_EXTERNAL_DATA_DIR ]; then
    rm -rf $APP_EXTERNAL_DATA_DIR
fi
