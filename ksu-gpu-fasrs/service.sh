#!/system/bin/sh

MODDIR=${0%/*}
LOGDIR=/data/local/tmp/joyose-gpu-fasrs
STATE="$LOGDIR/state"
GPU_MAX=/sys/class/kgsl/kgsl-3d0/devfreq/max_freq
mkdir -p "$LOGDIR"
chmod 755 "$MODDIR/gpu_fasrs.sh"

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 5
done

sleep 10
"$MODDIR/gpu_fasrs.sh" >> "$LOGDIR/controller.log" 2>&1

# If the controller ever exits unexpectedly, make one last attempt to restore
# the session baseline before the service process exits.
if [ -r "$STATE" ] && [ -w "$GPU_MAX" ]; then
    baseline=$(grep '^baseline_max=' "$STATE" 2>/dev/null | cut -d= -f2)
    case "$baseline" in
        ''|*[!0-9]*) ;;
        *) printf '%s\n' "$baseline" > "$GPU_MAX" 2>/dev/null ;;
    esac
fi
