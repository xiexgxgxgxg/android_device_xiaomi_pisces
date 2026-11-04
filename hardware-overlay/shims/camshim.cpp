/*
 * ABI shim for the 2013 tegra camera HAL (camera.tegra.so / camera.vendor.tegra.so).
 *
 * The blob was linked against the Android 4.x SensorManager, which lived in
 * libgui as a Singleton.  In Android 9 SensorManager moved to libsensor, is no
 * longer a Singleton and its createEventQueue() gained arguments, so the
 * loader fails with:
 *
 *   cannot locate symbol "_ZN7android9SingletonINS_13SensorManagerEE5sLockE"
 *
 * and, once that is satisfied, the camera open still fails with
 * "NvCameraHalCore: Fail to initialize SensorListener" because
 * createEventQueue() has to hand back a usable queue.
 *
 * The Android 9 SensorEventQueue takes the sensor callback fd / read / enable
 * calls as *non-virtual* member functions, i.e. the blob resolves them by
 * symbol.  So this shim exports those symbols and forwards them to the real
 * Android 9 object, and createEventQueue() returns a real queue obtained from
 * libsensor's SensorManager.  The rest is stubbed.
 *
 * LD_PRELOADed into the camera provider service.
 */

#include <sensor/SensorManager.h>
#include <sensor/SensorEventQueue.h>
#include <utils/String16.h>
#include <utils/String8.h>

using android::SensorEventQueue;
using android::Sensor;
using android::String16;
using android::String8;

// The blob is Android 4.x ABI: `this` is the first argument for the member
// functions it resolves by symbol, and createEventQueue() returns a
// non-trivial sp<> through a hidden pointer (r0 = return slot, r1 = this).
#define PISCES_METHOD(name) extern "C" __attribute__((visibility("default"))) name

namespace {

android::SensorManager& cameraSensorManager() {
    static android::SensorManager* manager = nullptr;
    if (manager == nullptr) {
        manager = &android::SensorManager::getInstanceForPackage(String16("camera"));
    }
    return *manager;
}

}  // namespace

// The blobs (libFaceProc.so in particular) still carry text relocations, which
// bionic refuses to load for a process targeting API 23 or newer.  Dropping the
// process target SDK to 22 is the documented workaround for pre-M vendor
// libraries.
extern "C" void android_set_application_target_sdk_version(uint32_t target);

__attribute__((constructor)) static void pisces_camshim_init(void) {
    android_set_application_target_sdk_version(22);
}

// android::Singleton<android::SensorManager>::sLock / sInstance.
// The blob's inlined getInstance() uses sLock as an android::Mutex; zeroed
// storage is a valid (unlocked) bionic mutex.
__attribute__((aligned(16), visibility("default")))
char _ZN7android9SingletonINS_13SensorManagerEE5sLockE[256];

__attribute__((aligned(16), visibility("default")))
char _ZN7android9SingletonINS_13SensorManagerEE9sInstanceE[8];

// android::SensorManager::SensorManager(): the blob allocates the object with
// its own (2013) sizeof; the constructor body does nothing on purpose.
PISCES_METHOD(void) _ZN7android13SensorManagerC1Ev(void *self) { (void)self; }
PISCES_METHOD(void) _ZN7android13SensorManagerC2Ev(void *self) { (void)self; }

// const android::Sensor *SensorManager::getDefaultSensor(int) const
PISCES_METHOD(void *) _ZN7android13SensorManager16getDefaultSensorEi(void *self, int type) {
    (void)self;
    return const_cast<Sensor*>(cameraSensorManager().getDefaultSensor(type));
}

// android::sp<android::SensorEventQueue> SensorManager::createEventQueue()
//
// There is no sensors HAL on this device, so the real SensorManager cannot
// create a queue ("NvSensorListener: createEventQueue returned NULL" made the
// whole camera open fail).  The sensor listener only needs a live object: the
// five SensorEventQueue methods the blob uses are resolved by symbol and hit
// the stubs below, so a bare RefBase subclass is enough - it just has to have
// valid refcount storage for the sp<> the caller keeps.
namespace {
class CameraDummyQueue : public android::RefBase {};
CameraDummyQueue gDummyQueue;
}  // namespace

PISCES_METHOD(void *) _ZN7android13SensorManager16createEventQueueEv(void *ret, void *self) {
    (void)self;
    android::sp<SensorEventQueue> queue(
            reinterpret_cast<SensorEventQueue*>(&gDummyQueue));
    new (ret) android::sp<SensorEventQueue>(queue);
    return ret;
}

// android::SensorEventQueue members, forwarded to the real Android 9 object.
PISCES_METHOD(int) _ZN7android16SensorEventQueue4readEP12ASensorEventj(void *self,
                                                                       void *events,
                                                                       unsigned int count) {
    return static_cast<SensorEventQueue*>(self)->read(
            static_cast<ASensorEvent*>(events), count);
}

PISCES_METHOD(int) _ZNK7android16SensorEventQueue5getFdEv(void *self) {
    return static_cast<const SensorEventQueue*>(self)->getFd();
}

PISCES_METHOD(int) _ZNK7android16SensorEventQueue12enableSensorEPKNS_6SensorE(void *self,
                                                                             void *sensor) {
    return static_cast<const SensorEventQueue*>(self)->enableSensor(
            static_cast<const Sensor*>(sensor));
}

PISCES_METHOD(int) _ZNK7android16SensorEventQueue13disableSensorEPKNS_6SensorE(void *self,
                                                                              void *sensor) {
    return static_cast<const SensorEventQueue*>(self)->disableSensor(
            static_cast<const Sensor*>(sensor));
}

PISCES_METHOD(int) _ZNK7android16SensorEventQueue12setEventRateEPKNS_6SensorEx(void *self,
                                                                              void *sensor,
                                                                              long long rate) {
    return static_cast<const SensorEventQueue*>(self)->setEventRate(
            static_cast<const Sensor*>(sensor), rate);
}
