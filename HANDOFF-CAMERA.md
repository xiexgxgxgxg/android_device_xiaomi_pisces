# Xiaomi Mi 3 TD (pisces) LineageOS 16.0 — 相机 / 呼吸灯收尾交接

> 交接时间：2026-11-07 凌晨（第二次收尾）
> 设备序列号：095ac70c（adb 需 `adb -s 095ac70c`）
> 当前版本：build52（相机快速切换修复 + 呼吸灯修复）
> 仓库全部已推送，分支 `lineage-16.0`

---

## 一、当前已完成并验证的功能

| 功能 | 状态 |
|---|---|
| 后摄（IMX135 13MP）预览/对焦/拍照/手电筒 | ✅ 完全可用 |
| 前摄（IMX132 2MP）预览/拍照 | ✅ 可用 |
| 后摄↔前摄切换 | ✅ 可用（双 provider 架构） |
| **快速反复切换（30 次以上）** | ✅ 不再出现"无法连接相机" |
| **呼吸灯（充电 / 通知）** | ✅ 已点亮（LM3533，见第八节） |
| 前摄方向 | ✅ 270°（不再上下颠倒） |
| 耳机（识别 + 路由） | ✅ |
| 振动 | ✅ |
| MTP 双向 | ✅ |
| WiFi 热点 | ✅ |
| **关机充电动画** | ✅ 原厂 `chargeonlymode`（/sbin）正常出动画 |

## 二、相机架构（关键理解，务必先读）

**核心事实**：这颗 2013 年的闭源 HAL（`libnvmm_camera.so`）**每个进程只能服务一个传感器**。
第二个传感器会打开成功、`imx132_set_mode` 和 `stream on` 都正常，但 VI/CSI 永远不推进同步点 15
（`Camera Timeout: Sync Point ID: 15`），HAL 用 `NvCameraViCsiFixFence` 手工伪造一帧，
于是黑屏→花屏→闪退。这个结论已经用大量实验确认（详见第三节）。

**所以现在的方案是双 provider**：让相机服务同时看到两个 HIDL provider 实例，
每个实例只服务一个传感器，切换相机 = 切换 provider，HAL 永远处于"第一个会话"状态。

```
legacy/0  PISCES_CAM_ROLE=rear   PISCES_CAM_ID_BASE=0  →  相机 id 0（后摄）
legacy/1  PISCES_CAM_ROLE=front  PISCES_CAM_ID_BASE=1  →  相机 id 1（前摄）
```

init 里对应两个 service（`vendor.camera-provider-2-4` 和 `vendor.camera-provider-2-4-front`），
环境变量在 `device/xiaomi/pisces/ramdisk/init.pisces.rc`。

## 三、已解决：快速连续切换会卡死（"无法连接相机"）

### 根因（本次定位）

1. **厂商 HAL 的 close 路径本身是坏的**：`camera_device_close()` 里调用
   `vendor->common.close()` 后，blob 的工作线程会在几百毫秒后解引用已释放的函数指针，
   provider 进程必定 SIGSEGV（`Fault addr 0x6e8c`，回溯落在 `libnvmm_camera.so+0x12b35`）。
   同时这条 close 路径会阻塞数秒（blob 等在自已的 teardown 超时上）。
2. **provider 进程一死，相机就从框架里消失**：`CameraProviderManager::removeProvider()`
   对死掉的 provider 的所有设备发 `NOT_PRESENT`，CameraService 直接 `removeStates()`，
   App 的 `CameraManager` 收到 `onStatusChanged(NOT_PRESENT)` 后把该 id 从缓存里删掉。
   于是"切换 → 相机消失 → 立刻再切"就会抛
   `IllegalArgumentException: Unknown camera ID` / `CameraAccessException`，
   即用户看到的"无法连接相机"。
3. 因为 close 慢，Camera1 API 的 `Camera.release()` 是同步的（Snap 用的是 Camera1），
   连续切换时主线程被卡住超过 5 s，触发 **"相机没有响应" ANR**。

