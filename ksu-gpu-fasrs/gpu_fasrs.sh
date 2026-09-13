#!/system/bin/sh

# GPU-FASRS: frame-aware GPU controller for Adreno/KGSL.
# Target: 王者荣耀 only. The kernel GPU governor remains in charge; this
# controller only moves the devfreq max_freq ceiling after stable frame data.

GPU=/sys/class/kgsl/kgsl-3d0
DEVFREQ="$GPU/devfreq"
MAX_FREQ="$DEVFREQ/max_freq"
CUR_FREQ="$DEVFREQ/cur_freq"
BUSY="$GPU/gpu_busy_percentage"
FREQS="$DEVFREQ/available_frequencies"
TARGET_PKG=com.tencent.tmgp.sgame
TARGET_FPS=90
SAMPLE_SEC=1
GOOD_REQUIRED=3
GOOD_FPS=89.5
BAD_FPS=89.0
GOOD_BUSY=72
BAD_BUSY=85
GOOD_MAX_INTERVAL_MS=22
LOGDIR=/data/local/tmp/joyose-gpu-fasrs
STATE="$LOGDIR/state"

mkdir -p "$LOGDIR"

readv() {
    [ -r "$1" ] && cat "$1" 2>/dev/null
}

foreground_pkg() {
    dumpsys activity activities 2>/dev/null \
        | grep -m1 -E 'mResumedActivity|topResumedActivity' \
        | grep -o -m1 'com.tencent.tmgp.sgame' || true
}

battery_temp() {
    for f in /sys/class/thermal/thermal_zone*/temp; do
        [ -r "$f" ] || continue
        type=$(readv "${f%/temp}/type")
        case "$type" in
            *battery*|*Battery*|*battery-therm*|*skin*)
                v=$(readv "$f")
                case "$v" in
                    ''|*[!0-9]*) continue ;;
                esac
                [ "$v" -gt 1000 ] && echo $((v / 1000)) || echo "$v"
                return
                ;;
        esac
    done
    echo NA
}

# Find a real SurfaceFlinger layer belonging to the target game. Modern
# games do not necessarily expose a layer literally named SurfaceView.
find_layer() {
    dumpsys SurfaceFlinger --list 2>/dev/null \
        | grep "$TARGET_PKG" \
        | head -n 1
}

# Returns: fps max_interval_ms. Uses completed SurfaceFlinger presentation
# timestamps; no latency-clear is issued.
frame_fps() {
    layer=$(find_layer)
    [ -n "$layer" ] || { echo "NA NA"; return; }

    dumpsys SurfaceFlinger --latency "$layer" 2>/dev/null | awk '
    BEGIN { n=0; pending="9223372036854775807"; prev=0 }
    NR>1 && NF==3 {
        c=$3+0
        if (c==pending || c<=0) next
        if (c==prev) next
        a[++n]=c
        prev=c
    }
    END {
        if (n<20) { print "NA NA"; exit }
        first=a[1]; last=a[n]
        span=last-first
        if (span<=0) { print "NA NA"; exit }
        fps=(n-1)*1000000000/span
        maxgap=0
        for (i=2;i<=n;i++) {
            gap=(a[i]-a[i-1])/1000000
            if (gap>maxgap) maxgap=gap
        }
        printf "%.2f %.2f\n", fps, maxgap
    }'
}

write_cap() {
    newcap="$1"
    oldcap=$(readv "$MAX_FREQ")
    [ -n "$oldcap" ] || return 1
    [ "$oldcap" = "$newcap" ] && return 0
    printf '%s\n' "$newcap" > "$MAX_FREQ" 2>/dev/null || return 1
    return 0
}

FREQ_LIST=$(readv "$FREQS" | tr ' ' '\n' | awk '/^[0-9]+$/ {print}' | sort -n | uniq)
ORIGINAL_MAX=$(readv "$MAX_FREQ")
[ -n "$ORIGINAL_MAX" ] || {
    echo "START failed=no_max_freq"; exit 1
}

CAP_LIST=$(printf '%s\n' "$FREQ_LIST" | awk -v m="$ORIGINAL_MAX" '$1<=m')
CAP_COUNT=$(printf '%s\n' "$CAP_LIST" | awk 'NF{n++} END{print n+0}')
[ "$CAP_COUNT" -ge 2 ] || {
    echo "START failed=insufficient_gpu_opps count=$CAP_COUNT"; exit 1
}

CAP_INDEX=$((CAP_COUNT - 1))
GOOD=0
ACTIVE=0
LAST_CAP="$ORIGINAL_MAX"

