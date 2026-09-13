#!/system/bin/sh

# GPU-FASRS control-path probe.
# IMPORTANT: observation only. Do not change GPU policy until the effective
# Qualcomm/KGSL control interface is proven. The previous controller observed
# max_freq=765MHz while cur_freq reached 900MHz, so max_freq is not trusted.

GPU=/sys/class/kgsl/kgsl-3d0
DEVFREQ="$GPU/devfreq"
MAX_FREQ="$DEVFREQ/max_freq"
MIN_FREQ="$DEVFREQ/min_freq"
CUR_FREQ="$DEVFREQ/cur_freq"
BUSY="$GPU/gpu_busy_percentage"
FREQS="$DEVFREQ/available_frequencies"
TARGET_PKG=com.tencent.tmgp.sgame
SAMPLE_SEC=1
LOGDIR=/data/local/tmp/joyose-gpu-fasrs
STATE="$LOGDIR/state"

mkdir -p "$LOGDIR"

readv() {
    [ -r "$1" ] && cat "$1" 2>/dev/null
}

foreground_pkg() {
    dumpsys activity activities 2>/dev/null \
        | grep -m1 -E 'mResumedActivity|topResumedActivity' \
        | grep -o -m1 "$TARGET_PKG" || true
}

battery_temp() {
    for f in /sys/class/thermal/thermal_zone*/temp; do
        [ -r "$f" ] || continue
        type=$(readv "${f%/temp}/type")
        case "$type" in
            *battery*|*Battery*|*battery-therm*|*skin*)
                v=$(readv "$f")
                case "$v" in ''|*[!0-9]*) continue ;; esac
                [ "$v" -gt 1000 ] && echo $((v / 1000)) || echo "$v"
                return
                ;;
        esac
    done
    echo NA
}

# Read every KGSL/devfreq control node that exists on this device. This is
# deliberately generic because vendor kernels expose different names.
node_snapshot() {
    for n in \
        max_freq min_freq cur_freq available_frequencies \
        max_clock_mhz min_clock_mhz clock_mhz freq_table_mhz \
        max_pwrlevel min_pwrlevel thermal_pwrlevel pwrscale \
        gpuclk max_gpuclk min_gpuclk; do
        f="$DEVFREQ/$n"
        [ -r "$f" ] && printf '%s=%s ' "$n" "$(readv "$f" | tr '\n' ' ')"
        f="$GPU/$n"
        [ -r "$f" ] && printf '%s=%s ' "$n" "$(readv "$f" | tr '\n' ' ')"
    done
    printf '\n'
}

# Android 15+/supported vendor builds may expose per-UID GPU work through
# dumpsys gpu --gpuwork. This is observation-only and does not clear state.
gpuwork_for_uid() {
    uid=$(cmd package list packages -U 2>/dev/null \
        | awk -v p="$TARGET_PKG" '$0 ~ ("package:" p " ") {for(i=1;i<=NF;i++) if($i ~ /^uid:/){sub("uid:","",$i); print $i; exit}}')
    [ -n "$uid" ] || { echo "uid=NA"; return; }
    line=$(dumpsys gpu --gpuwork 2>/dev/null \
        | awk -v u="$uid" '$1 ~ /^[0-9]+$/ && $2==u {print; exit}')
    if [ -n "$line" ]; then
        echo "uid=$uid row=$line"
    else
        echo "uid=$uid row=NA"
    fi
}

# A no-op readback test: NEVER write a new frequency in this version.
# The previous implementation proved only that max_freq can be read, not that
# it is the final effective ceiling. We therefore refuse all control writes.
control_status() {
    if [ -w "$MAX_FREQ" ]; then
        echo "max_freq_writable=yes control_write=DISABLED"
    else
        echo "max_freq_writable=no control_write=DISABLED"
    fi
}

ORIGINAL_MAX=$(readv "$MAX_FREQ")

printf 'START mode=gpu-fasrs-probe target=%s original_max=%s sample_sec=%s\n' \
    "$TARGET_PKG" "$ORIGINAL_MAX" "$SAMPLE_SEC"
printf 'START %s\n' "$(control_status)"
printf 'FREQS %s\n' "$(readv "$FREQS" | tr '\n' ' ')"

while true; do
    pkg=$(foreground_pkg)
    if [ "$pkg" = "$TARGET_PKG" ]; then
        cur=$(readv "$CUR_FREQ")
        busy=$(readv "$BUSY" | tr -cd '0-9')
        [ -n "$busy" ] || busy=NA
        max=$(readv "$MAX_FREQ")
        min=$(readv "$MIN_FREQ")
        temp=$(battery_temp)
        nodes=$(node_snapshot)
        gpuwork=$(gpuwork_for_uid)
        printf '%s pkg=%s cur=%s busy=%s max=%s min=%s temp_c=%s %s gpuwork=%s\n' \
            "$(date '+%Y-%m-%d %H:%M:%S')" "$pkg" "$cur" "$busy" "$max" "$min" "$temp" "$nodes" "$gpuwork"
        printf 'pkg=%s\ncur_freq=%s\ngpu_busy=%s\nmax_freq=%s\nmin_freq=%s\ntemp_c=%s\n%s\n%s\n' \
            "$pkg" "$cur" "$busy" "$max" "$min" "$temp" "$nodes" "$gpuwork" > "$STATE"
    else
        sleep 2
    fi
    sleep "$SAMPLE_SEC"
done
