# 小米 3 TD（pisces）3.10 内核移植 —— 注意事项与交接

生成时间：2026-11-13
适用对象：接手把当前 3.4.113 移植推进到 3.10 内核的人
当前可用基线：`/data/lineage-16.0`（LineageOS 16.0 / Android 9）+ 3.4.113-xiecore，
基带、相机、音频、蓝牙、WiFi、手电筒、Magisk 31.1 均已在 enforcing 下实机验证。

---

## 一、先看结论：哪些东西可以原样复用

3.10 移植**不要**从零开始，下面这些已经是设备树/用户态的工作，和内核版本基本无关：

| 资产 | 位置 | 说明 |
|---|---|---|
| 设备树 | `android_device_xiaomi_pisces`（本地 `/data/twrp/device-repo-real`） | BoardConfig、init rc、sepolicy、overlay、HAL 配置 |
| 内核仓库 | `android_kernel_xiaomi_pisces`（本地 `/data/twrp/kernel/src`，构建用 `kernel/xiaomi/pisces` 副本） | 3.4 分支；3.10 建议新开 `lineage-16.0-310` |
| vendor blobs | `android_vendor_xiaomi_pisces` | 相机/音频/GPU/传感器固件 |
| Nvidia HAL | `android_hardware_nvidia` | HWC、gralloc、OMX、相机 wrapper、audio HAL |
| 源码补丁 | 设备树 `patches/2026-11-12/`、`patches/2026-11-13/` | AOSP 侧的改动，repo sync 后按 README 重新打 |

四个仓库都已推到 `github.com/xiexgxgxgxg/`，分支 `lineage-16.0`。
**3.10 只应改内核仓库 + 必要的设备树 DTS/defconfig**，不要把 AOSP 源码改动再复制一份。

---

## 二、构建与环境

```bash
cd /data/lineage-16.0
export LC_ALL=C LANG=C LOCPATH=/data/twrp/locale \
  JAVA_HOME=/data/twrp/toolchain/jdk8 \
  PATH=/data/twrp/toolchain/bin:/data/twrp/toolchain/python2/bin:$JAVA_HOME/bin:$PATH \
  LD_LIBRARY_PATH=/data/twrp/toolchain/lib
source build/envsetup.sh && lunch lineage_pisces-user
mka bootimage -j16      # ~1.5 分钟
mka bacon -j16          # 全量 OTA，冷构建 20~40 分钟
```

- 内核源码路径由 `device/xiaomi/pisces/BoardConfig.mk` 的
  `TARGET_KERNEL_SOURCE := kernel/xiaomi/pisces` 决定；3.10 换源码时改这里，
  并同步 `TARGET_KERNEL_CONFIG`。
- 3.10 需要新的 DTB/DTB 打包方式。3.4 是 `board-pisces.c` 板级文件 + `tegra11_pisces_user_defconfig`；
  3.10 Tegra 已经设备树化，**GPIO/regulator/pinmux/相机/音频/面板都要迁到 DTS**，
  不要试图把 `board-pisces.c` 整体搬过去。
- 分区（不要搞错）：`LNX=p19 boot`、`SOS=p21 recovery`、`APP=p24 system`、
  `CAC=p26 cache`、`UDA=p27 data`、`MSC=p7`。boot 分区 16 MiB。

刷写（TWRP 内，TWRP 的 adb 是 root）：
```bash
adb reboot recovery
adb push out/target/product/pisces/boot.img /tmp/b.img
adb shell 'dd if=/tmp/b.img of=/dev/block/mmcblk0p19 bs=1048576; sync; reboot'
```

---

## 三、内核配置（3.10 必须确认的项）

3.4 的 defconfig 是 `arch/arm/configs/tegra11_pisces_user_defconfig`。3.10 新建 defconfig 时逐项对照：

- `CONFIG_NAMESPACES=y`（以及 `UTS_NS/PID_NS/NET_NS`）：Magisk 的 su 需要 mount namespace。
  3.4 构建出来的 `.config` 里是 `y`，但 defconfig 文本里是 `# CONFIG_NAMESPACES is not set`，
  说明曾手工改过；3.10 一定写进 defconfig，避免“Magisk 显示未安装/无法 su”。
- `CONFIG_SECURITY_SELINUX=y`、`CONFIG_SECURITY_SELINUX_DEVELOP=y`（调试期）、
  `CONFIG_EXT4_FS_SECURITY=y`：没有 ext4 security xattr，TWRP 里 `chcon` 会报
  `Operation not supported on transport endpoint`。
