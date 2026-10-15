/*
 * Copyright (C) 2026 The Lujarch Project
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

package org.lujarch.root.settings


/**
 * Names the Root access page offers for a KernelSU root profile, as the
 * KernelSU manager offers them: Android's group IDs
 * (android_filesystem_config.h), the Linux capabilities up to
 * CAP_CHECKPOINT_RESTORE (the kernel's CAP_LAST_CAP), and SELinux domains
 * that exist in this system's policy.
 */
object RootIds {
    /** Group ID and Android's name for it; any other ID can be typed in. */
    val GROUPS: List<Pair<Int, String>> = listOf(
        0 to "root",
        1000 to "system",
        1001 to "radio",
        1002 to "bluetooth",
        1003 to "graphics",
        1004 to "input",
        1005 to "audio",
        1006 to "camera",
        1007 to "log",
        1010 to "wifi",
        1011 to "adb",
        1015 to "sdcard_rw",
        1023 to "media_rw",
        1028 to "sdcard_r",
        1032 to "package_info",
        1065 to "reserved_disk",
        1077 to "external_storage",
        1078 to "ext_data_rw",
        1079 to "ext_obb_rw",
        2000 to "shell",
        2001 to "cache",
        2002 to "diag",
        3001 to "net_bt_admin",
        3002 to "net_bt",
        3003 to "inet",
        3004 to "net_raw",
        3005 to "net_admin",
        3006 to "net_bw_stats",
        3007 to "net_bw_acct",
        3009 to "readproc",
        3010 to "wakelock",
        3011 to "uhid",
        3012 to "readtracefs",
        9997 to "everybody",
    )

    fun groupLabel(id: Int): String = GROUPS.firstOrNull { it.first == id }?.let { "${it.second} ($id)" } ?: id.toString()

    /** Capability names by number (CAP_ prefix left out). */
    val CAPABILITIES: List<String> = listOf(
        "CHOWN", "DAC_OVERRIDE", "DAC_READ_SEARCH", "FOWNER", "FSETID", "KILL", "SETGID", "SETUID", "SETPCAP",
        "LINUX_IMMUTABLE", "NET_BIND_SERVICE", "NET_BROADCAST", "NET_ADMIN", "NET_RAW", "IPC_LOCK", "IPC_OWNER",
        "SYS_MODULE", "SYS_RAWIO", "SYS_CHROOT", "SYS_PTRACE", "SYS_PACCT", "SYS_ADMIN", "SYS_BOOT", "SYS_NICE",
        "SYS_RESOURCE", "SYS_TIME", "SYS_TTY_CONFIG", "MKNOD", "LEASE", "AUDIT_WRITE", "AUDIT_CONTROL", "SETFCAP",
        "MAC_OVERRIDE", "MAC_ADMIN", "SYSLOG", "WAKE_ALARM", "BLOCK_SUSPEND", "AUDIT_READ", "PERFMON", "BPF",
        "CHECKPOINT_RESTORE",
    )

    fun capabilityNames(mask: Long): List<String> =
        CAPABILITIES.filterIndexed { i, _ -> mask and (1L shl i) != 0L }

    /**
     * SELinux domains for su sessions: KernelSU's own (unconfined, its
     * default) and Android's su domain (debuggable builds only). Others can
     * be typed in; the system service checks that the policy has them.
     */
    val DOMAINS: List<Pair<String, Int>> = listOf(
        "u:r:ksu:s0" to R.string.root_profile_domain_ksu,
        "u:r:su:s0" to R.string.root_profile_domain_su,
    )
}
