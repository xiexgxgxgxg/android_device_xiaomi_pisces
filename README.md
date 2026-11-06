# android_device_xiaomi_pisces

Device tree for the Xiaomi Mi 3 TD (codename **pisces**), LineageOS 16.0.

## Hardware

| Item | Specification |
|---|---|
| SoC | NVIDIA Tegra 4 (T114), 4x Cortex-A15 @ 1.8 GHz + companion core, 32-bit |
| GPU | NVIDIA GeForce ULP |
| RAM / storage | 2 GB / 16 GB eMMC |
| Display | 5.0" IPS, 1080x1920 |
| Cameras | rear Sony IMX135 13 MP, front Sony IMX132 2 MP |
| Audio | Cirrus Logic CS42L73 (main codec) + ES325 voice processor |
| Wireless | Broadcom BCM43341 (Wi-Fi 2.4/5 GHz, BT 4.0, FM) |
| Sensors | MPU-6050 (accel/gyro), AKM8963 (magnetometer), BMP180 (pressure), ISL29028 (light/proximity) |
| Power | MAX77665 PMIC (with flash driver), MAX17042 fuel gauge |
| Vibration | TI DRV2604 |
| Modem | Spreadtrum |
| Partitions | system 4 GiB (repartitioned), cache 384 MiB, userdata ~10 GiB |

## Notes

This repository is the device tree itself: BoardConfig, device makefiles, init,
overlays and HAL configuration.
Companion repositories: `android_vendor_xiaomi_pisces` (blobs),
`android_hardware_nvidia` (HALs), `android_kernel_xiaomi_pisces` (kernel).
Active development branch: `lineage-16.0` (Android 9).
