# android_device_xiaomi_pisces

Xiaomi Mi 3 TD（codename **pisces**）的 LineageOS 设备树。

## 硬件

| 项目 | 规格 |
|---|---|
| SoC | NVIDIA Tegra 4 (T114)，4×Cortex-A15 1.8 GHz + 低功耗伴核，32 位 |
| GPU | NVIDIA GeForce ULP |
| 内存 / 存储 | 2 GB RAM / 16 GB eMMC |
| 屏幕 | 5.0 英寸 IPS，1080×1920 |
| 相机 | 后置 Sony IMX135 13 MP，前置 Sony IMX132 2 MP |
| 音频 | Cirrus Logic CS42L73（主 codec）+ ES325 语音处理 |
| 无线 | Broadcom BCM43341（Wi-Fi 2.4/5 GHz、BT 4.0、FM） |
| 传感器 | MPU-6050（加速度/陀螺）、AKM8963（磁力计）、BMP180（气压）、ISL29028（光感/距感） |
| 电源 | MAX77665 PMIC（含闪光灯驱动）、MAX17042 电量计 |
| 振动 | TI DRV2604 |
| 基带 | 展讯（Spreadtrum） |
| 分区 | system 4 GiB（已重分区）/ cache 384 MiB / userdata ~10 GiB |

## 说明

本仓库是设备树本体：BoardConfig、device mk、init、overlay、HAL 配置等。
配套仓库：`android_vendor_xiaomi_pisces`（blobs）、`android_hardware_nvidia`（HAL）、
`android_kernel_xiaomi_pisces`（内核）。当前开发分支 `lineage-16.0`（Android 9）。
