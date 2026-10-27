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
| Use passwords | 使用密码 | パスワードを使用 |
| Use 2FA codes | 使用两步验证码 | 2FA コードを使用 |
| Set up passwords | 设置密码数据源 | パスワードのソースを設定 |
| Set up 2FA codes | 设置两步验证码数据源 | 2FA コードのソースを設定 |
| Password file | 密码文件 | パスワード ファイル |
| File | 文件 | ファイル |
| Key file | 密钥文件 | キーファイル |
| Accounts | 账号 | アカウント |
| Import from another file | 从其他文件导入 | 他のファイルからインポート |
| Allow Root access | 允许 Root 权限 | Root アクセスを許可 |
| Root profile | Root 配置 | Root プロファイル |
| Allowed with a custom profile | 已允许（自定义配置） | 許可（カスタム プロファイル） |
| Restore default profile | 恢复默认配置 | デフォルトのプロファイルに戻す |
| Mount namespace | 挂载命名空间 | マウント名前空間 |
| Files | 文件 | ファイル |
| Music | 音乐 | 音楽 |
| App Store | 应用商店 | アプリストア |
| Choose again | 重新选择 | 選択し直す |
| Battery | 电池 | バッテリー |
| Battery usage | 电池使用情况 | バッテリー使用量 |
| Record battery usage | 记录电池用量 | バッテリー使用量を記録 |
| Full battery history | 完整电池历史 | バッテリーの完全な履歴 |
| Lowest CPU frequency when idle | 空闲时使用最低频率 | アイドル時は最低の CPU 周波数 |
| Linux development environment | Linux 开发环境 | Linux 開発環境 |
| Use Linux development environment | 使用 Linux 开发环境 | Linux 開発環境を使用 |
| Root sessions | Root 会话 | Root セッション |
| Remote access | 远程访问 | リモート アクセス |
| Desktop | 桌面 | デスクトップ |
| Open desktop | 打开桌面 | デスクトップを開く |
| Install the desktop | 安装桌面 | デスクトップをインストール |
| Terminal | 终端 | ターミナル |
| Services | 服务 | サービス |
| Microphone | 麦克风 | マイク |
| Camera | 相机 | カメラ |
| Location | 位置信息 | 位置情報 |
| Vibration | 振动 | バイブレーション |
| Android's quick settings | Android 快捷设置 | Android のクイック設定 |
| Shared storage | 共享存储 | 共有ストレージ |
| Caffeine | 保持亮屏 | カフェイン |
| Wine Settings | Wine 设置 | Wine の設定 |
| Windows Programs | Windows 程序 | Windows プログラム |
| System | 系统 | システム |
| Modes | 模式 | モード |
| Status bar | 状态栏 | ステータスバー |
| Status bar items | 状态栏项目 | ステータスバーの項目 |
| Privacy indicators | 隐私指示器 | プライバシー インジケーター |
| Lyrics | 歌词 | 歌詞 |
| Media | 媒体 | メディア |
| Fit app icons | 适配应用图标 | アプリアイコンを揃える |
| Wallpaper & style | 壁纸与风格 | 壁紙とスタイル |
| Icon | 图标 | アイコン |
| Original | 原始 | オリジナル |
| Fitted | 适配 | 調整 |
| Redrawn | 重绘 | 再描画 |
| Fit notification icons | 适配通知图标 | 通知アイコンを揃える |
| App drawer sections | 应用抽屉分区 | アプリドロワーのセクション |
| Sort apps in drawer | 应用抽屉排序 | アプリドロワーの並べ替え |
| By name | 按名称 | 名前順 |
| By icon color | 按图标颜色 | アイコンの色順 |
| Unnamed section | 未命名分区 | 名前のないセクション |
| Lujarch motion | Lujarch 动效 | Lujarch モーション |
| Lock screen and fingerprint | 锁屏和指纹 | ロック画面と指紋 |
| Charging | 充电 | 充電 |
| Back gesture | 返回手势 | 「戻る」ジェスチャー |
| Volume | 音量 | 音量 |
| Small windows | 小窗 | 小ウィンドウ |
| Small window | 小窗 | 小ウィンドウ |
| Use small windows | 使用小窗 | 小ウィンドウを使用 |
| Open in small window | 在小窗中打开 | 小ウィンドウで開く |
| Release to open in a small window | 松手即在小窗中打开 | 指を離すと小ウィンドウで開きます |
| Move to dock | 收进停靠条 | ドックに移動 |
| Move to dock when the app below is touched | 碰到下面的应用时收进停靠条 | 下のアプリに触れたらドックに移動 |
| Live previews in the dock | 停靠条显示实时画面 | ドックにライブプレビューを表示 |
| At most | 同时最多 | 最大数 |
| All conversations | 所有对话 | すべての会話 |
| Selected conversations | 所选对话 | 選択した会話 |
| Dock | 停靠条 | ドック |
| Rail | 细轨 | レール |
| Background usage | 后台使用 | バックグラウンドでの使用 |
| Allow background usage | 允许后台使用 | バックグラウンドでの使用を許可 |
| Restricted | 受限 | 制限 |
| Optimized | 优化 | 最適化 |
| Chosen processes | 指定进程 | 指定したプロセス |
| Unrestricted | 无限制 | 制限なし |
| After it leaves the screen | 离开屏幕以后 | 画面を離れたあと |
| Add the main process | 一起指定主进程 | メイン プロセスも指定 |
| Run state | 运行状态 | 実行状態 |
| Foreground | 前台 | フォアグラウンド |
| Running in background | 后台运行 | バックグラウンドで実行中 |
| Paused | 已暂停 | 一時停止中 |
| Reclaimed | 已回收 | 回収済み |
| Stopped | 已停止 | 停止済み |
| Keep | 保留 | 保持 |
| Remove keep | 取消保留 | 保持を解除 |
| Kept | 已保留 | 保持中 |
| Opens again | 打开时重新加载 | 開くと再読み込み |
| User | 用户 | ユーザー |
| System | 系统 | システム |
| Memory | 内存 | メモリ |
| Device memory | 设备内存 | デバイスのメモリ |
| Apps | 应用 | アプリ |
| Free | 可用 | 空き |
| System services | 系统服务 | システム サービス |
| Show system processes | 显示系统进程 | システム プロセスを表示 |
| End this process | 结束这个进程 | このプロセスを終了 |
| Memory limit | 内存上限 | メモリの上限 |
| Use memory limit | 使用内存上限 | メモリの上限を使用 |
| Compressed | 压缩 | 圧縮 |
| No limit | 无限制 | 制限なし |
| When it needs more | 需要更多时 | さらに必要なとき |
| Hold it back | 让它等待 | 待機させる |
| End it at once | 立即结束 | すぐに終了 |
| End it after %1$s | %1$s后结束 | %1$s後に終了 |
| Also on screen | 在屏幕上时也限制 | 画面に表示中も制限 |
| Within its limits | 未超出上限 | 上限内 |
| Compressing | 正在压缩 | 圧縮中 |
| Needs more memory | 需要更多内存 | メモリが不足 |
| Storage limit | 存储上限 | ストレージの上限 |
| Use storage limit | 使用存储上限 | ストレージの上限を使用 |
| When it is full | 存满时 | いっぱいになったとき |
| Clear its cache first | 先清除缓存 | 先にキャッシュを削除 |
| Stop it saving | 停止保存 | 保存を停止 |
| Within its limit | 未超出上限 | 上限内 |
| Full since %1$s | 自 %1$s 起已满 | %1$s から上限に到達 |
| Cache last cleared %1$s | 上次清除缓存：%1$s | 前回のキャッシュ削除：%1$s |
| Files in shared storage, such as Download and media, are not counted | 共享存储中的文件（如“下载”和媒体文件）不计入 | 共有ストレージ内のファイル（ダウンロードやメディアなど）は含まれません |
| Running | 运行中 | 実行中 |
| End running | 结束运行 | 実行を終了 |
| View running | 查看运行中 | 実行中を表示 |
| Background activity | 后台活动 | バックグラウンド アクティビティ |
| Set to restricted | 设为受限 | 制限に設定 |
| Show running | 显示“运行中” | 「実行中」を表示 |
| Show app state | 显示应用状态 | アプリの状態を表示 |
| Show memory | 显示内存 | メモリを表示 |
