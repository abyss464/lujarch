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
| Autostart | 自启动 | 自動起動 |
| Defaults for new apps | 新应用的默认设置 | 新しいアプリのデフォルト |
| Not changed | 不更改 | 変更しない |
| Wait for an answer | 等待回答的时长 | 回答の待ち時間 |
| Keep history | 记录保留时长 | 履歴の保持期間 |
| Explain why apps stop | 说明应用停止的原因 | アプリが停止した理由を表示 |
| Decided by Android | 由 Android 决定 | Android が決定 |
| Lock | 锁定 | ロック |