### 修复（三处，互相配合）

1. `hardware/nvidia/pisces/camera/CameraWrapper.cpp`
   - **默认（`persist.pisces.camkill=1`）在 close 的最前面 fork 一个子进程
     `kill -9 $PPID` 杀掉 provider，并立刻返回**：app 的 release/close 变成毫秒级，
     不再 ANR；不做损坏的 teardown，也就不再有 SIGSEGV（验证时 30 次切换 0 次崩溃）。
     内核在进程退出时释放 sensor / VI / ISP 的 fd，init 立刻重启 provider。
   - `camera_release()` 在这种模式下直接返回（vendor 的 release 也会阻塞）。
   - close 里的 `unloadVendorModule()` / `closeLeakedVendorFds()` 保留给
     `camkill=0` 的旧路径（属性开关，方便 A/B）。
2. `frameworks/av/services/camera/libcameraservice/common/CameraProviderManager.cpp`
   - `removeProvider()` **不再把死亡 provider 的设备标记成 NOT_PRESENT**：
     pisces 只有两颗内置摄像头、id 固定，设备保持"在场"，重启后的 provider 会重新
     announce 一次（"same status" 的告警无害）。App 的相机列表因此不再抖动。
3. `frameworks/av/services/camera/libcameraservice/CameraService.cpp`
   - `connectHelper()` 在设备暂时不在任何 provider 里时**等 provider 重启**
     （最多 30×100 ms，等待期间 `mServiceLock` 是放开的，addListener 不会跟着卡死）。
   - `getDeviceVersion()` 同样带等待：App 的 `CameraManager.openCamera()` 会同步调用
     `supportsCameraApi()`，provider 重启窗口里原先直接报 "Unknown camera ID"。

4. `hardware/nvidia/pisces/shims/camshim.cpp`
   - 每个会话一份私有 `libnvmm_camera.so` 副本（改 SONAME、`__android_dlopen_ext`
     和普通 `dlopen` 都重定向）：让**同一个 provider 进程内**第二次开相机也能成功
     （否则撞 FM 守卫 `NvFmInitialize: Failed already initialized` → 0xd）。
   - 现在默认走"杀进程"路径，进程本来就是新的，副本默认关闭
     （`persist.pisces.campriv` 可强制打开；camkill=0 时默认打开），
     不再每次开关相机往 /data 写 1 MB。

### 验证

- `PiscesCamTest`（内置测试 app，见第四节）连续 30 次前后切换（每次间隔 0.1 s），
  每次开相机 + 建会话 + 抓一帧 JPEG：**0 失败，前后摄都能出图**。
- 真机 Snap：连点切换键 50 次以上（间隔 1.2 s），
  `dumpsys media.camera` 里 CONNECT/DISCONNECT 交替出现、**没有 REMOVE/ADD**，
  无 ANR（`/data/anr` 没有新文件），之后拍照正常出图（`/sdcard/DCIM/Camera`）。
- 代价：每次切换要重启 provider + 重新初始化 HAL，打开耗时约 0.6–3 s（旧实现更慢，
  而且会卡死）。

## 四、调试工具（已内置）

| 工具 | 用法 | 说明 |
|---|---|---|
| `NvOsDbg` 日志 | `adb shell logcat -s NvOsDbg:D` | HAL 内部状态机全可见（camshim 拦截 `NvOsDebugPrintf`） |
| `NvRmReg` | `logcat -s NvRmReg:D` | host1x 寄存器读写（mod 11=VI, 12=ISP） |
| `persist.pisces.camswap` | `setprop persist.pisces.camswap 1` | 把相机 0 映射到前摄（单 provider 调试用） |
| `persist.pisces.camkill` | 默认 1 | 1=close 时直接杀掉 provider（默认，快）；0=旧的进程内关闭路径 |
| `persist.pisces.campriv` | 空=自动 | 1=强制每会话私有 NvMM 核心副本；0=用共享的 libnvmm_camera.so |
| `PiscesCamTest` APK | `adb shell am start -n org.pisces.camtest/.MainActivity --es mode cam --ei count 30 --ei gap 100 --ez frames true` | 相机切换压测（逐次打 ITER/CAMTEST 日志）；`--es mode notify` 发带灯的通知 |
| `lighttest` | `lighttest hal\|hidl\|regs` | 直接驱动 lights HAL / 读 LM3533 寄存器 |
| 调用栈 | 拦截器对 ViCsi/already/syncpt 消息自动打 `CallStack` | 定位 blob 内部函数 |