- `CONFIG_TEGRA_CAMERA`/VI/CSI、`CONFIG_SND_SOC_TEGRA*`、`CONFIG_SND_SOC_CS42L73`、
  `CONFIG_SND_SOC_ES325`、`CONFIG_BCMDHD`/`CONFIG_BT_BCM`、`CONFIG_USB_F_*`、
  `CONFIG_TEGRA_MODEM`/`CONFIG_SC8800G_MODEM`（3.10 驱动名可能变）。
- 3.10 的 `wake_lock` 已改成 wakeup source，音频/显示里的 `wake_lock` 调用要改成
  `pm_wakeup_event()` / `device_init_wakeup()`；用户态 `/sys/power/wake_lock` 节点
  如果保留，SELinux 的 `sysfs_wake_lock` 规则可继续用。
- `CONFIG_ANDROID_BINDER_IPC`、`CONFIG_ANDROID_BINDER_DEVICES`、`CONFIG_SYNC`、
  `CONFIG_SW_SYNC`：3.10 的 binder/sync 与 3.4 不同，HWC/gralloc 依赖它们。

---

## 四、Modem / 基带（最容易被 3.10 弄坏的部分）

3.4 上最终跑通的链路（细节见设备树 `patches/2026-11-13/README.md`）：

1. `init.modem_sprd.rc` 把 `pwr_onoff/state/usb_switch` chown 成 `root:radio`，
   `reset` 给 `system:radio`；**不要再让 init 直接 write reset**（AOSP neverallow）。
2. `fild` 以 `user radio` 运行，需要：
   - `sh` 执行权限（它用 `system("echo 1 > .../pwr_onoff")`）；
   - `fild_data_file:fifo_file` 读写（`/data/fild/fild_fifo`）；
   - `netlink_kobject_uevent_socket { create bind read write setopt }`；
   - `ctl.start/ctl.stop` 属性。
3. `bin/fild` 被打了 2 字节补丁：`mknod` 的 mode 从 `0300` 改成 `0600`
   （文件偏移 `0x1a18`，`f4 4f 86 51` → `f4 4f 8c 51`）。厂商脚本里的
   `#hmod 600` 是拼写错误且被注释掉，不改的话 radio 读不了 fifo，
   fild 永远打印 "Modem power is off"。
4. 内核 `drivers/modem/serial_sc8800g.c` 的 `sc8800g_mdm_active()`：
   热重启后 `BP_PWRON` 仍为高，原代码直接 early-return，跳过
   `sc8800g_gpio_init()` 和复位释放，modem 永远不进 PWRING/ACTIVE。
   现已改成“已上电则先断电 500 ms 再走完整上电流程”。

3.10 移植要点：
- 3.10 的 `drivers/modem`/`drivers/misc/ts27010mux` 与 3.4 不同，先确认
  Spreadtrum 驱动是否还在原路径；不在就整份搬（SPI + tty + 状态机 + sysfs 属性）。
- `gpio_request_one`、`gpio_export`、`tegra_gpio_enable` 在 3.10 有变化；
  `gpio_set_value` 对未 request 的 GPIO 行为也可能不同，`BP_PWRON`/`MDM_EXTRSTN`
  必须显式 request。
- `/dev/ts0710mux*`、`/dev/sysmatdrv` 的 ueventd 权限和 SELinux 标签要照抄。
- 基带字符串最终来自 modem 自身的 `DM_BASE_...`，只有 fild 成功把 fdl1/modem 镜像
  通过 `/system/bin/fdl` 写进去、并且 `mux0` 上 AT 有响应，rild 才不会崩在
  `at_channel_writer_destroy`。
- 验证：
  ```bash
  adb shell getprop gsm.version.baseband   # DM_BASE_13A_8803G_W13.44 | 20140120092332
  adb shell getprop init.svc.ril-daemon    # running
  adb logcat -b all -d | grep -c "F DEBUG.*rild"   # 0
  ```

---

## 五、SELinux（3.10 上反而更简单，但别丢规则）

3.4 的坑：
- `POLICYVERS := 26`（3.4 内核支持的最高策略版本）。
- per-node `genfscon` 在 3.4 上经常不生效，`/sys` 下大量节点落到泛型 `sysfs`，
  所以 `init`/`lmkd`/`fild`/`pisces_cpu` 等都给了泛型 `sysfs:file` 权限。
- `neverallow init sysfs:file { open read write }` 是硬限制：所有 sysfs 写必须
  放到独立域（`fild`、`pisces_cpu`、`vpcmd`）或内核里做。
