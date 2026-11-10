/*
 * pisces: a minimal framebuffer_device_t built directly on /dev/graphics/fb0.
 *
 * The vendor gralloc's framebuffer device is incompatible with Android 9's
 * HWC2OnFbAdapter (it returns a structure whose layout/garbage pointers crash the
 * adapter).  Building our own keeps the fb composition path usable, which avoids
 * the vendor hwcomposer/gralloc entirely and is what makes the display stable.
 */

#define LOG_TAG "pisces-fb"

#include <android/log.h>
#include <cutils/properties.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fb.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <hardware/fb.h>
#include <hardware/gralloc.h>
#include <hardware/hardware.h>

#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct PiscesFb {
    framebuffer_device_t device;
    int fd;
    uint8_t* memory;
    size_t size;
    fb_var_screeninfo var;
    fb_fix_screeninfo fix;
    int current;
    gralloc_module_t const* gralloc;
};

PiscesFb* toFb(framebuffer_device_t* device) {
    return reinterpret_cast<PiscesFb*>(device);
}

int fbClose(hw_device_t* device) {
    if (!device) {
        return -EINVAL;
    }
    PiscesFb* fb = toFb(reinterpret_cast<framebuffer_device_t*>(device));
    if (fb->memory != MAP_FAILED && fb->memory != nullptr) {
        munmap(fb->memory, fb->size);
    }
    if (fb->fd >= 0) {
        close(fb->fd);
    }
    free(fb);
    return 0;
}

int fbPost(framebuffer_device_t* device, buffer_handle_t handle) {
    if (!device || !handle) {
        return -EINVAL;
    }
    PiscesFb* fb = toFb(device);
    if (!fb->gralloc) {
        return -EINVAL;
    }
    void* vaddr = nullptr;
    int err = fb->gralloc->lock(fb->gralloc, handle, GRALLOC_USAGE_SW_READ_OFTEN,
                                0, 0, device->width, device->height, &vaddr);
    if (err != 0 || vaddr == nullptr) {
        ALOGE("cannot lock the client target: %d", err);
        return -EINVAL;
    }

    const uint8_t* src = static_cast<const uint8_t*>(vaddr);
    const size_t lineBytes = static_cast<size_t>(device->stride) * 4;
    uint8_t* dst = fb->memory + static_cast<size_t>(fb->current) * fb->fix.line_length
            * device->height;

    const int height = static_cast<int>(device->height);
    if (fb->var.bits_per_pixel == 32) {
        for (int y = 0; y < height; y++) {
            memcpy(dst + static_cast<size_t>(y) * fb->fix.line_length,
                   src + static_cast<size_t>(y) * lineBytes, lineBytes);
        }
    } else {
        ALOGE("unsupported framebuffer depth %d", fb->var.bits_per_pixel);
        fb->gralloc->unlock(fb->gralloc, handle);
        return -EINVAL;
    }
    fb->gralloc->unlock(fb->gralloc, handle);

    if (fb->var.yres_virtual >= static_cast<uint32_t>(fb->var.yres) * 2u) {
        fb->current ^= 1;
        fb->var.yoffset = fb->current * fb->var.yres;
        if (ioctl(fb->fd, FBIOPAN_DISPLAY, &fb->var) != 0) {
            ALOGE("FBIOPAN_DISPLAY failed: %s", strerror(errno));
        }
    }
    return 0;
}

int fbSetSwapInterval(framebuffer_device_t* device, int interval) {
    (void)device;
    (void)interval;
    return 0;
}

int fbEnableScreenUpdate(framebuffer_device_t* device, int enable) {
    (void)device;
    (void)enable;
    return 0;
}

void fbDump(framebuffer_device_t* device, char* buffer, int length) {
    PiscesFb* fb = toFb(device);
    snprintf(buffer, length, "pisces fb %dx%d bpp=%d stride=%d\n",
            fb->var.xres, fb->var.yres, fb->var.bits_per_pixel, fb->fix.line_length);
}

}  // namespace

