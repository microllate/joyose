#!/system/bin/sh

MODDIR=${0%/*}
LOGDIR=/data/local/tmp/joyose-gpu-adaptive
LOGFILE="$LOGDIR/monitor.log"
GPU=/sys/class/kgsl/kgsl-3d0

mkdir -p "$LOGDIR"
chmod 755 "$MODDIR/gpu_monitor.sh"

# Stage 1 is observation-only. No GPU, CPU, governor, thermal, or perf-lock writes.
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 5
done

sleep 10
exec "$MODDIR/gpu_monitor.sh" >> "$LOGFILE" 2>&1
