#!/system/bin/sh

# GPU-FASRS: frame-aware GPU controller for Adreno/KGSL.
# Target: 王者荣耀 only. The kernel governor remains in charge; we only move
# the devfreq max_freq ceiling after several consecutive frame-safe samples.

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

# Returns: fps max_interval_ms. Uses completed SurfaceFlinger presentation
# timestamps; no latency-clear is issued, so we do not disturb other tools.
frame_stats() {
    dumpsys SurfaceFlinger --latency SurfaceView 2>/dev/null | awk '
    BEGIN { n=0; prev=0; total=0; maxgap=0; pending="9223372036854775807" }
    NR==1 { next }
    NF==3 {
        c=$3+0
        if (c==pending || c<=0) next
        if (prev>0) {
            gap=(c-prev)/1000000
            if (gap>0 && gap<1000) {
                total++
                if (gap>maxgap) maxgap=gap
            }
        }
        prev=c
    }
    END {
        if (total<10) { print "NA NA"; exit }
        # The buffer contains recent completed frames; this is a smoothed
        # presentation-rate estimate rather than Choreographer callback rate.
        first=0; last=0
        # Re-read the stream is not possible in awk, so derive FPS from the
        # average inter-frame gap accumulated above.
        # total is the number of intervals; maxgap is a jank guard.
        # The average gap is reconstructed from the same timestamps by a
        # second pass in awk is unavailable; use the nominal refresh period
        # from the first line through a conservative count below.
        # Emit maxgap and interval count; caller derives FPS from refresh rate.
        print total, maxgap
    }'
}

# More direct frame-rate estimate: count unique completed timestamps in the
# last 128-frame buffer and divide by the observed timestamp span.
frame_fps() {
    dumpsys SurfaceFlinger --latency SurfaceView 2>/dev/null | awk '
    BEGIN { n=0; pending="9223372036854775807" }
    NR>1 && NF==3 {
        c=$3+0
        if (c==pending || c<=0) next
        a[++n]=c
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

# Keep the frequency table in ascending order. The original max is preserved
# as the top ceiling; we never touch min_freq or the governor.
FREQ_LIST=$(readv "$FREQS" | tr ' ' '\n' | awk '/^[0-9]+$/ {print}' | sort -n | uniq)
ORIGINAL_MAX=$(readv "$MAX_FREQ")
[ -n "$ORIGINAL_MAX" ] || {
    echo "START failed=no_max_freq"; exit 1
}

# Find the index of the current maximum in the sorted OPP list.
INDEX=$(printf '%s\n' "$FREQ_LIST" | awk -v m="$ORIGINAL_MAX" '$1<=m{idx++} END{print idx-1}')
[ -n "$INDEX" ] || INDEX=0

# Build the controller's ordered list from OPPs <= original max.
CAP_LIST=$(printf '%s\n' "$FREQ_LIST" | awk -v m="$ORIGINAL_MAX" '$1<=m')
CAP_COUNT=$(printf '%s\n' "$CAP_LIST" | awk 'NF{n++} END{print n+0}')
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
        # Re-capture the current ceiling in case the system changed it while
        # the game was not foreground. This becomes our session baseline.
        ORIGINAL_MAX=$(readv "$MAX_FREQ")
        CAP_LIST=$(printf '%s\n' "$FREQ_LIST" | awk -v m="$ORIGINAL_MAX" '$1<=m')
        CAP_COUNT=$(printf '%s\n' "$CAP_LIST" | awk 'NF{n++} END{print n+0}')
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

    printf 'pkg=%s\nfps=%s\ngpu_busy=%s\ncur_freq=%s\ncap_freq=%s\ntemp_c=%s\naction=%s\n' \
        "$pkg" "$fps" "$busy" "$cur" "$LAST_CAP" "$temp" "$action" > "$STATE"

    sleep "$SAMPLE_SEC"
done
