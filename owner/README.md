# Lujarch owner

[中文](#中文) · [English](#english)

## 中文

这个仓库放所有者自己的手机和电脑在 Lujarch 上的一切：电脑的配置、关于自己手机的规则、把正式版本送到手机、备份、一次性迁移，以及只给自己用的工具。系统本身在 `~/Code/apps/lujarch`，那里的东西任何一台小米 13 都能用；这里的东西只属于所有者。

个人设置本身只在手机上，由系统界面设置，不写进任何仓库。密钥、订阅、同步身份等秘密从不进这个仓库，备份放在 `host.toml` 指定的备份目录，只有所有者可读。

| 路径 | 内容 |
|---|---|
| `host.toml` | 电脑的配置：源码树和输出路径、签名钥匙位置、同步与构建资源、送达与备份设置；链接为 `~/.config/lujarch/host.toml` |
| `sync.gitconfig` | repo 同步用的 git 设置（镜像） |
| `process/delivery.md` | 把正式版本送到手机：唯一安装路径、装后检查、大版本试用、救援 |
| `process/backup.md` | 导出与恢复手机上的个人设置和数据 |
| `process/migration.md` | 从上一个系统一次性迁移到 Android 17 上的 Lujarch，用完删除 |
| `deliver/`、`backup/`、`migrate/` | 上面三件事的工具 |
| `tools/` | 只给所有者用的工具 |

agent 先读 `AGENTS.md`。文档用英文，本文件中英双语。

## English

This repository holds everything about the owner's own phone and computer for Lujarch: the computer's configuration, the rules for the owner's phone, bringing releases to it, backups, one-time migrations, and tools only the owner uses. The system itself is in `~/Code/apps/lujarch`, usable on any Xiaomi 13; what is here belongs to the owner alone.

Personal settings themselves live only on the phone, set in the system's UI, and are never written into a repository. Secrets (keys, subscriptions, sync identities) never enter this repository; backups stay in the backup directory set in `host.toml`, readable by the owner only.

| Path | What |
|---|---|
| `host.toml` | The computer's configuration: source tree and output paths, the signing keys' location, sync and build resources, delivery and backup settings; linked as `~/.config/lujarch/host.toml` |
| `sync.gitconfig` | Git settings used for repo sync (mirrors) |
| `process/delivery.md` | Bringing a release to the phone: the one install path, the delivery check, the major-version trial, rescue |
| `process/backup.md` | Exporting and restoring the personal settings and data on the phone |
| `process/migration.md` | The one-time move from the previous system to Lujarch on Android 17, deleted after use |
| `deliver/`, `backup/`, `migrate/` | Tools for the three above |
| `tools/` | Tools only the owner uses |

Agents read `AGENTS.md` first. Documents are in English; this file is in Chinese and English.