restore() {
    if [ -n "$ORIGINAL_MAX" ] && [ -w "$MAX_FREQ" ]; then
        printf '%s\n' "$ORIGINAL_MAX" > "$MAX_FREQ" 2>/dev/null
    fi
    echo "STOP restored_max=$ORIGINAL_MAX"
}
trap restore EXIT INT TERM

echo "START mode=gpu-fasrs target=$TARGET_PKG target_fps=$TARGET_FPS original_max=$ORIGINAL_MAX opps=$CAP_COUNT"

while true; do
    pkg=$(foreground_pkg)

    if [ "$pkg" != "$TARGET_PKG" ]; then
        if [ "$ACTIVE" = "1" ]; then
            write_cap "$ORIGINAL_MAX" && echo "EXIT_GAME cap=$ORIGINAL_MAX"
            ACTIVE=0
            LAST_CAP="$ORIGINAL_MAX"
        fi
        GOOD=0
        sleep 2
        continue
    fi

    if [ "$ACTIVE" = "0" ]; then
        ORIGINAL_MAX=$(readv "$MAX_FREQ")
        CAP_LIST=$(printf '%s\n' "$FREQ_LIST" | awk -v m="$ORIGINAL_MAX" '$1<=m')
        CAP_COUNT=$(printf '%s\n' "$CAP_LIST" | awk 'NF{n++} END{print n+0}')
        [ "$CAP_COUNT" -ge 2 ] || { sleep 2; continue; }
        CAP_INDEX=$((CAP_COUNT - 1))
        LAST_CAP="$ORIGINAL_MAX"
        ACTIVE=1
        GOOD=0
        echo "ENTER_GAME baseline_max=$ORIGINAL_MAX opps=$CAP_COUNT"
    fi

    cur=$(readv "$CUR_FREQ")
    busy=$(readv "$BUSY" | tr -cd '0-9')
    [ -n "$busy" ] || busy=NA
    stats=$(frame_fps)
    fps=$(printf '%s' "$stats" | awk '{print $1}')
    maxgap=$(printf '%s' "$stats" | awk '{print $2}')
    temp=$(battery_temp)

    action=hold
    if [ "$fps" != "NA" ] && [ "$busy" != "NA" ]; then
        good=$(awk -v f="$fps" -v b="$busy" -v g="$maxgap" \
            -v gf="$GOOD_FPS" -v gb="$GOOD_BUSY" -v gm="$GOOD_MAX_INTERVAL_MS" \
            'BEGIN{print (f>=gf && b<=gb && g<=gm) ? 1 : 0}')
        bad=$(awk -v f="$fps" -v b="$busy" -v g="$maxgap" \
            -v bf="$BAD_FPS" -v bb="$BAD_BUSY" \
            'BEGIN{print (f<bf || b>=bb || g>33) ? 1 : 0}')

        if [ "$bad" = "1" ]; then
            GOOD=0
            if [ "$CAP_INDEX" -lt $((CAP_COUNT - 1)) ]; then
                CAP_INDEX=$((CAP_INDEX + 1))
                newcap=$(printf '%s\n' "$CAP_LIST" | sed -n "$((CAP_INDEX + 1))p")
                if write_cap "$newcap"; then
                    LAST_CAP="$newcap"
                    action=raise
                fi
            fi
        elif [ "$good" = "1" ]; then
            GOOD=$((GOOD + 1))
            if [ "$GOOD" -ge "$GOOD_REQUIRED" ] && [ "$CAP_INDEX" -gt 0 ]; then
                CAP_INDEX=$((CAP_INDEX - 1))
                newcap=$(printf '%s\n' "$CAP_LIST" | sed -n "$((CAP_INDEX + 1))p")
                if write_cap "$newcap"; then
                    LAST_CAP="$newcap"
                    action=lower
                    GOOD=0
                fi
            fi
        else
            GOOD=0
        fi
    else
        GOOD=0
    fi

    printf '%s pkg=%s fps=%s maxgap_ms=%s gpu_busy=%s cur=%s cap=%s temp_c=%s action=%s good=%s\n' \
        "$(date '+%Y-%m-%d %H:%M:%S')" "$pkg" "$fps" "$maxgap" "$busy" "$cur" "$LAST_CAP" "$temp" "$action" "$GOOD"

    printf 'baseline_max=%s\npkg=%s\nfps=%s\ngpu_busy=%s\ncur_freq=%s\ncap_freq=%s\ntemp_c=%s\naction=%s\n' \
        "$ORIGINAL_MAX" "$pkg" "$fps" "$busy" "$cur" "$LAST_CAP" "$temp" "$action" > "$STATE"

    sleep "$SAMPLE_SEC"
done
