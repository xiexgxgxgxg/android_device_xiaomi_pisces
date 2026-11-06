# Xiaomi Mi 3 TD (pisces) LineageOS 16.0 — 相机收尾交接

> 交接时间：2026-11-07 凌晨
> 设备序列号：095ac70c（adb 需 `adb -s 095ac70c`）
> 当前版本：build50（关机充电修复 + 双 provider 相机）
> 仓库全部已推送，分支 `lineage-16.0`

---

## 一、当前已完成并验证的功能

| 功能 | 状态 |
|---|---|
| 后摄（IMX135 13MP）预览/对焦/拍照/手电筒 | ✅ 完全可用 |
| 前摄（IMX132 2MP）预览/拍照 | ✅ 可用 |
| **后摄↔前摄切换** | ✅ 可用（双 provider 架构） |
| 前摄方向 | ✅ 270°（不再上下颠倒） |
| 耳机（识别 + 路由） | ✅ |
| 振动 | ✅ |
| MTP 双向 | ✅ |
| WiFi 热点 | ✅ |
| 关机充电动画 | 🔄 已接入原厂 `chargeonlymode`，待验证 |

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

## 三、待修复问题：快速连续切换会卡死

**现象**：一切换成功就立刻继续切换，切换次数多了会触发卡住，然后提示"无法连接相机"。

**已定位的原因（需要优化）**：

1. **每次打开相机的耗时较长**（HAL 的 `setupHalInLock` + FM 守卫重试）。
   wrapper 里已有重试逻辑（4×150ms），但仍是秒级。
2. **相机服务的 `connectHelper` 在持有 `mServiceLock` 的情况下等待 HAL 打开**。
   而 App 启动时 `CameraApp.onCreate → CameraManager.getCameraIdList() → ICameraService.addListener`
   需要同一把锁 → App UI 被阻塞 → 全黑屏、没有拍照界面。这个已经从 App 线程栈确认：

   ```
   at android.os.BinderProxy.transact
   at android.hardware.ICameraService$Stub$Proxy.addListener
   at android.hardware.camera2.CameraManager$CameraManagerGlobal.connectCameraServiceLocked
   ```

3. **FM 守卫**：`libnvmm_camera.so 0x380e8` 是一个静态指针守卫，返回 0xd。
   同一 provider 第二次打开时会命中它（`NvFmInitialize: Failed already initialized`），
   需要重试才能成功。这是"第二次切换开始变慢"的直接原因。

**优化方向（按优先级）**：

- A. 缩短 HAL 打开时间：调 wrapper 的重试参数 / 关闭宽限期（当前 150ms）
- B. 减少 FM 守卫触发：让关闭后的异步清理在下次打开前完成
- C. 框架层：让 `CameraService::connectHelper` 不要在持 `mServiceLock` 时等 HAL 打开
  （`frameworks/av/services/camera/libcameraservice/CameraService.cpp`，风险较高，放最后）
- D. 如果"无法连接相机"是 cameraserver 崩溃：注意 provider 意外死亡时，
  `CameraHardwareInterface::getParameters()` 会因 HIDL `DEAD_OBJECT` 触发
  `HidlStatus: Failed HIDL return status not checked` 断言，直接 abort cameraserver。
  需要给该路径加状态检查。

## 四、调试工具（已内置）

| 工具 | 用法 | 说明 |
|---|---|---|
| `NvOsDbg` 日志 | `adb shell logcat -s NvOsDbg:D` | HAL 内部状态机全可见（camshim 拦截 `NvOsDebugPrintf`） |
| `NvRmReg` | `logcat -s NvRmReg:D` | host1x 寄存器读写（mod 11=VI, 12=ISP） |
| `persist.pisces.camswap` | `setprop persist.pisces.camswap 1` | 把相机 0 映射到前摄（单 provider 调试用） |
| 调用栈 | 拦截器对 ViCsi/already/syncpt 消息自动打 `CallStack` | 定位 blob 内部函数 |

