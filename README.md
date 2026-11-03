# android_device_xiaomi_pisces

Xiaomi Mi 3 TD（codename **pisces**，NVIDIA Tegra 4 / T114）的 LineageOS 设备树。

## 设备信息

| 项目 | 值 |
|---|---|
| SoC | NVIDIA Tegra 4 (T114)，32-bit Cortex-A15 |
| 内核 | 3.4.113（基于小米 3.4.35 合并上游 stable） |
| 分区 | system 4 GiB / cache 384 MiB / userdata ~10 GiB（已重分区） |
| 屏幕 | 1080x1920 |

## 分支

| 分支 | 说明 |
|---|---|
| `lineage-16.0` | **当前开发分支**：LineageOS 16.0（Android 9）移植，来自 CM12 树逐项迁移 |
| `cm-12.1` | 旧基线：CM12.1（Android 5.1），已可用 |
| `cm-12.0` / `cm-11.0` | 更早的 CM 分支，仅存档 |

## 配套仓库（分支均为 `lineage-16.0`）

| 仓库 | 内容 |
|---|---|
| [android_vendor_xiaomi_pisces](https://github.com/xiexgxgxgxg/android_vendor_xiaomi_pisces) | MIUI V9.2.3.0 提取的 186 个 blob（Tegra HAL、NV 图形/OMX、BCM43341 固件） |
| [android_hardware_nvidia](https://github.com/xiexgxgxgxg/android_hardware_nvidia) | lights / vibrator / camera wrapper / libshim / immersion |
| [android_kernel_xiaomi_pisces](https://github.com/xiexgxgxgxg/android_kernel_xiaomi_pisces) | 3.4.113 合并内核（分支 `lineage-16.0-113`） |

## 当前状态

- [x] 内核 3.4.113 源码构建（Lineage kernel.mk 直接编译）
- [x] 内核 backport：`getrandom`、`memfd_create`、seccomp filter（真机验证）
- [x] 4 GiB system 重分区（TWRP 模块已验证双向）
- [x] vendor blobs 提取并入库
- [x] HAL 迁移到 hardware/nvidia
- [ ] LineageOS 16 完整构建通过
- [ ] 首次启动 / 显示 / 触摸
- [ ] WiFi / 蓝牙 / 音频 / 相机 / RIL

## 构建

```bash
repo init -u https://github.com/LineageOS/android.git -b lineage-16.0
repo sync -c -j8

# 设备树 / vendor / hardware / kernel 放到对应路径（或写 local_manifest）
# device/xiaomi/pisces       <- 本仓库 lineage-16.0
# vendor/xiaomi/pisces       <- android_vendor_xiaomi_pisces lineage-16.0
# hardware/nvidia            <- android_hardware_nvidia lineage-16.0
# kernel/xiaomi/pisces       <- android_kernel_xiaomi_pisces lineage-16.0-113

source build/envsetup.sh
lunch lineage_pisces-eng
mka bacon -j16
```

> 现代发行版构建需要兼容处理（python2、prebuilts shebang、libtinfo5、`LC_ALL=C`），
> 本机脚本见 `/data/twrp/build-lineage.sh`，日志写入 `build-logs/latest.log`。

## 已知问题 / 后续

- SELinux：内核 3.4 只认 policy 26，A9 用 policy 30，运行期需要跳过策略加载或 backport
- 图形：Tegra 4 只有 HWC1，需要 HWC2On1Adapter 或 HWC2 实现
- HAL：audio/sensors/gnss/camera 需要 HIDL 包装（Android 9 强制）
- 相机 wrapper 已移出构建，等 camera provider HIDL 方案
