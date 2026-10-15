# Terms

How Lujarch words what the user sees, in English, Simplified Chinese and Japanese. Every string
Lujarch adds or changes follows this file; each feature adds its own terms to the table.

## Rules

- `Lujarch` is never translated or transliterated.
- `Root` keeps its capital R in every language; `root` in lower case is only the Unix account.
- A term Android already has takes the exact wording of Android's own translation in the tree
  (Settings, the framework, SettingsLib, PermissionController).
- Register is Android's: English sentence case; Chinese plain and formal; Japanese です/ます in
  sentences, nouns or the 〜する form in titles and buttons.
- Row titles stay short; explanations go in summaries.
- A count that can be 1 is a plural resource in every language.
- Placeholders keep their positional form (`%1$s`); their order in a sentence may change.
- Lists of names inside a sentence use the language's separator: `, ` in English, `、` in Chinese
  and Japanese.
- Settings search keywords: the language's own words first, then the English technical names
  users also type.

## Chinese spacing and quotes

As Android's own Simplified Chinese strings:

- One space between Chinese and a Latin word, abbreviation or digit: 自动开启 WLAN,
  最多可以添加 %d 个指纹.
- A placeholder counts as the text it shows: a value the system produces (a size, a version, a
  time) takes a space where it begins or ends with a digit or Latin letter; a name someone chose
  (an app, a device, a network, a file) is quoted with “” and takes no space: 要与“%1$s”配对吗？
- No space between Chinese punctuation and Latin text, digits or placeholders.

## Japanese spacing

As Android's own Japanese strings: one half-width space between Japanese and a Latin word or a
digit (Lujarch チャネル, Wi-Fi ネットワーク); none next to Japanese punctuation.

## Terms

| English | 简体中文 | 日本語 |
|---|---|---|
| Lujarch | Lujarch | Lujarch |
| Lujarch Settings | Lujarch 设置 | Lujarch 設定 |
| Root access | Root 权限 | Root アクセス |
| App info | 应用信息 | アプリ情報 |
| Permissions | 权限 | 権限 |
| Notifications | 通知 | 通知 |
| System updates | 系统更新 | システムアップデート |
| Install local update | 安装本地更新 | ローカル アップデートをインストール |
| Build | 版本 | ビルド |
| Confirm this build | 确认此版本 | このビルドを確認 |
| Confirm automatically | 自动确认 | 自動的に確認 |
| Return to the previous build | 回到上一版本 | 以前のビルドに戻す |
| Settings | 设置 | 設定 |
| Allow | 允许 | 許可する |
| Allow all the time | 始终允许 | 常に許可 |
| Allow only while using the app | 仅在使用该应用时允许 | アプリの使用中のみ許可 |
| Ask every time | 每次都询问 | 毎回確認する |
| Return empty data | 返回空数据 | 空のデータを返す |
| Don't allow | 不允许 | 許可しない |
| Permission manager | 权限管理器 | 権限マネージャー |
| Privacy dashboard | 隐私信息中心 | プライバシー ダッシュボード |
| Special app access | 特殊应用权限 | 特別なアプリアクセス |
| See more | 查看更多 | 詳細 |
| Install unknown apps | 安装未知应用 | 不明なアプリのインストール |
| App battery usage | 应用的电池使用情况 | アプリのバッテリー使用量 |
| Mobile data & Wi-Fi | 移动数据和 WLAN | モバイルデータと Wi-Fi |
| Allow restricted settings | 允许受限制的设置 | 制限付き設定を許可 |
| Phone | 电话 | 電話 |
| Basic phone info only | 仅基本电话信息 | 電話の基本情報のみ |
| Motion and environment sensors | 运动和环境传感器 | モーションと環境のセンサー |
| Clipboard | 剪贴板 | クリップボード |
| Installed apps | 已安装的应用 | インストール済みアプリ |
| Device identifiers | 设备标识符 | デバイス ID |
| Device info | 设备信息 | デバイス情報 |
| Network info | 网络信息 | ネットワーク情報 |
| Other access | 其他访问 | その他のアクセス |
| Show pop-up messages | 显示弹出消息 | ポップアップ メッセージの表示 |
| Returned empty data | 返回了空数据 | 空のデータを返しました |
| Asked | 已询问 | 確認しました |
| Autostart | 自启动 | 自動起動 |
| Defaults for new apps | 新应用的默认设置 | 新しいアプリのデフォルト |
| Not changed | 不更改 | 変更しない |
| Wait for an answer | 等待回答的时长 | 回答の待ち時間 |
| Keep history | 记录保留时长 | 履歴の保持期間 |
| Explain why apps stop | 说明应用停止的原因 | アプリが停止した理由を表示 |
| Decided by Android | 由 Android 决定 | Android が決定 |
| Lock | 锁定 | ロック |
| Network & internet | 网络和互联网 | ネットワークとインターネット |
| Proxy | 代理 | プロキシ |
| Use proxy | 使用代理 | プロキシを使用 |
| Rule | 规则 | ルール |
| Global | 全局 | グローバル |
| Direct | 直连 | 直接接続 |
| Exit node for Global | 全局出口节点 | グローバル モードの出口ノード |
| Follow the rules | 按规则分流 | ルールに従う |
| Reject | 拒绝 | 拒否 |
| Proxy group | 代理组 | プロキシ グループ |
| Bypass the proxy entirely | 完全绕过代理 | プロキシを完全にバイパス |
| Apps that use the proxy | 使用代理的应用 | プロキシを使うアプリ |
| Node | 节点 | ノード |
| Subscriptions | 订阅 | サブスクリプション |
| Connections | 连接 | 接続 |
| Logs | 日志 | ログ |
| Core settings | 内核设置 | コア設定 |
| Repair after network changes | 网络变化后修复 | ネットワーク変更後に修復 |
| Block IPv6 while the proxy runs | 代理运行时阻止 IPv6 | プロキシの実行中は IPv6 をブロック |
| Change the control key | 更换控制密钥 | 制御キーを変更 |
| System icons | 系统图标 | システムアイコン |
| Connected devices | 已连接的设备 | 接続設定 |
| Sync | 同步 | 同期 |
| Use sync | 使用同步 | 同期を使用 |
| Only on Wi-Fi | 仅在连接 WLAN 时运行 | Wi-Fi のみ |
| Only while charging | 仅在充电时运行 | 充電中のみ |
| Device identity | 设备身份 | デバイスの識別情報 |
| Sync storage | 同步存储 | 同期ストレージ |
| Passwords & passkeys | 密码和通行密钥 | パスワードとパスキー |
| Preferred service | 首选服务 | 優先するサービス |
| Lujarch Vault | Lujarch 保险库 | Lujarch 保管庫 |
| 2FA codes | 两步验证码 | 2FA コード |
| Passwords | 密码 | パスワード |
| Sources | 数据源 | ソース |
| Allow Root access | 允许 Root 权限 | Root アクセスを許可 |
| Root profile | Root 配置 | Root プロファイル |
| Allowed with a custom profile | 已允许（自定义配置） | 許可（カスタム プロファイル） |
| Restore default profile | 恢复默认配置 | デフォルトのプロファイルに戻す |
| Mount namespace | 挂载命名空间 | マウント名前空間 |
