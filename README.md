# Lujarch

[中文](#中文) · [English](#english)

## 中文

Lujarch 是小米 13（`fuxi`）上的 Android 系统，基于 LineageOS 24.0（Android 17），用所有者自己的钥匙签名。这个仓库是它唯一的来源：目标、实现、测试和工具都在这里。

### 原则

1. **只呈现目标**：仓库里只有目标、实现、对照目标的测试和工具。没有计划、待办、状态记录、决策记录，也没有兼容旧版本的代码。进度由工具算出，历史只在 git 里。
2. **唯一真实**：每条信息只在一个地方；别处要用，就从它生成或对它检查，不手抄。
3. **最高一致性**：同一类事只有一种做法，由检查强制。
4. **不记录决策**：文档只写"是什么"，不写为什么、从何时起。
5. **以目标为唯一约束**：不为旧仓库、旧命名、旧数据格式保留任何东西；旧数据一次性迁移，迁移在所有者的仓库里。

### 内容的三分

| 类别 | 内容 | 位置 |
|---|---|---|
| 系统内 | 镜像里的一切：通用功能、各机型的机型层、全部预装应用 | 本仓库 |
| 系统外 | 只用于测试的组件，构建与发布工具 | 本仓库的 `test/`、`release/`、`tools/`，从不进镜像 |
| 只属于所有者 | 手机上的个人设置和数据；电脑上的路径、钥匙位置、镜像、远程访问；关于自己手机的规则、送达、备份、一次性迁移 | `lujarch-owner` 仓库；个人设置本身只在手机上 |

### 目录

| 路径 | 内容 |
|---|---|
| `target/` | 目标：系统应当是什么，由所有者批准 |
| `process/` | 仓库怎么运作：布局、约定、目标格式、测试流程、发布流程 |
| `system/features/<功能>/` | 一个功能的全部实现：补丁、组件、SELinux、开关、测试 |
| `system/device/<机型>/` | 机型层：`fuxi`（小米 13）和 `vm`（虚拟设备） |
| `system/order` | 全部补丁的应用顺序 |
| `lock.toml`、`upstream.xml` | 构建的全部固定输入 |
| `test/`、`release/` | 测试流程、发布流程 |
| `tools/lujarch` | 命令行入口 |

### 两条流程

- **测试流程**：随时在电脑和虚拟设备上运行，用公开的测试钥匙签名；产物不离开电脑，任何个人手机都不参与。
- **发布流程**：所有者要求时，从通过测试流程的提交构建，用正式钥匙签名。送到手机是 `lujarch-owner` 的事。

### 语言

界面：英文、简体中文、日文。文档：英文；本文件中英双语。

### 从哪里开始

- 系统是什么：`target/core.md`，以及 `target/` 下各功能的文件。
- 怎么工作：`AGENTS.md` 和 `process/`。
- 检查与进度：`tools/lujarch check`、`tools/lujarch status`。

## English

Lujarch is an Android system for the Xiaomi 13 (`fuxi`), built on LineageOS 24.0 (Android 17) and signed with its owner's keys. This repository is its only source: the target, the implementation, the tests and the tools are all here.

### Principles

1. **Only the target.** The repository holds the target, its implementation, the tests against it and the tools. No plans, to-do lists, status logs, decision records or compatibility code for older versions. Progress is computed by the tools; history is git's.
2. **One source of truth.** Every fact lives in one place; what else needs it is generated from it or checked against it, never copied by hand.
3. **Highest consistency.** One way for each kind of thing, enforced by checks.
4. **No decision records.** Documents say what is, never why or since when.
5. **The target is the only constraint.** Nothing is kept for an older repository, name or data format; old data is migrated once, by a migration in the owner's repository.

### Three kinds of content

| Kind | What | Where |
|---|---|---|
| In the system | Everything in the image: the generic features, each device's layer, all preinstalled apps | this repository |
| Outside the system | Test-only components, build and release tooling | this repository's `test/`, `release/`, `tools/`; never in an image |
| The owner's own | Personal settings and data on the phone; the computer's paths, key locations, mirrors and remote access; rules for the owner's phone, delivery, backups, one-time migrations | the `lujarch-owner` repository; personal settings themselves live only on the phone |

### Layout

| Path | What |
|---|---|
| `target/` | The target: what the system is, approved by the owner |
| `process/` | How the repository works: layout, conventions, target format, test flow, release flow |
| `system/features/<feature>/` | One feature's whole implementation: patches, components, SELinux, switches, tests |
| `system/device/<device>/` | Device layers: `fuxi` (Xiaomi 13) and `vm` (the virtual device) |
| `system/order` | The order in which every patch is applied |
| `lock.toml`, `upstream.xml` | Every pinned input of a build |
| `test/`, `release/` | The test flow and the release flow |
| `tools/lujarch` | The command line |

### Two flows

- **Test flow**: runs at any time on the computer and on virtual devices, signed with the public test keys; its outputs never leave the computer and no personal phone takes part.
- **Release flow**: when the owner asks, built from a commit that passed the test flow and signed with the release keys. Bringing it to a phone is `lujarch-owner`'s job.

### Languages

UI: English, Simplified Chinese, Japanese. Documents: English; this file is in Chinese and English.

### Where to start

- What the system is: `target/core.md` and each feature's file in `target/`.
- How to work: `AGENTS.md` and `process/`.
- Checks and progress: `tools/lujarch check`, `tools/lujarch status`.