## 五、关键文件

| 路径 | 内容 |
|---|---|
| `hardware/nvidia/pisces/camera/CameraWrapper.cpp` | 相机 HAL wrapper（角色、参数缓存、异步 setParameters、fd 回收、open 重试、close 快速重启） |
| `hardware/nvidia/pisces/shims/camshim.cpp` | ABI shim + 每会话私有 NvMM 核心 + NvOs 日志 |
| `hardware/interfaces/camera/provider/2.4/default/service.cpp` | provider 实例名（env `PISCES_PROVIDER_INSTANCE`） |
| `hardware/interfaces/camera/provider/2.4/default/CameraProvider.cpp` | 相机 id 偏移（env `PISCES_CAM_ID_BASE`）、状态表 |
| `hardware/interfaces/camera/device/1.0/default/CameraDevice.cpp` | HIDL id → 模块 id 映射 |
| `frameworks/av/services/camera/libcameraservice/CameraService.cpp` | connect/getDeviceVersion 等 provider 重启 |
| `frameworks/av/services/camera/libcameraservice/common/CameraProviderManager.cpp` | provider 死亡不摘除设备 |
| `device/xiaomi/pisces/overlay/lineage-sdk/lineage/res/res/values/config.xml` | 呼吸灯能力位（75） |
| `device/xiaomi/pisces/overlay/frameworks/base/core/res/res/values/config.xml` | 充电灯颜色（红/红/绿） |
| `kernel/xiaomi/pisces/drivers/leds/leds-lm3533.c` | LM3533 亮度写入修复 |
| `device/xiaomi/pisces/ramdisk/init.pisces.rc` | 双 provider service、`on charger`、原厂充电逻辑 |
| `device/xiaomi/pisces/device_pisces.mk` | `chargeonlymode`（root/sbin + system/bin）等拷贝规则 |
| `patches/0028-frameworks-av-camera-provider-restart.patch` | camera service：provider 重启不摘设备 + 等 provider |
| `patches/0029-system-core-init-charger-mode.patch` | init：`androidboot.forcecharger=1` 调试开关 |
| `kernel-patches/files/leds-lm3533.c` | 内核 LM3533 亮度写入修复（内核不是 git 工程） |
| `packages/apps/PiscesCamTest/` | 压测 APK + `lighttest`（本机工具，随系统编译） |

## 六、构建与刷入

```bash
# 构建（整包，冷 20-25 分钟，增量 5-25 分钟）
cd /data/twrp && nohup ./build-lineage.sh > /tmp/buildNN.out 2>&1 &
# 日志：/data/lineage-16.0/build-logs/latest.log（末尾 BUILD_RC=0 表示成功）
# 产物：/data/lineage-16.0/out/target/product/pisces/lineage_pisces-ota-eng.xie.zip

# 刷入
adb -s 095ac70c push <zip> /sdcard/lineage.zip
adb -s 095ac70c shell 'echo "install /sdcard/lineage.zip" > /cache/recovery/openrecoveryscript'
adb -s 095ac70c reboot recovery

# 只改 HAL / 框架库时可免整包（快很多）：
#   m camera.pisces   && adb remount && adb push out/.../camera.pisces.so /system/lib/hw/
#   m libcameraservice && adb remount && adb push out/.../libcameraservice.so /system/lib/ && setprop ctl.restart cameraserver
#   改内核：mka bootimage，push boot.img 后
#     adb shell 'dd if=/sdcard/boot.img of=/dev/block/platform/sdhci-tegra.3/by-name/LNX bs=4096'
#   然后重启
# 注意：/data 分区不支持 security xattr（内核没开 CONFIG_EXT4_FS_SECURITY），
#       adb install 会失败；测试 APK 要 push 到 /system/app/<name>/ 再重启。
```

