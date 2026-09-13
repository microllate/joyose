#!/system/bin/sh

PKG=com.tencent.tmgp.sgame
LOGDIR=/data/local/tmp/joyose-gpu-adaptive
OUT="$LOGDIR/frame_stats.log"
MAX_LINES=300

mkdir -p "$LOGDIR"

get_foreground() {
    dumpsys activity activities 2>/dev/null \
        | grep -m1 -E 'mResumedActivity|topResumedActivity' \
        | sed -n 's/.* \([^ ]*\/[^ ]*\).*/\1/p' \
        | cut -d/ -f1
}

trim_log() {
    [ -f "$OUT" ] || return 0
    lines=$(wc -l < "$OUT" 2>/dev/null)
    case "$lines" in
        ''|*[!0-9]*) return 0;;
    esac
    if [ "$lines" -gt "$MAX_LINES" ]; then
        tail -n "$MAX_LINES" "$OUT" > "$OUT.tmp" 2>/dev/null && mv "$OUT.tmp" "$OUT"
    fi
}

cleanup() {
    dumpsys SurfaceFlinger --timestats -disable >/dev/null 2>&1
}
trap cleanup EXIT INT TERM

printf '%s timestats=enable waiting_for=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$PKG" >> "$OUT"
dumpsys SurfaceFlinger --timestats -clear -enable >/dev/null 2>&1

while [ "$(get_foreground)" != "$PKG" ]; do
    sleep 1
done

printf '%s game_started=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$PKG" >> "$OUT"

while [ "$(get_foreground)" = "$PKG" ]; do
    sleep 1
done

printf '%s game_ended=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$PKG" >> "$OUT"

TMP="$LOGDIR/.timestats_dump"
dumpsys SurfaceFlinger --timestats -dump > "$TMP" 2>/dev/null

awk -v pkg="$PKG" '
    /^layerName = / {
        if (in_game && printed) exit
        in_game = (index($0, pkg) > 0)
        printed = 0
    }
    in_game {
        if ($0 ~ /layerName = / || $0 ~ /averageFPS/ || $0 ~ /totalFrames/ || $0 ~ /presentToPresent histogram/) {
            print
            printed = 1
        }
    }
' "$TMP" >> "$OUT"

rm -f "$TMP"
trim_log
printf '%s timestats=disabled\n' "$(date '+%Y-%m-%d %H:%M:%S')" >> "$OUT"
trim_log