extern "C" framebuffer_device_t* piscesOpenFbDevice() {
    PiscesFb* fb = static_cast<PiscesFb*>(calloc(1, sizeof(PiscesFb)));
    if (!fb) {
        return nullptr;
    }
    fb->fd = open("/dev/graphics/fb0", O_RDWR);
    if (fb->fd < 0) {
        ALOGE("cannot open /dev/graphics/fb0: %s", strerror(errno));
        free(fb);
        return nullptr;
    }
    if (ioctl(fb->fd, FBIOGET_VSCREENINFO, &fb->var) != 0 ||
            ioctl(fb->fd, FBIOGET_FSCREENINFO, &fb->fix) != 0) {
        ALOGE("cannot query the framebuffer: %s", strerror(errno));
        close(fb->fd);
        free(fb);
        return nullptr;
    }
    // pisces: ask the display controller for a 720p framebuffer.  The panel stays
    // at its native 1080p mode and the DC scales, which cuts both the GPU
    // composition and the framebuffer copy of the software composed path.
    {
        char small[PROPERTY_VALUE_MAX] = {0};
        property_get("persist.pisces.fb_720p", small, "1");
        if (small[0] == '1' && fb->var.xres > 720) {
            fb_var_screeninfo var = fb->var;
            var.xres = 720;
            var.yres = 1280;
            var.xres_virtual = 720;
            var.yres_virtual = 1280 * 2;
            if (ioctl(fb->fd, FBIOPUT_VSCREENINFO, &var) == 0 &&
                    ioctl(fb->fd, FBIOGET_VSCREENINFO, &fb->var) == 0 &&
                    ioctl(fb->fd, FBIOGET_FSCREENINFO, &fb->fix) == 0) {
                ALOGI("720p framebuffer accepted");
            } else {
                ALOGE("720p framebuffer rejected, staying at %dx%d", fb->var.xres,
                        fb->var.yres);
                ioctl(fb->fd, FBIOGET_VSCREENINFO, &fb->var);
                ioctl(fb->fd, FBIOGET_FSCREENINFO, &fb->fix);
            }
        }
    }
    fb->size = static_cast<size_t>(fb->fix.line_length) * fb->var.yres_virtual;
    fb->memory = static_cast<uint8_t*>(mmap(nullptr, fb->size, PROT_READ | PROT_WRITE,
            MAP_SHARED, fb->fd, 0));
    if (fb->memory == MAP_FAILED) {
        ALOGE("cannot map the framebuffer: %s", strerror(errno));
        close(fb->fd);
        free(fb);
        return nullptr;
    }
    hw_module_t const* module = nullptr;
    if (hw_get_module(GRALLOC_HARDWARE_MODULE_ID, &module) == 0) {
        fb->gralloc = reinterpret_cast<gralloc_module_t const*>(module);
    }

    framebuffer_device_t* device = &fb->device;
    device->common.tag = HARDWARE_DEVICE_TAG;
    device->common.version = 0;
    device->common.module = nullptr;
    device->common.close = fbClose;
    // The fields of framebuffer_device_t are const; the object itself is not, so
    // writing them through const_cast is legal.
    const_cast<uint32_t&>(device->flags) = 0;
    const_cast<uint32_t&>(device->width) = fb->var.xres;
    const_cast<uint32_t&>(device->height) = fb->var.yres;
    const_cast<int&>(device->stride) = fb->fix.line_length / 4;
    const_cast<int&>(device->format) = HAL_PIXEL_FORMAT_RGBA_8888;
    const_cast<int&>(device->numFramebuffers) = 2;
    const_cast<int&>(device->minSwapInterval) = 0;
    const_cast<int&>(device->maxSwapInterval) = 0;
    const_cast<float&>(device->xdpi) = 400.0f;
    const_cast<float&>(device->ydpi) = 400.0f;
    const_cast<float&>(device->fps) = 60.0f;
    device->post = fbPost;
    device->setSwapInterval = fbSetSwapInterval;
    device->setUpdateRect = nullptr;
    device->enableScreen = fbEnableScreenUpdate;
    device->dump = fbDump;

    ALOGI("framebuffer ready: %dx%d bpp=%d stride=%d virtual=%dx%d", fb->var.xres,
            fb->var.yres, fb->var.bits_per_pixel, fb->fix.line_length,
            fb->var.xres_virtual, fb->var.yres_virtual);
    return device;
}