## 七、已排除的方向（不要重复）

- 卸载 vendor 模块（dlclose 不生效，链接器保留映射）
- 关闭 HAL 泄漏的 fd（`/dev/tegra_sema` **不能关**，那是同步点等待的信号量，关掉会刷 `syncpt semaphore failed 0x30003`）
- 私有 `libnvmm_camera` 副本（加载第二份会因符号互相插入而 SIGSEGV）——现在只在
  进程内重开（camkill=0）时使用，且每会话一份、改 SONAME、不卸载
- provider 重启方案（能出图，但和框架的客户端重连竞态，会黑屏）——本次用
  "框架不摘设备 + connect 等 provider"补上了这个竞态
- 直接寄存器编程（VI/ISP 只用 0x5c8 握手，真正配置走命令缓冲区）

## 八、呼吸灯（LM3533 三色 LED）

### 之前为什么"不亮"

三层原因，缺一层都不亮：

1. **内核驱动吞掉了亮度写入**：`drivers/leds/leds-lm3533.c` 的 `lm3533_led_set()`
   在 `patten_enabled`（板级数据给红/绿配了 650/200 ms 硬件闪烁）为真时**不调度
   work**，于是 `echo 255 > /sys/class/leds/red/brightness` 回读恒为 0，
   只有再写 `/sys/class/leds/red/update` 才会真正下发。原厂 `chargeonlymode`
   在关机充电时就是直接写 brightness 节点 → 关机充电时灯永远不亮。
2. **框架根本没把"有电池灯"告诉 Lineage**：`config_deviceLightCapabilities`
   默认是 8（只有 pulsating），没有 `LIGHTS_BATTERY_LED`，
   `LineageBatteryLights.isSupported()` 返回 false，BatteryService 直接不驱动充电灯；
   通知灯也没有 `LIGHTS_RGB_NOTIFICATION_LED`，自定义颜色会被忽略。
3. 充电灯颜色资源里 full 被设成 0（全灭）。

### 修复

1. `drivers/leds/leds-lm3533.c`：`lm3533_led_set()` 一律 `schedule_work(&led->work)`。
   现在 `echo 255 > brightness` 立即生效（回读 255），`update` 节点仍然兼容。
2. 新增 overlay `device/xiaomi/pisces/overlay/lineage-sdk/lineage/res/res/values/config.xml`：
   `config_deviceLightCapabilities = 75`
   （RGB 通知灯 1 + RGB 电池灯 2 + pulsating 8 + 电池灯 64）。
   **注意 overlay 目录必须镜像模块的资源目录**（`lineage-sdk/lineage/res/res`），
   写成包名 `lineageos.platform/` 不会被 soong 采纳。
3. `overlay/frameworks/base/core/res/res/values/config.xml`：充电中红色、
   充满绿色（原厂 MIUI 行为）。

### 验证（寄存器级）

充电中（`/sys/class/power_supply/max170xx_battery/status = Charging`）：

```
/sys/class/leds/red/brightness = 255        # 由 BatteryService 自己写，不是手动
LM3533[0x27] = 0x3f   # 5 个 control bank 全开
LM3533[0x28] = 0x01   # pattern 0（红灯）使能 → 650/200 ms 呼吸
LM3533[0x42] = 0xff   # 红灯亮度
```

`lighttest hal 4 0xffff0000` / `lighttest hidl 4 0xffff0000` 也都能把红点亮到 255。

通知灯（LOCK SCREEN 后发带灯通知，用 `PiscesCamTest` 的 notify 模式，颜色依次
红/绿/蓝/白）：

