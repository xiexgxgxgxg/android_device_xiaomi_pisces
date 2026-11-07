/*
 * Small diagnostic for the pisces LM3533 notification LED.
 *
 *   lighttest hal  <type 0-7> <argb>
 *   lighttest hidl <type 0-7> <argb>
 *   lighttest types
 *
 * "hal" loads lights.pisces.so through hw_get_module() (the legacy HAL) and
 * "hidl" goes through android.hardware.light@2.0, which is what Android 9's
 * LightsService uses.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>

#include <hardware/hardware.h>
#include <hardware/lights.h>

#include <fcntl.h>
#include <sys/ioctl.h>
#include <linux/i2c.h>
#include <linux/i2c-dev.h>

#include <android/hardware/light/2.0/ILight.h>
#include <android/hardware/light/2.0/types.h>

using namespace android::hardware::light::V2_0;

static const char *kNames[] = {
    "backlight", "keyboard", "buttons", "battery",
    "notifications", "attention", "bluetooth", "wifi"
};

static int runHal(int type, int argb)
{
    hw_module_t *module = NULL;
    int ret = hw_get_module(LIGHTS_HARDWARE_MODULE_ID, (const hw_module_t **)&module);
    if (ret != 0 || module == NULL) {
        printf("hw_get_module(lights) failed: %d\n", ret);
        return 1;
    }
    printf("hal module: %s\n", module->name);
    if (type < 0 || type > 7) {
        type = 4;
    }
    light_device_t *dev = NULL;
    ret = module->methods->open(module, kNames[type], (hw_device_t **)&dev);
    if (ret != 0 || dev == NULL) {
        printf("open(%s) failed: %d\n", kNames[type], ret);
        return 1;
    }
    light_state_t state;
    memset(&state, 0, sizeof(state));
    state.color = argb;
    state.flashMode = LIGHT_FLASH_NONE;
    state.brightnessMode = BRIGHTNESS_MODE_USER;
    ret = dev->set_light(dev, &state);
    printf("hal set_light(%s, %08x) -> %d\n", kNames[type], argb, ret);
    return ret == 0 ? 0 : 1;
}

static int runHidl(int type, int argb)
{
    android::sp<ILight> light = ILight::getService();
    if (light == nullptr) {
        printf("ILight::getService() returned null\n");
        return 1;
    }
    android::hardware::hidl_vec<Type> types;
    light->getSupportedTypes([&](android::hardware::hidl_vec<Type> t) { types = t; });
    printf("hidl supported types:");
    for (size_t i = 0; i < types.size(); i++) {
        printf(" %d", (int)types[i]);
    }
    printf("\n");

    if (type < 0 || type > 7) {
        type = 4;
    }
    LightState state = {};
    state.color = argb;
    state.flashMode = Flash::NONE;
    state.flashOnMs = 0;
    state.flashOffMs = 0;
    state.brightnessMode = Brightness::USER;
    auto ret = light->setLight((Type)type, state);
    Status status = ret.isOk() ? (Status)ret : Status::UNKNOWN;
    printf("hidl setLight(%d, %08x) -> %s status=%d\n", type, argb,
            ret.isOk() ? "ok" : "transport-error", (int)status);
    return 0;
}

/* Dump the LM3533 registers that decide whether the LEDs light up. */
static int runRegs(void)
{
    int fd = open("/dev/i2c-0", O_RDWR);
    if (fd < 0) {
        printf("open /dev/i2c-0 failed: %s\n", strerror(errno));
        return 1;
    }
    static const int regs[] = {
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
        0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x21, 0x22, 0x23,
        0x27, 0x28, 0x2c, 0x2d, 0x2e, 0x2f,
        0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38,
        0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
        0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57,
        0x60, 0x61, 0x62, 0x63,
        0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77,
        0xb0, 0xb1, 0xb2
    };
    for (unsigned i = 0; i < sizeof(regs) / sizeof(regs[0]); i++) {
        struct i2c_rdwr_ioctl_data msgset;
        struct i2c_msg msgs[2];
        unsigned char reg = (unsigned char)regs[i];
        unsigned char val = 0xaa;
        msgs[0].addr = 0x38; msgs[0].flags = 0; msgs[0].len = 1; msgs[0].buf = &reg;
        msgs[1].addr = 0x38; msgs[1].flags = I2C_M_RD; msgs[1].len = 1; msgs[1].buf = &val;
        msgset.msgs = msgs; msgset.nmsgs = 2;
        int ret = ioctl(fd, I2C_RDWR, &msgset);
        if (ret < 0) {
            printf("[%02x] <err %d>\n", reg, errno);
        } else {
            printf("[%02x] = 0x%02x (%u)\n", reg, val, val);
        }
    }
    close(fd);
    return 0;
}

int main(int argc, char **argv)
{
    if (argc < 2) {
        printf("usage: %s hal|hidl|types [type] [argb]\n", argv[0]);
        return 2;
    }
    int type = argc > 2 ? atoi(argv[2]) : 4;
    int argb = argc > 3 ? (int)strtoul(argv[3], NULL, 0) : 0xffff0000;

    if (strcmp(argv[1], "hal") == 0) {
        return runHal(type, argb);
    }
    if (strcmp(argv[1], "hidl") == 0) {
        return runHidl(type, argb);
    }
    if (strcmp(argv[1], "regs") == 0) {
        return runRegs();
    }
    if (strcmp(argv[1], "types") == 0) {
        return runHidl(4, 0);
    }
    printf("unknown mode %s\n", argv[1]);
    return 2;
}
