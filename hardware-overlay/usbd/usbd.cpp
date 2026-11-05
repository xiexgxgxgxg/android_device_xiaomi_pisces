/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "usbd"

#include <string>

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android/hardware/usb/gadget/1.0/IUsbGadget.h>

#include <hidl/HidlTransportSupport.h>

#define PERSISTENT_USB_CONFIG "persist.sys.usb.config"

using android::base::GetProperty;
using android::base::SetProperty;
using android::hardware::configureRpcThreadpool;
using android::hardware::usb::gadget::V1_0::GadgetFunction;
using android::hardware::usb::gadget::V1_0::IUsbGadget;
using android::hardware::Return;

int main(int /*argc*/, char** /*argv*/) {
    /* pisces: keep this daemon out of the composition business.  It is started
     * from late_start, i.e. after init.pisces.rc has already installed the boot
     * composition (mtp,adb) from this same property.  The stock fallback then
     * replaced it with MTP only (idProduct 4ee1, adbd stopped, ffs unbound),
     * which is why every boot came up without adb and with a gadget the
     * framework had not asked for.  Starting adbd early is still useful because
     * the ffs function needs it before the interface can bind. */
    std::string function = GetProperty(PERSISTENT_USB_CONFIG, "");
    if (function == "adb") {
        LOG(INFO) << "persistent prop is adb";
        SetProperty("ctl.start", "adbd");
    }
    return 0;
}