```
red=255 green=0    blue=0     → 红
red=0   green=255  blue=0     → 绿
red=0   green=0    blue=255   → 蓝
red=255 green=255  blue=255   → 白
```

`dumpsys notification` 里也能看到 `Lights List` 有该通知。
**注意**：AOSP 的 `NotificationRecord.calculateImportance()` 会把 targetSdk<26 且
"不响不震"（`isNoisy=false`）的通知压到 `IMPORTANCE_LOW`，低于点灯阈值
（`IMPORTANCE_DEFAULT`），所以测试通知必须带 `setVibrate()`/`setSound()`
（或者 app 自己建 importance >= DEFAULT 的 channel）才会点灯。高优先级/带响铃的
真实通知不受影响。

## 九、关机充电（已修好）

### 现象

关机 + 插线，屏幕不出小米原厂的充电动画（之前被认为"关机充电不能用"）。

### 根因

对照原厂 MIUI 9 的 ramdisk（`pisces_images_V9.2.3.0.KXCCNEK` 里的 `boot.img`）：

```
# stock init.pisces.rc
service charger /sbin/chargeonlymode
    class charger
```

原厂的充电 UI 二进制放在**ramdisk 的 `/sbin`**，而且**没有 `critical`**。
移植时放到了 `/system/bin/chargeonlymode` 并加了 `critical`：

- charger 模式里 init 只跑 `on charger`，`/system` 要靠那行 `mount` 挂上；
  `/sbin` 在 ramdisk 里，永远可用，原厂就是靠这一点。
- `critical` 服务的语义是"退出就重启，反复退出就重启设备"，
  充电 UI 一但退出就会把手机重启进系统，看起来就是"关机充电没用"。

### 修复

- `ramdisk/init.pisces.rc`：`service charger /sbin/chargeonlymode`，
  去掉 `critical`，`on charger` 保持原厂那套（CPU 低耗集群、关触摸、mount /system、
  `class_start charger`），并往 `/dev/kmsg` 打两行标记方便排查。
- `device_pisces.mk`：`chargeonlymode` 同时拷到 `root/sbin/` 和 `system/bin/`。
- `patches/0029-system-core-init-charger-mode.patch`：init 支持
  `androidboot.forcecharger=1`（boot 命令行加这个参数即可进充电模式），
  方便以后不拔插线也能验证充电 UI。

### 验证

- `init` 里加 `forcecharger` 开关后，重启直接进充电模式：原厂电池充电动画出现。
- 真机（用户确认）：关机后拔插数据线，动画正常触发；
  动画上的电量/配色正常（`chargeonlymode` 读 `max170xx_battery/capacity`，
  三个 LED 节点现在也能真正写进去，见第八节）。

### 注意

- 进充电模式后 **没有 adb**（USB 只充电），排查只能靠 `/dev/kmsg` 标记、
  或者把 `forcecharger` 的 boot.img 换回普通版本。
- `chargeonlymode` 在充电模式里会把 `input1` 关掉、CPU 限制在低耗集群，
  和原厂一致。

## 十、已知遗留（下次再做）

- **参数缓存文件名冲突**：`CameraWrapper.cpp` 里 `cacheCameraParameters()` /
  `camera_get_parameters()` 用 `CAMERA_ID(device)` 拼 `/data/misc/pisces_camera/params_%d.txt`，
  双 provider 模式下两个实例的模块内 id 都是 0，后摄/前摄会互相覆盖同一个文件
  （现在两个文件都是 2536 字节，就是前摄的参数）。只有厂商 HAL 返回空参数串时才会
  用到这个缓存，所以目前没触发，但应该改成按角色分文件（`params_rear.txt` /
  `params_front.txt`）。
- 每次切换重启 provider 的代价仍在（0.6–3 s），要再快只能把整个 HAL 会话搬到子进程里代理。
- 通知灯和充电灯共用同一颗 RGB LED：通知熄灭后会把三个通道写 0，
  充电灯要等下一次电池状态变化才会重新点亮（Lineage 通用行为）。
