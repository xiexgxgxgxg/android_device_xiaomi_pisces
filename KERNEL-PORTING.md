# pisces 内核移植交接：3.4 之后的路

写于 2026-11-11。当前设备状态、3.4 的上限、以及往 3.10 / 主线走的路线。

---

## 一、当前状态

### 设备与系统

- 机型：小米 3 TD（`pisces`，Tegra 4 / T114，armv7l）
- 系统：LineageOS 16.0（Android 9），`user` 变体
- 内核：**3.4.113-xiecore**（上游 3.4 终点 + 我们的补丁）

### 内核版本的分水岭

```
3.4.35   小米原厂（MiCode/pisces-kk-oss，KitKat 时代）
3.4.113  上游 LTS 终点，2016-10-26 发布后 EOL  ← 我们现在这里
```

`uname -r` 现在输出 `3.4.113-xiecore`，来自 `CONFIG_LOCALVERSION="-xiecore"`。

**重要**：3.4 在 2016 年后没有任何安全更新。所有还在跑 3.4 的设备都在吃老本。

### 已经修好的问题（3.4 上的最终状态）

| 问题 | 根因 | 位置 |
|---|---|---|
| 装不了 APK | `ParcelFileDescriptor.getFile()` 的 `readlink` 失败中止 `allocateBytes` | `StorageManager.java` 容错 |
| 应用数据目录不完整 | `installd` 遇 `EOPNOTSUPP` 中断 `createAppData` | `InstalldNativeService.cpp` |
| `phone` 无限崩溃 | 上一条导致 `HbpcdLookup.db` 打不开 | 同上 |
| RescueParty 恢复出厂循环 | `phone` 反复崩溃触发 | 同上 |
| 未知 class 全部被拒 | 无策略时 `deny_unknown=1` | 内核 `security_get_allow_unknown()` |
| `checkreqprot` 未设置 | `SelinuxInitialize()` 提前返回跳过 | `system/core/init/selinux.cpp` |
| SELinux 元数据缺失 | `PRODUCT_SEPOLICY_SPLIT` 未生效 | 设备树 `selinux/` |
| 屏幕淡入淡出卡死看门狗 | `ColorFade` 的 EGL 切换等 Tegra gralloc 锁 | `DisplayPowerController.java` |
| NFC 崩溃 | Android 9 需 `nfc@1.1` HIDL，设备只有 2013 老式 HAL | 不编译 NFC |

---

## 二、可用的内核来源

### 本地已有的（都是 3.4）

| 位置 | 版本 | 说明 |
|---|---|---|
| `kernel/xiaomi/pisces` | 3.4.113 | 当前构建树 |
| `/data/twrp/kernel/src` | 3.4.113 | 完整 git 副本，2.5 GB |
| `/data/twrp/kernel/stock-src` | 3.4.35 | 原厂源码，680 MB |
| `/data/twrp/kernel/kernel-3.4.35-stock.bin` | 3.4.35 | 原厂 boot 镜像 |
| `/data/twrp/kernel/patches/stable-3.4.35-to-3.4.113.diff` | — | 升级补丁，4.0 MB |
| `/data/twrp/kernel/patches/merged-3.4.113-hybrid.diff` | — | 混合版，3.5 MB |

git 远程只有两个分支，都是 3.4：

```
origin/pisces-kk-oss      MiCode 原厂
xie/lineage-16.0          你的 fork（3.4.113 + 补丁）
```

### 需要联网获取的

#### 1. NVIDIA L4T 3.10 —— 最现实的下一步

```
git://nv-tegra.nvidia.com/linux-3.10
最后 Tegra 4 标签：tegra-l4t-r19.3
```

- Tegra 4 的参考板是 **Dalmore** / **Foster**，不是 pisces
- 带 NVIDIA 的 Android 补丁
- SoC 层（时钟、pinctrl、GPU、显示控制器）基本齐全
- **缺**：pisces 的板级支持

#### 2. 主线 6.x

- `arch/arm/boot/dts/tegra114.dtsi` 存在（Linux 3.13 起）
- 2025 年有人在系统性补完 Tegra 4：
  - `[PATCH v1 0/9] Tegra114: implement EMC support`（2025-02）
  - `[PATCH v1 0/6] ARM: tegra: complete Tegra 4 and Tegra K1 device trees`（2025-02，v4 2025-05，v5 2025-08）
  - `[PATCH v1 3/3] ARM: tegra: Add DFLL clock support on Tegra 4`（2025-08）
- **缺**：pisces 的 DTS、全部 Android HAL 对接

#### 3. AOSP `android-3.10`

通用 Android 3.10 分支，无 Tegra 支持，需与 L4T 合并。

---

## 三、移植路线建议

