#!/system/bin/sh

GPU=/sys/class/kgsl/kgsl-3d0
LOG_INTERVAL=1

read_file() {
    [ -r "$1" ] && cat "$1" 2>/dev/null || echo "NA"
}

get_foreground() {
    dumpsys activity activities 2>/dev/null \
        | grep -m1 -E 'mResumedActivity|topResumedActivity' \
        | sed -n 's/.* \([^ ]*\/[^ ]*\).*/\1/p' \
        | cut -d/ -f1
}

get_temp() {
    for f in /sys/class/thermal/thermal_zone*/temp; do
        [ -r "$f" ] || continue
        type=$(cat "${f%/temp}/type" 2>/dev/null)
        case "$type" in
            *battery*|*Battery*|*battery-therm*|*pm8350*|*skin*)
                v=$(cat "$f" 2>/dev/null)
                case "$v" in
                    ''|*[!0-9]*) continue;;
                esac
                if [ "$v" -gt 1000 ]; then echo $((v / 1000)); else echo "$v"; fi
                return
                ;;
        esac
    done
    echo NA
}

mkdir -p /data/local/tmp/joyose-gpu-adaptive

while true; do
    ts=$(date '+%Y-%m-%d %H:%M:%S')
    pkg=$(get_foreground)
    freq=$(read_file "$GPU/devfreq/cur_freq")
    load=$(read_file "$GPU/gpu_busy_percentage")
    [ "$load" = "NA" ] && load=$(read_file "$GPU/devfreq/load")
    governor=$(read_file "$GPU/devfreq/governor")
    temp=$(get_temp)

    printf '%s pkg=%s freq=%s load=%s temp_c=%s governor=%s\n' \
        "$ts" "${pkg:-NA}" "$freq" "$load" "$temp" "$governor"

    sleep "$LOG_INTERVAL"
done
