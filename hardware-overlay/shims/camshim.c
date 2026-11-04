/*
 * ABI shim for the 2013 tegra camera HAL (camera.tegra.so / camera.vendor.tegra.so).
 *
 * The blob was linked against the Android 4.x SensorManager, which lived in
 * libgui as a Singleton.  In Android 9 SensorManager moved to libsensor, is no
 * longer a Singleton and its createEventQueue() gained arguments, so the
 * loader fails with:
 *
 *   cannot locate symbol "_ZN7android9SingletonINS_13SensorManagerEE5sLockE"
 *   referenced by "/vendor/lib/hw/camera.vendor.tegra.so"
 *
 * These stubs satisfy the loader.  The camera only uses the sensor event queue
 * to tag the capture orientation, so handing it an empty queue is fine; the
 * real SensorManager (libsensor) keeps working for everything else.
 *
 * The symbols are written with their mangled names on purpose: this file must
 * be pure C so nothing re-mangles them, and it is dlopen()ed with RTLD_GLOBAL
 * by camera.pisces before the vendor module is loaded.
 */

#include <stdint.h>

/* The blobs (libFaceProc.so in particular) still carry text relocations, which
 * bionic refuses to load for a process targeting API 23 or newer.  Dropping
 * the process target SDK to 22 (the last level that accepted them) is the
 * documented workaround for pre-M vendor libraries. */
extern void android_set_application_target_sdk_version(uint32_t target);

__attribute__((constructor))
static void pisces_camshim_init(void) {
    android_set_application_target_sdk_version(22);
}

/* android::Singleton<android::SensorManager>::sLock / sInstance.
 * The blob's inlined Singleton<T>::getInstance() uses sLock as an
 * android::Mutex; 256 zeroed bytes is a valid (unlocked) bionic mutex. */
__attribute__((aligned(16), visibility("default")))
char _ZN7android9SingletonINS_13SensorManagerEE5sLockE[256];

__attribute__((aligned(16), visibility("default")))
char _ZN7android9SingletonINS_13SensorManagerEE9sInstanceE[8];

/* android::SensorManager::SensorManager() */
void _ZN7android13SensorManagerC1Ev(void *self) { (void)self; }
void _ZN7android13SensorManagerC2Ev(void *self) { (void)self; }

/* const android::Sensor *SensorManager::getDefaultSensor(int) const */
void *_ZN7android13SensorManager16getDefaultSensorEi(void *self, int type) {
    (void)self;
    (void)type;
    return 0;
}

/* android::sp<android::SensorEventQueue> SensorManager::createEventQueue()
 * returns a struct: r0 = this, r1 = hidden return slot. */
void *_ZN7android13SensorManager16createEventQueueEv(void *self, void *ret) {
    (void)self;
    *(void **)ret = 0;
    return ret;
}

/* android::SensorEventQueue::* */
int _ZN7android16SensorEventQueue4readEP12ASensorEventj(void *self,
                                                        void *events,
                                                        unsigned int count) {
    (void)self;
    (void)events;
    (void)count;
    return -1;
}

int _ZNK7android16SensorEventQueue5getFdEv(void *self) {
    (void)self;
    return -1;
}

int _ZNK7android16SensorEventQueue12enableSensorEPKNS_6SensorE(void *self,
                                                              void *sensor) {
    (void)self;
    (void)sensor;
    return -1;
}

int _ZNK7android16SensorEventQueue13disableSensorEPKNS_6SensorE(void *self,
                                                               void *sensor) {
    (void)self;
    (void)sensor;
    return -1;
}

int _ZNK7android16SensorEventQueue12setEventRateEPKNS_6SensorEx(void *self,
                                                               void *sensor,
                                                               long long rate) {
    (void)self;
    (void)sensor;
    (void)rate;
    return -1;
}
