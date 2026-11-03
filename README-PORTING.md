# pisces → LineageOS 16.0 迁移清单

底座：本仓库 `cm-12.1`（Android 5.1）设备树。
目标：LineageOS 16.0（Android 9）+ 3.4.113 内核
（`android_kernel_xiaomi_pisces` 分支 `lineage-16.0-113`）。

## 已完成
- [x] `lineage-16.0` 分支、`lineage_pisces.mk`、`AndroidProducts.mk`
- [x] 内核 3.4.35 + 3.4.113 通用子系统合并，真机 TWRP 验证可启动
- [x] 内核 backport：`getrandom`(384)、`memfd_create`(385)

## 待做（按依赖顺序）

### 1. 构建系统
- [ ] `BoardConfig.mk`：`BOARD_SEPOLICY_DIRS`、`TARGET_USES_MKE2FS`、HWC2 策略
- [ ] `device.mk`：A9 必需 HIDL 服务（light/vibrator/health/power/memtrack…）
- [ ] `fstab.pisces`：A9 格式（vold 管理项）
- [ ] `init.pisces.rc` / `ueventd.pisces.rc`：A9 init 语法适配

### 2. HAL（A9 强制 HIDL）
- [ ] 图形：Tegra 4 仅 HWC1 → HWC2On1Adapter 或重写 HWC2
- [ ] audio / sensors / gnss / camera：legacy HAL + HIDL 包装
- [ ] lights / vibrator / health / power / memtrack
- [ ] keymaster：无 Trusty，走软件实现

### 3. sepolicy
- [ ] 按 A9 policy 重写（5.1 规则不可直接复用）
- [ ] 先 permissive，再逐条收紧

### 4. blobs
- [ ] `extract-files.sh` 从 MIUI 4.4 提取
- [ ] 无 vendor 分区：放到 `/system/vendor`
- [ ] 按 A9 需求补全 `proprietary-files.txt`

### 5. 内核
- [x] getrandom / memfd_create
- [ ] seccomp filter（CONFIG_SECCOMP_FILTER）
- [ ] SELinux policy 30 支持（或维持跳过 policy）
- [ ] mmap_rnd_bits 兼容（已在 TWRP 树验证跳过方案）

## 里程碑
1. `brunch pisces` 出包
2. 进 bootanimation（抓 log）
3. 进桌面
4. 电话/WiFi/蓝牙/相机
