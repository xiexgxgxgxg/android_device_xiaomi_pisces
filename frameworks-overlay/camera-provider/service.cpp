/*
 * Copyright 2017 The Android Open Source Project
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

#define LOG_TAG "android.hardware.camera.provider@2.4-service"

#include <android/hardware/camera/provider/2.4/ICameraProvider.h>
#include <hidl/LegacySupport.h>

#include <binder/ProcessState.h>
#include <stdlib.h>

using android::hardware::camera::provider::V2_4::ICameraProvider;
using android::hardware::defaultPassthroughServiceImplementation;

int main()
{
    ALOGI("Camera provider Service is starting.");
    // The camera HAL may communicate to other vendor components via
    // /dev/vndbinder
    android::ProcessState::initWithDriver("/dev/vndbinder");

    // pisces: the 2013 Tegra camera HAL can only serve one sensor per process,
    // so the provider is started twice - once per sensor - and the camera
    // service switches between the two providers instead of switching sensors
    // inside one HAL.  The instance name (and the camera id base that keeps the
    // ids unique across the two providers) comes from the environment, set by
    // the init service definition.
    const char *instance = getenv("PISCES_PROVIDER_INSTANCE");
    if (instance == NULL) {
        instance = "legacy/0";
    }
    ALOGI("Camera provider instance: %s", instance);
    return defaultPassthroughServiceImplementation<ICameraProvider>(instance, /*maxThreads*/ 6);
}
