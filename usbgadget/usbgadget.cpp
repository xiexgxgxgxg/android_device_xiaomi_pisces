/*
 * android.hardware.usb.gadget@1.0 implementation for pisces.
 *
 * Android 9's UsbDeviceManager talks to IUsbGadget instead of writing
 * sys.usb.config itself, and the AOSP implementation drives a configfs gadget
 * (/config/usb_gadget) that this 3.4 kernel does not have - it only has the
 * legacy android_usb gadget.  Without this service every function switch (MTP,
 * PTP, USB tethering) failed with
 *   "Could not find service hosting interface
 *    android.hardware.usb.gadget@1.0::IUsbGadget/default"
 * and the tethering UI toggled on and straight back off.
 *
 * This maps the HAL's function bits onto the legacy sysfs interface.
 */

#define LOG_TAG "pisces-usb-gadget"

#include <android/hardware/usb/gadget/1.0/IUsbGadget.h>
#include <android/hardware/usb/gadget/1.0/IUsbGadgetCallback.h>
#include <android/hardware/usb/gadget/1.0/types.h>
#include <hidl/LegacySupport.h>

#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include <string>

using android::hardware::Return;
using android::hardware::Void;
using android::hardware::hidl_string;
using android::hardware::usb::gadget::V1_0::GadgetFunction;
using android::hardware::usb::gadget::V1_0::IUsbGadget;
using android::hardware::usb::gadget::V1_0::IUsbGadgetCallback;
using android::hardware::usb::gadget::V1_0::Status;

namespace {

constexpr const char* kGadgetDir = "/sys/class/android_usb/android0/";

bool writeNode(const char* name, const std::string& value) {
    std::string path = std::string(kGadgetDir) + name;
    int fd = open(path.c_str(), O_WRONLY);
    if (fd < 0) {
        return false;
    }
    ssize_t rc = write(fd, value.c_str(), value.size());
    close(fd);
    return rc == (ssize_t)value.size();
}

std::string readNode(const char* name) {
    std::string path = std::string(kGadgetDir) + name;
    char buf[128] = {0};
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        return "";
    }
    ssize_t rc = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (rc <= 0) {
        return "";
    }
    std::string out(buf, rc);
    while (!out.empty() && (out.back() == '\n' || out.back() == '\r')) {
        out.pop_back();
    }
    return out;
}

class UsbGadget : public IUsbGadget {
  public:
    Return<void> setCurrentUsbFunctions(uint64_t functions,
                                        const android::sp<IUsbGadgetCallback>& callback,
                                        uint64_t timeout) override {
        (void)timeout;

        std::string fns;
        // ordering matters for the legacy gadget: adb first, mtp/ptp mutually
        // exclusive with rndis only in the "none" case
        if (functions & GadgetFunction::ADB) {
            fns = "adb";
        }
        if (functions & GadgetFunction::MTP) {
            fns = fns.empty() ? "mtp" : fns + ",mtp";
        } else if (functions & GadgetFunction::PTP) {
            fns = fns.empty() ? "ptp" : fns + ",ptp";
        } else if (functions & GadgetFunction::RNDIS) {
            fns = fns.empty() ? "rndis" : fns + ",rndis";
        }
        if (functions & GadgetFunction::MIDI) {
            fns = fns.empty() ? "midi" : fns + ",midi";
        }
        if (fns.empty()) {
            fns = "none";
        }

        // legacy gadget: disable, reprogram, enable
        writeNode("enable", "0");
        bool ok = writeNode("functions", fns);
        if (ok && fns != "none") {
            const char* product = "4ee7";  // adb
            if (functions & GadgetFunction::MTP) {
                product = (functions & GadgetFunction::ADB) ? "4ee2" : "4ee1";
            } else if (functions & GadgetFunction::PTP) {
                product = (functions & GadgetFunction::ADB) ? "4ee6" : "4ee5";
            } else if (functions & GadgetFunction::RNDIS) {
                product = (functions & GadgetFunction::ADB) ? "4ee4" : "4ee3";
            }
            writeNode("idVendor", "18d1");
            writeNode("idProduct", product);
            writeNode("bDeviceClass", "0");
        }
        if (ok) {
            ok = writeNode("enable", fns == "none" ? "0" : "1");
        }

        if (callback != nullptr) {
            callback->setCurrentUsbFunctionsCb(functions, ok ? Status::FUNCTIONS_APPLIED
                                                            : Status::ERROR);
        }
        mFunctions = functions;
        return Void();
    }

    Return<void> getCurrentUsbFunctions(const android::sp<IUsbGadgetCallback>& callback) override {
        std::string fns = readNode("functions");
        uint64_t bits = 0;
        if (fns.find("adb") != std::string::npos) bits |= GadgetFunction::ADB;
        if (fns.find("mtp") != std::string::npos) bits |= GadgetFunction::MTP;
        if (fns.find("ptp") != std::string::npos) bits |= GadgetFunction::PTP;
        if (fns.find("rndis") != std::string::npos) bits |= GadgetFunction::RNDIS;
        if (fns.find("midi") != std::string::npos) bits |= GadgetFunction::MIDI;

        if (callback != nullptr) {
            callback->getCurrentUsbFunctionsCb(bits, Status::SUCCESS);
        }
        return Void();
    }

  private:
    uint64_t mFunctions = 0;
};

}  // namespace

int main() {
    android::sp<UsbGadget> service = new UsbGadget();
    if (service->registerAsService("default") != android::OK) {
        return 1;
    }
    /* keep the process alive, otherwise the service disappears immediately */
    android::hardware::joinRpcThreadpool();
    return 0;
}
