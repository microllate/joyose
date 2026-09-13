#!/system/bin/sh

STATE=/data/local/tmp/joyose-gpu-fasrs/state
GPU_MAX=/sys/class/kgsl/kgsl-3d0/devfreq/max_freq

if [ -r "$STATE" ] && [ -w "$GPU_MAX" ]; then
    baseline=$(grep '^baseline_max=' "$STATE" 2>/dev/null | cut -d= -f2)
    case "$baseline" in
        ''|*[!0-9]*) ;;
        *) printf '%s\n' "$baseline" > "$GPU_MAX" 2>/dev/null ;;
    esac
fi
rm -rf /data/local/tmp/joyose-gpu-fasrs 2>/dev/null
