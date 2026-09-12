#!/system/bin/sh
# Joyose / PowerKeeper runtime monitor
# Diagnostic only: reads runtime state, does not modify anything.

last_app=""
last_gpu=""
last_cpu4=""
last_cpu7=""
last_refresh=""

get_app() {
    dumpsys activity activities 2>/dev/null | grep -m1 'mResumedActivity' | sed -E 's/.* ([A-Za-z0-9._]+)\/.*/\1/'
}

get_refresh() {
    dumpsys display 2>/dev/null | grep -m1 -oE 'mRefreshRate=[0-9.]+' | cut -d= -f2
}

while true; do
    app="$(get_app)"
    gpu="$(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null)"
    cpu4="$(cat /sys/devices/system/cpu/cpu4/cpufreq/scaling_cur_freq 2>/dev/null)"
    cpu7="$(cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq 2>/dev/null)"
    refresh="$(get_refresh)"

    if [ "$app" != "$last_app" ] || [ "$gpu" != "$last_gpu" ] || [ "$cpu4" != "$last_cpu4" ] || [ "$cpu7" != "$last_cpu7" ] || [ "$refresh" != "$last_refresh" ]; then
        printf '%s APP=%s GPU=%s CPU4=%s CPU7=%s REFRESH=%s\n' "$(date '+%H:%M:%S.%3N')" "$app" "$gpu" "$cpu4" "$cpu7" "$refresh"
        last_app="$app"
        last_gpu="$gpu"
        last_cpu4="$cpu4"
        last_cpu7="$cpu7"
        last_refresh="$refresh"
    fi

    sleep 0.05
done