- 测试期间见过一次 system_server 的 watchdog ANR dump
  （`/data/anr/anr_2026-11-06-09-01-57-098`，所有线程都已恢复，无阻塞线程），
  之后反复切换/点灯都没有复现。

## 十一、仓库 / 补丁怎么落

- **设备树**：`github.com/xiexgxgxgxg/android_device_xiaomi_pisces`（分支 `lineage-16.0`，已推送）
- **相机 HAL（wrapper + camshim）**：`github.com/xiexgxgxgxg/android_hardware_nvidia`（分支 `lineage-16.0`，已推送）
- **frameworks/av（相机框架）已 fork 到我们自己的账号**：
  `github.com/xiexgxgxgxg/android_frameworks_av`，分支 **`lineage-16.0-pisces`**
  （基于 lineage-16.0 + 相机 provider 重启容忍 + torch/参数缓存；README 里写明了这是
  为小米 3 TD `/mi3td` 相机问题改的）。用法（`.repo/local_manifests/pisces.xml`）：

  ```xml
  <manifest>
    <remove-project name="LineageOS/android_frameworks_av" />
    <project path="frameworks/av" name="xiexgxgxgxg/android_frameworks_av"
             revision="lineage-16.0-pisces" remote="github" />
  </manifest>
  ```

  `patches/0028` 是同一份改动的补丁，两种方式二选一即可。