### 路线 A：3.4 → 3.10（推荐先做）

**目标**：拿到一个比 3.4 新、有官方 Tegra 4 支持、能跑 Android 的内核。

**步骤**：

1. 拉取 L4T 3.10
   ```bash
   git clone git://nv-tegra.nvidia.com/linux-3.10
   cd linux-3.10
   git checkout tegra-l4t-r19.3
   ```

2. 以 Dalmore 为参考，创建 pisces 的板级支持
   - `arch/arm/mach-tegra/board-pisces.c`（或 DTS）
   - 参考 `board-dalmore.c` / `arch/arm/boot/dts/tegra114-dalmore.dts`

3. 对接关键外设（这些是 pisces 独有的）
   | 外设 | 参考 |
   |---|---|
   | 屏幕（JDI/Sharp 1080p） | 原厂 3.4 的 panel 驱动 |
   | 触摸（Atmel mxt） | 原厂 3.4 |
   | PMIC（TI Palmas） | L4T 已有 |
   | 相机（OV13B / S5K3L8） | 原厂 3.4 |
   | 调制解调器（MDM9215） | 原厂 3.4 |
   | 音频（RT5501 等） | 原厂 3.4 |
   | 传感器 | 原厂 3.4 |

4. 用 TWRP 做首次验证（比 Android 简单得多）
   - 只要内核能起、能挂载分区、屏幕能出图、触摸能用，就可以测

5. TWRP 通过后再进 LineageOS 16 逐步修复

**风险**：移植期间设备可能一段时间不可用。建议保留当前 3.4 的 boot.img 备份。

### 路线 B：3.4 → 主线（长期）

**目标**：完全上游化，不再依赖任何厂商代码。

**前提**：等 2025 那批 Tegra 4 补丁进主线。EMC 和 DFLL 是最后两块拼图。

**工作量**：写完整 pisces DTS + 全部驱动适配 + Android HAL 重做。这是月级别的工程。

---

## 四、必须一起带过去的关键修复

这些是 **Android 框架层** 的问题，换内核后依然存在：

### 1. SELinux 元数据安装（设备树）

```makefile
# device_pisces.mk
$(LOCAL_PATH)/selinux/plat_file_contexts:system/etc/selinux/plat_file_contexts
$(LOCAL_PATH)/selinux/plat_seapp_contexts:system/etc/selinux/plat_seapp_contexts
# ……共 10 个文件，见 device/xiaomi/pisces/selinux/
```

**原因**：`PRODUCT_SEPOLICY_SPLIT` 在这个 product 上不生效，元数据会进 ramdisk 而不是 `/system/etc/selinux/`。

### 2. `installd` 的 `EOPNOTSUPP` 容错

```cpp
// frameworks/native/cmds/installd/InstalldNativeService.cpp
if (lgetfilecon(path.c_str(), &before) < 0) {
    if (errno == EOPNOTSUPP) {
        return 0;   // 无策略 → 无标签可打 → 成功
    }
    ...
}
```

**注意**：换到 3.10 后如果 SELinux 策略能加载了，这条容错就不再触发，但保留无害。

### 3. `init` 的 `checkreqprot=0`

```cpp
// system/core/init/selinux.cpp
WriteFile("/sys/fs/selinux/checkreqprot", "0");
```

**注意**：如果 3.10 能正常加载策略，应该**去掉** `SelinuxInitialize()` 的提前返回，让完整的初始化流程跑起来。

### 4. `StorageManager.allocateBytes` 容错

```java
// frameworks/base/core/java/android/os/storage/StorageManager.java
try {
    file = ParcelFileDescriptor.getFile(fd);
} catch (IOException e) {
    Slog.w(TAG, "allocateBytes: cannot resolve fd " + fd + ": " + e);
    return;
}
```

**注意**：3.10 在 `/proc` 权限模型上已经改过，这个 EACCES 大概率不再出现。修好后可以去掉。

### 5. `ColorFade` 禁用

```java
// frameworks/base/services/core/java/android/server/display/DisplayPowerController.java
mColorFadeEnabled = false;
```

**注意**：这是 Tegra gralloc 锁的问题，换内核后**要重新评估**——如果是 gralloc 而非内核的问题，仍然需要。

### 6. NFC 不编译

Android 9 需要 `android.hardware.nfc@1.1`（HIDL），设备只有 2013 年的 legacy HAL。要做的话得写 HIDL 包装层。

---

## 五、已知陷阱

### 1. 内核模块的 vermagic 不匹配

```
/system/lib/modules/gps_drv.ko:
  vermagic: 3.4.35-g779fa93 SMP preempt mod_unload ARMv7 p2v8
```

**这些是原厂预编译模块**，vermagic 停留在 `3.4.35`，而现在内核是 `3.4.113-xiecore`。