- `restorecon` 在这个 port 上不可靠，`/data` 下新建目录的标签不会自动生效；
  需要预创建目录或在策略里按 `system_data_file` 放行。

3.10 上：
- 策略版本可以提高，`genfscon` 正常；可以逐步把 3.4 的泛型放行收窄成按类型放行。
- 但**不要**把 3.4 的 `pisces.te` 整份删掉重写：里面每个 `allow` 都对应一个实机
  验证过的坑（相机、ES325、rfkill、fild、modem、vpcmd、media_omx、storaged、
  netd qtaguid 等）。
- 设备树 `selinux/plat_file_contexts`、`plat_seapp_contexts` 是 `PRODUCT_COPY_FILES`
  直接进 system 的，改完要刷 system（只刷 boot 不生效）。
- 新增服务/二进制时：`init` 不能 exec 普通 `system_file`，必须给
  `type xxx_exec, exec_type, file_type;` + `init_daemon_domain(xxx)`，
  并在 `plat_file_contexts` 里加路径标签（参考 `pisces_cpu`、`fild`、`vpcmd`）。

---

## 六、Magisk 31.1（enforcing 下必须做的两处）

TWRP 刷 Magisk zip 只完成 ramdisk patch；首次开机 app 会要求“修复运行环境”，
需要在 app 内做一次“直接安装”。如果 enforcing 下 app 直接显示“未安装”，
根因是 **MLS**，不是 daemon/su：

- Magisk app targetSdk≥28 → `seapp_contexts` 给它 `levelFrom=all`，
  进程级别是 `s0:c84,c256,...`；
- magiskd 的 socket 是 `u:object_r:magisk_file:s0`，写操作受
  `mlsconstrain sock_file { write } (l1 eq l2 ...)` 约束，分类不相等 → 拒绝
  `{ write }`，app 查不到 daemon。

设备树里已经修好（`patches/2026-11-13/README.md` 有完整说明）：
1. `system/sepolicy/private/mac_permissions.xml` 给 Magisk APK 的签名证书
   （SHA-256 `B4CB83...3EE6`）分配专属 `seinfo=magisk`；
2. `device/xiaomi/pisces/selinux/plat_seapp_contexts` 在通用 app 规则前加：
   ```
   user=_app seinfo=magisk name=com.topjohnwu.magisk domain=untrusted_app type=app_data_file levelFrom=none
   ```
这样只有 Magisk manager 以 `s0` 运行，其余 app 仍带分类，系统保持 enforcing。
验证：app 首页显示 `Magisk 31.1 (31100)`，`ps -Z` 里能看到
`u:r:magisk:s0 root ... com.topjohnwu.magisk:root:0`。

3.10 移植时这两条要一起带过去；如果 3.10 上 Magisk 仍显示未安装，
先看 `dmesg | grep avc` 里有没有 `magisk_file:sock_file` 的 `write` 拒绝，
再检查 `seinfo` 是否真的分配到了（`ps -Z` 的 app 进程级别应为 `s0`，不带分类）。

---

## 七、相机 / 音频 / 显示 / 无线（3.10 的对应关系）

- **相机**：3.4 用 `tegra_camera` V4L2 + 自写 camera wrapper（`hardware/nvidia/pisces/camera`）。
  3.10 的 Tegra 相机栈变化很大（VI/CSI、`tegra_camera_platform`、media controller）。
  尽量保留 wrapper 的“双 provider、参数缓存、torch 走 LED”逻辑，只换底层 V4L2 节点/ioctl。
  IMX135 + IMX132 + AD5823 + MAX77665 flash 的 I2C/regulator 要在 DTS 里配对。
- **音频**：CS42L73（主 codec）+ ES325（语音 DSP）。3.4 上删掉了
  `tegra_cs42l73.c` 里“codec 当 ASP 主机”的实验代码；ES325 固件由 `vpcmd`
  在独立 `es325_fw` 域直接跑（不要用 init exec sh）。3.10 的 ASoC 是
  DPCM/component 化，`tegra30-i2s` 的 DAI link 要重写；`wake_lock` 改 wakeup source。
- **显示**：HWC 2.1 + gralloc；3.4 上有 `ComposerHandleCache` 双重释放修复、
  present watchdog 调到 1 s。3.10 的 HWC 接口可能不兼容，优先保证
  “客户端合成（client composition）”能起来，再逐步开硬件合成。