- **其余 LineageOS 上游工程**（system/core、hardware/interfaces、frameworks/base、
  packages/apps/Settings、external/*、hardware/broadcom/*、hardware/ril、system/vold）没有推送权限，
  改动全部以补丁形式放在本仓 `patches/`，repo sync 之后跑 `patches/apply-pisces-patches.sh` 复原：

  | 补丁 | 内容 |
  |---|---|
  | 0028 | frameworks/av：相机 provider 重启不摘设备 + connect 等 provider；shim 参数缓存；torch 走 sysfs |
  | 0029 | system/core：init 的 `androidboot.forcecharger=1` 调试开关 |
  | 0030-0039 | 之前会话遗留在工作区、没进补丁的改动：双 provider 相机 HIDL、MTP/热点/USB、蓝牙/WiFi、vold、rild |

  每个补丁都拿 `git show HEAD:<file>` 的干净副本试过一遍再和构建树对比，保证能干净应用。
- **内核**（`kernel/xiaomi/pisces`）不是 git 工程：改过的文件整份放在 `kernel-patches/files/`
  （`leds-lm3533.c`、`board-pisces.c`、`tegra11_pisces_user_defconfig`）。
  内核另有独立仓库 `github.com/xiexgxgxgxg/android_kernel_xiaomi_pisces`（分支 `lineage-16.0-113`，
  `/data/twrp/kernel/src`），官方源码在 `/data/twrp/kernel/stock-src`（分支 `pisces-kk-oss`）。
- **源码备份**：`/data/twrp/pisces-src-backup/`（镜像 `~/lineage-dev/pisces-src-backup-20261107/`）
  同时存了"我们改过的那 50 个文件的官方版本"和"我们的版本"，附 `scripts/restore-official.sh`、
  `restore-modified.sh`、`verify.sh`。这套源码以后要给别的机器用时，先跑 `restore-official.sh`
  回到 LineageOS 官方源码，再按 `patches/` 里对新机器的部分重新打补丁即可。

## 十二、历史背景（简）

- 相机死锁的根因是 camshim 的 `SensorEventQueue` 桩自递归编译成 `b .`（死循环）
- 单开驱动（imx135/ad5823/max77665-flash/tegra_camera）改成了引用计数
- 参数按 sensor 缓存（`/data/misc/pisces_camera/params_<id>.txt`）
- `.text` 是 **Thumb-2**（反汇编要加 `-M force-thumb`）

## 十三、图形栈：composer 崩溃导致的"软重启"(2026-11-07)

现象：user 构建刷入后，开机向导阶段偶发"软重启"（不是内核重启，是 zygote 重启），
多次发⽣后系统卡死（`gralloc: GetBufferLock timed out`、watchdog dump、`dumpsys` 卡住）。

根因（dropbox 里两份一模一样的 tombstone）：

```
pid 345/2669  android.hardware.graphics.composer@2.1-service
signal 11 (SIGSEGV), fault addr 0xb6c30010
    #00 NvGrUnregisterBuffer+26                     (gralloc.tegra.so)
    #01 Gralloc0HalImpl<MapperHal>::freeBuffer
    #02 MapperImpl<IMapper,MapperHal>::freeBuffer
    #03 BsMapper::freeBuffer
    #04 ComposerHandleCache::~ComposerHandleCache
    #05 ComposerDisplayResource::removeLayer
    #06 ComposerClientImpl::destroyLayer
```

链路：HWC 销毁带 buffer 的图层 → 对导入的句柄调 `freeBuffer` → `NvGrUnregisterBuffer`
崩 → composer 死 → SurfaceFlinger 收不到 HWC 而 `SIGABRT` → `surfaceflinger.rc` 里
`onrestart restart zygote` → **zygote 重启 = 看到的"软重启"**。

反汇编说明：`NvGrUnregisterBuffer+26` 读 `handle+0x80`（`NvGrRegisterBuffer` 里
`mmap` 出来的 fence 页指针）再解引用；页已经 munmap 过（重复反注册）或者这个副本
从未在本进程注册过，就会踩到失效地址：

```
2440: ldr r3, [handle, #0x80]
2446: ldr r0, [r3, #0x10]      <- SIGSEGV
```

Android 9 把 composer 拆成独立进程，句柄必须跨进程复制，2013 年的 nvgralloc 句柄
（43 个私有 int）里含进程内指针，复制后这些指针在 composer 进程里无效。

**修复（patch 0040）**：`Gralloc0Hal.h` 里维护一份"本进程已注册句柄"集合：
- `importBuffer`：clone + `registerBuffer`（注册不能省，否则合成拿不到 buffer，会黑屏进不去向导），登记进集合；
- `freeBuffer`：只对集合里的句柄 `unregisterBuffer`，重复 free 直接跳过并打
  `pisces: duplicate freeBuffer(...)`，不再踩 NVIDIA 的失效指针。

**验证方法**：刷入后在 `logcat` 里看
`pisces: duplicate freeBuffer` / `pisces: import` / `pisces: free` 计数；
没有 `composer@2.1-service ... Fatal signal 11`、`zygote` 的 `ELAPSED` 一直等于开机时长，
就说明不再软重启。

**教训**：不要试图省掉 `registerBuffer`（试过，直接黑屏进不去开机向导）；
问题只在"反注册"这一步。

## 十四、frameworks/av 改动已并入设备树（av-overlay）

相机框架改动不再依赖 fork/补丁，而是放在
`device/xiaomi/pisces/av-overlay/`（6 个文件，保持相对路径）：

```
services/camera/libcameraservice/CameraService.cpp
services/camera/libcameraservice/common/CameraProviderManager.cpp
services/camera/libcameraservice/CameraFlashlight.cpp
services/camera/libcameraservice/api1/CameraClient.cpp
media/libaudioprocessing/AudioResampler.cpp
soundtrigger/SoundTrigger.cpp
```

`BoardConfig.mk` 末尾在 configure 阶段调用 `av-overlay/apply.sh $(TOPDIR)`，
把内容不同的文件覆盖进 `frameworks/av`（幂等，`cmp` 相同就跳过），并用 `$(warning ...)`
在构建日志里打印更新了哪些文件。验证方式：把 `frameworks/av` 切回上游 commit 后直接
`mka bacon`，日志里会出现 `pisces: av-overlay: updated frameworks/av/...`，且编译通过。

`patches/0028` 与 fork（`android_frameworks_av` 分支 `lineage-16.0-pisces`）保留作等价备份；
`apply-pisces-patches.sh` 遇到已应用的 0028 会自动跳过。