## 五、关键文件

| 路径 | 内容 |
|---|---|
| `hardware/nvidia/pisces/camera/CameraWrapper.cpp` | 相机 HAL wrapper（角色、参数缓存、异步 setParameters、fd 回收、open 重试） |
| `hardware/interfaces/camera/provider/2.4/default/service.cpp` | provider 实例名（env `PISCES_PROVIDER_INSTANCE`） |
| `hardware/interfaces/camera/provider/2.4/default/CameraProvider.cpp` | 相机 id 偏移（env `PISCES_CAM_ID_BASE`）、状态表 |
| `hardware/interfaces/camera/device/1.0/default/CameraDevice.cpp` | HIDL id → 模块 id 映射 |
| `device/xiaomi/pisces/ramdisk/init.pisces.rc` | 双 provider service、`on charger`、原厂充电逻辑 |
| `device/xiaomi/pisces/device_pisces.mk` | `chargeonlymode` 等拷贝规则 |

## 六、构建与刷入

```bash
# 构建（整包，约 20-25 分钟）
cd /data/twrp && nohup ./build-lineage.sh > /tmp/buildNN.out 2>&1 &
# 日志：/data/lineage-16.0/build-logs/latest.log（末尾有 BUILD_RC=0 表示成功）
# 产物：/data/lineage-16.0/out/target/product/pisces/lineage_pisces-ota-eng.xie.zip

# 刷入
adb -s 095ac70c push <zip> /sdcard/lineage.zip
adb -s 095ac70c shell 'echo "install /sdcard/lineage.zip" > /cache/recovery/openrecoveryscript'
adb -s 095ac70c reboot recovery

# 只改 wrapper 时可免整包（快很多）：
#   m camera.pisces && adb -s 095ac70c remount && adb -s 095ac70c push out/.../camera.pisces.so /system/lib/hw/
#   然后重启两个 provider：
#   adb shell 'setprop ctl.stop vendor.camera-provider-2-4; setprop ctl.stop vendor.camera-provider-2-4-front'
#   adb shell 'setprop ctl.start vendor.camera-provider-2-4; setprop ctl.start vendor.camera-provider-2-4-front'
```

## 七、已排除的方向（不要重复）

- 卸载 vendor 模块（dlclose 不生效，链接器保留映射）
- 关闭 HAL 泄漏的 fd（`/dev/tegra_sema` **不能关**，那是同步点等待的信号量，关掉会刷 `syncpt semaphore failed 0x30003`）
- 私有 `libnvmm_camera` 副本（加载第二份会因符号互相插入而 SIGSEGV）
- provider 重启方案（能出图，但和框架的客户端重连竞态，会黑屏）
- 直接寄存器编程（VI/ISP 只用 0x5c8 握手，真正配置走命令缓冲区）

## 八、其它两个小问题

**呼吸灯**：LM3533 三色 LED，`echo 255 > /sys/class/leds/red/brightness` 后回读恒为 0。
背光正常说明 I2C 通。内核 `CONFIG_LEDS_TRIGGERS` 关闭（原厂也关）。
另外原厂 `chargeonlymode` 也直接写这三个 LED 节点，如果关机充电验证成功，说明这些节点是可用的，
那问题就在亮度写法/时序。

**关机充电**：已按原厂逻辑接入（`/system/bin/chargeonlymode` + 原厂的 CPU 低功耗集群设置 +
`class_start charger`）。注意原厂用的是 `chargeonlymode` 而不是 `healthd -c`。

## 九、历史背景（简）

- 相机死锁的根因是 camshim 的 `SensorEventQueue` 桩自递归编译成 `b .`（死循环）
- 单开驱动（imx135/ad5823/max77665-flash/tegra_camera）改成了引用计数
- 参数按 sensor 缓存（`/data/misc/pisces_camera/params_<id>.txt`）
- `.text` 是 **Thumb-2**（反汇编要加 `-M force-thumb`）