- **WiFi/BT**：BCM43341（SDIO WiFi + UART BT）。3.4 上 BT HAL 以 root 跑以写
  rfkill；`persist.pisces.bt.skip_fw=0` 强制加载 `bcm43341.hcd`（ROM 固件不支持
  BLE 扫描过滤命令）。3.10 的 `bluetooth`/`rfkill` 子系统基本兼容，但
  `CONFIG_BT_BCM`/`CONFIG_BCMDHD` 的 firmware path 要改到 `/vendor/firmware`。
- **电源/充电**：2013 电池带不动默认开机电流，PMIC（palmas）会在 3.4 s 左右
  触发 VSYS_MON POR。现有 workaround 是开机 4 核 510 MHz + 背光 20，
  `sys.boot_completed=1` 后再放开。3.10 的 cpufreq/cpuquiet 不同，
  这个限制要在 DTS/thermal 或 init 里重新实现，**不要直接删**，否则开机会重启。
- **传感器/NFC/GPS**：沿用 vendor HAL；GPS 目前 init 直接 exec 被 SELinux 拒绝，
  3.10 上要么给 `gpsd` 独立域，要么按现有 `glgps` 方式处理。

---

## 八、调试手段（这套设备上验证过可用）

```bash
adb shell su -c 'dmesg | grep avc'          # Magisk 可用后，看被限流的 avc
adb reboot recovery
adb shell 'cat /proc/last_kmsg | tail -200' # 上一次 Android 启动的内核日志（TWRP 里）
adb logcat -b all -d | grep -E 'avc|F DEBUG|Magisk|FILD'
adb shell lshal | grep -i radio
adb shell getprop | grep -E 'init.svc|gsm.version'
```

- `/proc/last_kmsg` 是排查“热重启后 modem 没起来 / 驱动 early-return”的关键。
- Magisk 可用后，`su -c` 能直接看 dmesg，不必只依赖 logcat 的 audit 限流。
- TWRP 里 `simg2img`、`magiskboot`、`e2fsdroid`、`chcon` 都在；但 TWRP 内核
  可能没有 ext4 security xattr，`chcon` 会 ENOTSUP，标签要在镜像构建时写入。

---

## 九、已知坑（按踩坑概率排序）

1. **别删 `/data/adb` 再在 TWRP 刷 Magisk**。Magisk 安装脚本的
   `mkdir /data/adb/magisk` 没有 `-p`，父目录不存在会回退到 `/data/magisk`，
   结果 magiskd 报 `/data/adb is not present, abort`，app 显示未安装。
   正常设备 `/data/adb` 由 init 创建；手工清理后先 `mkdir -p /data/adb`。
2. **Magisk 的 “未安装” 先看 `ps -Z` 的 app 级别**。带分类就是 MLS 问题，
   按第六节修；不带分类再查 socket/daemon。
3. **只刷 boot 不会更新 system 里的 file_contexts/seapp_contexts/mac_permissions**。
   这些改了必须重编 system 或手动 push 后重启。
4. **签名**：APK 用 `signapk` 时不要带 `-w`（`-w` 是给 OTA zip 的）；重签后
   `cmd package compile -m speed -a` 恢复流畅。system 和 vendor 的
   `BUILD_FINGERPRINT` 必须一致，否则弹“联系厂商”。
5. **`restorecon` 不可用**：`/data` 下新建文件/目录的标签不会自动修正，
   策略要按实际落点放行，或由 init 预创建。
6. **boot 分区只有 16 MiB**：3.10 内核 + ramdisk 容易超，必要时裁 ramdisk
   （去掉不用的 HAL/字体/主题），或确认 `BOARD_BOOTIMAGE_PARTITION_SIZE`。
7. **不要 `fastboot flash boot`**：这台机器只能 `fastboot boot recovery.img`，
   或者 TWRP 里 dd 到 p19。

---

## 十、交接检查清单

- [ ] 3.10 内核能起来，串口/最后日志无早期 panic
- [ ] `getenforce` = Enforcing，`dmesg | grep avc` 没有新增关键拒绝
- [ ] `gsm.version.baseband` 有值，`ril-daemon` 长期 running，无 rild tombstone
- [ ] 相机 2 个 device、前后切换、闪光灯/手电筒
- [ ] 铃声/音乐/外放/耳机，ES325 固件版本可读
- [ ] WiFi 扫描/连接、BT 搜索/被搜索
- [ ] 热重启一次，基带仍正常（验证 `BP_PWRON` 处理）
- [ ] 刷 Magisk 31.1，app 显示已安装、能授予 su、app 内“直接安装”可完成
- [ ] 四个仓库的改动已提交并推送；源码改动都在 `patches/` 里
