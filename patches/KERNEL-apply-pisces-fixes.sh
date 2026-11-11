#!/bin/bash
# Apply the kernel-side pisces fixes to kernel/xiaomi/pisces.
# Run from the kernel tree root:  bash KERNEL-apply-pisces-fixes.sh
set -e
[ -f kernel/ptrace.c ] || { echo "run from the kernel tree root"; exit 1; }

python3 - <<'PYEOF'
import glob
import io

# 1. kernel/ptrace.c -- allow same-thread-group introspection.
p = 'kernel/ptrace.c'
s = io.open(p, encoding='utf-8').read()
old = '\tint dumpable = 0;\n\t/* Don\'t let security modules deny introspection */\n\tif (task == current)\n\t\treturn 0;'
new = ('\tint dumpable = 0;\n'
       '\t/* Don\'t let security modules deny introspection */\n'
       '\tif (task == current)\n'
       '\t\treturn 0;\n'
       '\t/*\n'
       '\t * pisces: /proc/self/fd/N resolves to the thread group leader rather than\n'
       '\t * to the calling thread, so "task == current" above does not catch it.\n'
       '\t * The lookup then falls through to the dumpable check below, and because\n'
       '\t * Android marks system_server non-dumpable (prctl(PR_SET_DUMPABLE, 0))\n'
       '\t * a thread ends up denied access to its own process\' descriptors.  That\n'
       '\t * made ParcelFileDescriptor.getFile() fail with\n'
       '\t * "readlink failed: EACCES" for every PackageInstaller write.  Threads of\n'
       '\t * one process share credentials, mm and fds, so inspecting each other is\n'
       '\t * not an information leak - allow it, as newer kernels do.\n'
       '\t */\n'
       '\tif (same_thread_group(task, current))\n'
       '\t\treturn 0;')
if 'same_thread_group(task, current)' not in s:
    assert old in s, 'ptrace.c anchor not found'
    s = s.replace(old, new, 1)
    io.open(p, 'w', encoding='utf-8').write(s)
    print('ptrace.c patched')
else:
    print('ptrace.c already patched')

# 2. security/selinux/ss/services.c -- no policy means "allow unknown".
p = 'security/selinux/ss/services.c'
s = io.open(p, encoding='utf-8').read()
old = 'int security_get_allow_unknown(void)\n{\n\treturn policydb.allow_unknown;\n}'
new = ('int security_get_allow_unknown(void)\n'
       '{\n'
       '\t/* pisces: the 3.4 policy parser cannot load the Android 9 policy, so no\n'
       '\t * policy is ever installed and policydb stays zeroed.  allow_unknown then\n'
       '\t * reads 0, which makes /sys/fs/selinux/deny_unknown report 1 and turns\n'
       '\t * every unknown class - fileSystem included - into a denial.  That is what\n'
       '\t * stops system_server from reading an APK handed to it by the package\n'
       '\t * installer.  With no policy loaded there is nothing to be strict about,\n'
       '\t * so report "allow" instead.\n'
       '\t */\n'
       '\tif (!ss_initialized)\n'
       '\t\treturn 1;\n'
       '\treturn policydb.allow_unknown;\n'
       '}')
if 'if (!ss_initialized)' not in s:
    assert old in s, 'services.c anchor not found'
    s = s.replace(old, new, 1)
    io.open(p, 'w', encoding='utf-8').write(s)
    print('services.c patched')
else:
    print('services.c already patched')

# 3. defconfig -- stamp the kernel with a custom local version so uname shows
#    e.g. 3.4.113-xiecore.  LOCALVERSION_AUTO is turned off so the stamp stays
#    deterministic even if the tree later becomes a git checkout.
for p in sorted(glob.glob('arch/arm/configs/tegra11_pisces_*defconfig')):
    s = io.open(p, encoding='utf-8').read()
    orig = s
    s = s.replace('CONFIG_LOCALVERSION=""', 'CONFIG_LOCALVERSION="-xiecore"', 1)
    s = s.replace('CONFIG_LOCALVERSION_AUTO=y',
                  '# CONFIG_LOCALVERSION_AUTO is not set', 1)
    if s != orig:
        io.open(p, 'w', encoding='utf-8').write(s)
        print('%s stamped -xiecore' % p)
    else:
        print('%s already stamped' % p)
PYEOF