**但目前能加载**——说明 vermagic 检查实际没生效。换内核后要重新确认：
- 如果 3.10 严格检查 vermagic，这些 vendor 模块会加载失败
- 需要从源码重编，或者继续让检查失效

模块清单：
```
baseband_usb_chr.ko   gps_drv.ko   mbt8797.ko
```

### 2. SELinux 策略版本

- 3.4 内核的解析器最高支持 **policy version 26**
- Android 9 的策略是 **version 30**
- 所以 3.4 上策略**永远加载不了**，`ss_initialized` 一直是 0

**3.10 也不一定够**——需要确认它的 `POLICYDB_VERSION_MAX`。如果还是 26，问题依旧。

主线 6.x 肯定支持 v30。

### 3. `PRODUCT_SEPOLICY_SPLIT` 不生效

试过 `PRODUCT_SEPOLICY_SPLIT := true` 和 `PRODUCT_SEPOLICY_SPLIT_OVERRIDE := true`，都没让元数据装到 `/system/etc/selinux/`。最后的办法是直接从设备树用 `PRODUCT_COPY_FILES` 拷。

### 4. `PRODUCT_PACKAGES` 改动需要 `installclean`

增量 `m bacon` 不会重新评估 `PRODUCT_PACKAGES`。加/删应用后必须：

```bash
make installclean && m bacon
```

### 5. A/B 分区与 OTA 格式

OTA 是块级镜像（`system.new.dat.br` + `system.transfer.list`），`unzip -l` 看不到单个 APK。要检查文件是否在镜像里，看：

```
out/target/product/pisces/system/app/<App>/<App>.apk
```

### 6. `adb sideload` 偶发中断

47% 左右可能报 `adb: failed to read command: Success`。重试一次即可。

---

## 六、构建与刷机流程

### 构建

```bash
cd /data/lineage-16.0
export LC_ALL=C LANG=C LOCPATH=/data/twrp/locale \
       JAVA_HOME=/data/twrp/toolchain/jdk8 \
       PATH=/data/twrp/toolchain/bin:/data/twrp/toolchain/python2/bin:$JAVA_HOME/bin:$PATH \
       LD_LIBRARY_PATH=/data/twrp/toolchain/lib
source build/envsetup.sh
lunch lineage_pisces-user
mka bacon -j16
```

封装脚本：

```bash
/data/twrp/build-lineage-user.sh    # user 变体
/data/twrp/build-lineage-eng.sh     # eng 变体
```

日志在 `/data/lineage-16.0/build-logs/`，`latest.log` 是软链接。

### 刷机（**不格式化 userdata**）

```bash
adb reboot recovery
# 等 TWRP 起来
adb shell twrp sideload
adb sideload out/target/product/pisces/lineage-16.0-*-UNOFFICIAL-pisces.zip
adb reboot
```

**不要** `twrp wipe data`——会丢 USB 调试授权。

### 判断设备状态

```bash
# recovery（TWRP 起来但系统没起）
getprop ro.twrp.version    # 有值
getprop sys.boot_completed # 空

# 系统
getprop ro.twrp.version    # 空
getprop sys.boot_completed # 1
```

---

## 七、备份清单

| 内容 | 位置 |
|---|---|
| 原厂 boot.img | `/data/twrp/backup-20261110/boot.img` |
| 原厂 recovery.img | `/data/twrp/backup-20261110/recovery.img` |
| 原厂内核源码 | `/data/twrp/kernel/stock-src` |
| 原厂内核镜像 | `/data/twrp/kernel/kernel-3.4.35-stock.bin` |
| 3.4.113 完整树 | `/data/twrp/kernel/src` |
| 设备树仓库 | `/data/twrp/device-repo-real`（GitHub: `xiexgxgxgxg/android_device_xiaomi_pisces`） |
| 刷机工具链 | `/data/twrp/toolchain`、`/data/twrp/source-9.0` |

---

## 八、下一步建议

1. **先做 3.10**：L4T 有官方 Tegra 4 支持，是最短路径
2. **用 TWRP 验证**：比 Android 简单，只要能起、能显示、能触摸就够了
3. **主线放后面**：等 2025 那批补丁进主线（EMC + DFLL 完成后）
4. **保留 3.4 备份**：移植期间随时可以回退

---

## 附：本次修复的提交

```
58db371  pisces: fix package installation, phone crashes and the soft reboot loop
```

内核侧补丁在 `patches/KERNEL-apply-pisces-fixes.sh`，包含：
1. `kernel/ptrace.c` — `same_thread_group` 放行（主线语义）
2. `security/selinux/ss/services.c` — 无策略时 `allow_unknown=1`
3. defconfig — `CONFIG_LOCALVERSION="-xiecore"`
