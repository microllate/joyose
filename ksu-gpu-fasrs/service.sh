#!/system/bin/sh

MODDIR=${0%/*}
LOGDIR=/data/local/tmp/joyose-gpu-fasrs
mkdir -p "$LOGDIR"
chmod 755 "$MODDIR/gpu_fasrs.sh"

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 5
done

sleep 10
exec "$MODDIR/gpu_fasrs.sh" >> "$LOGDIR/controller.log" 2>&1
