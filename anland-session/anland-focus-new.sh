#!/system/bin/sh
# anland-focus-new.sh - front a newly created anland window task.
#
# anland 6 gives every X toplevel its own AwlWindowActivity task, but Android
# keeps a single task visible at a time: a task created while another one is
# focused starts behind it (visible=false). Steam therefore keeps the screen and
# a game launched from its Play button runs invisibly - the game is alive, its
# X window is mapped, yet nothing changes on the display until that task is
# brought forward.
#
# This is the host-side focus policy for that gap: poll the activity stack and
# front each task that appears after startup. It mirrors what a desktop window
# manager does for a newly mapped toplevel (raise + focus) and is deliberately
# dumb about which window it is - dialogs and game windows both want the front.
#
# Runs on the Android host (shell uid); no root needed for "am task focus".
#
# Usage:
#   anland-focus-new.sh                 watch until killed
#   anland-focus-new.sh --once 120      exit after fronting one task (120 s cap)
#
# Tunables: ANLAND_FOCUS_POLL (default 2 s).

PKG=com.anlandnext
POLL=${ANLAND_FOCUS_POLL:-2}
ONCE=0
CAP=0

while [ $# -gt 0 ]; do
    case "$1" in
        --once) ONCE=1; shift
                [ -n "$1" ] && CAP=$1 && shift ;;
        *) echo "usage: $0 [--once [seconds]]" >&2; exit 2 ;;
    esac
done

tasks() {
    dumpsys activity activities 2>/dev/null \
        | grep -oE "#[0-9]+ type=standard A=[0-9]+:$PKG" \
        | grep -oE "#[0-9]+" | tr -d '#' | sort -u
}

# Everything already on screen is the baseline; a watcher that starts after
# Steam is up therefore only reacts to windows created later.
SEEN=" $(tasks | tr '\n' ' ') "
echo "anland-focus-new: baseline:$SEEN" >&2

END=0
[ "$CAP" -gt 0 ] && END=$(( $(date +%s) + CAP ))

while :; do
    for t in $(tasks); do
        case "$SEEN" in
            *" $t "*) continue ;;
        esac
        SEEN="$SEEN$t "
        am task focus "$t" >/dev/null 2>&1
        echo "anland-focus-new: task $t fronted"
        [ "$ONCE" = 1 ] && exit 0
    done
    [ "$END" -gt 0 ] && [ "$(date +%s)" -ge "$END" ] && exit 1
    sleep "$POLL"
done
