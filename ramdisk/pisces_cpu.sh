#!/system/bin/sh
# pisces: keep all four A15 cores online, at battery-safe frequencies.
#
# Why: the cpuquiet "runnable" governor parks the system on CPU0.  The A9 UI
# workload here is GPU/sync bound (client composition), so the runnable thread
# count stays low, the governor never brings the other cores back, and the
# whole system - input dispatch included - ends up on a single 1.02 GHz core.
# That is what made scrolling feel frozen.
#
# Order matters: bring a core online first, then cap it.  A freshly onlined
# CPU gets a new cpufreq policy with the default 1.8 GHz limit, and 4 cores at
# 1.8 GHz draws enough current to sag the aged battery below the PMIC
# VSYS_MON threshold and reset the phone.
#
# Phase 1 (during boot) keeps the 510 MHz cap that the aged battery needs.
# Phase 2 raises it to 816 MHz once Android reports boot_completed.

BOOT_MAXF=${PISCES_CPU_BOOT_MAXF:-510000}
MAXF=${PISCES_CPU_MAXF:-1224000}

log() {
    echo "pisces_cpu: $*" > /dev/kmsg
}

cap_all() {
    for c in 0 1 2 3; do
        echo $1 > /sys/devices/system/cpu/cpu$c/cpufreq/scaling_max_freq 2>/dev/null
    done
}

i=0
while [ $i -lt 400 ]; do
    echo userspace > /sys/devices/system/cpu/cpuquiet/current_governor 2>/dev/null
    for c in 1 2 3; do
        echo 1 > /sys/devices/system/cpu/cpu$c/online 2>/dev/null
    done

    ready=1
    for c in 0 1 2 3; do
        [ -e /sys/devices/system/cpu/cpu$c/cpufreq/scaling_max_freq ] || ready=0
    done

    if [ $ready = 1 ] && [ "$(cat /sys/devices/system/cpu/online)" = "0-3" ]; then
        cap_all $BOOT_MAXF
        log "4 cores online, boot cap ${BOOT_MAXF} kHz (tries=$i)"
        break
    fi

    i=$((i + 1))
    sleep 0.2
done

if [ $ready != 1 ]; then
    log "giving up on hotplug, online=$(cat /sys/devices/system/cpu/online)"
    exit 1
fi

# Phase 2: only lift the cap after the framework is up.
i=0
while [ $i -lt 300 ]; do
    if [ "$(getprop sys.boot_completed)" = "1" ]; then
        cap_all $MAXF
        log "boot completed, max ${MAXF} kHz"
        exit 0
    fi
    # re-assert the boot cap in case a core was re-onlined in the meantime
    cap_all $BOOT_MAXF
    i=$((i + 1))
    sleep 1
done

log "boot_completed timeout, keeping ${BOOT_MAXF} kHz"
exit 1
