/*
 * pisces_cpu: C replacement for /pisces_cpu.sh.
 *
 * The shell script cannot run under SELinux enforcing because init may not
 * execute /system/bin/sh.  This binary does the same job: take the cpuquiet
 * hotplug over, bring all four A15 cores online, cap them to a safe frequency
 * during boot, then lift the cap once sys.boot_completed=1.
 */
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/system_properties.h>

static void wr(const char *path, const char *val)
{
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd >= 0) {
        ssize_t n = write(fd, val, strlen(val));
        (void)n;
        close(fd);
    }
}

static void cap_all(const char *freq)
{
    char path[160];
    for (int c = 0; c < 4; c++) {
        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_max_freq", c);
        wr(path, freq);
    }
}

static int four_online(void)
{
    char buf[32] = {0};
    int fd = open("/sys/devices/system/cpu/online", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return 0;
    }
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) {
        return 0;
    }
    return strncmp(buf, "0-3", 3) == 0;
}

int main(void)
{
    const char *boot_max = getenv("PISCES_CPU_BOOT_MAXF");
    const char *max = getenv("PISCES_CPU_MAXF");
    char prop[PROP_VALUE_MAX] = {0};
    int ready = 0;

    if (boot_max == NULL) {
        boot_max = "510000";
    }
    if (max == NULL) {
        max = "1428000";
    }

    for (int i = 0; i < 400; i++) {
        wr("/sys/devices/system/cpu/cpuquiet/current_governor", "userspace");
        wr("/sys/devices/system/cpu/cpu1/online", "1");
        wr("/sys/devices/system/cpu/cpu2/online", "1");
        wr("/sys/devices/system/cpu/cpu3/online", "1");
        if (four_online()) {
            cap_all(boot_max);
            printf("pisces_cpu: 4 cores online, boot cap %s (tries=%d)\n", boot_max, i);
            ready = 1;
            break;
        }
        usleep(200000);
    }

    if (!ready) {
        printf("pisces_cpu: giving up on hotplug\n");
        return 1;
    }

    for (int i = 0; i < 300; i++) {
        if (__system_property_get("sys.boot_completed", prop) > 0 && prop[0] == '1') {
            cap_all(max);
            printf("pisces_cpu: boot completed, max %s\n", max);
            return 0;
        }
        cap_all(boot_max);
        sleep(1);
    }

    printf("pisces_cpu: boot_completed timeout, keeping %s\n", boot_max);
    return 1;
}
