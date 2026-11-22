# AI Studio 反代助手 (Android)

专为移动端打造的 **Google AI Studio 反向代理服务 ([DeconstructedCube/aistudio-api](https://github.com/DeconstructedCube/aistudio-api))** 一键搭建与管理 Android App。

通过联动手机上的 **Termux**（F-Droid / GitHub 版），将原本需要敲命令行的克隆、编译本地 WAA IPC 签名引擎（SpiderMonkey 152）、启动保活、导入 Google Cookie、查看模型列表与配置酒馆/第三方客户端全部图形化、一键化。

---

## 核心功能

1. **一键搭建与服务控制（「搭建」页）**
   - 界面极简：主区只有一个大按钮——没装过显示【一键搭建并启动】，装过显示【启动服务】，运行中显示【停止服务】。
   - 首次准备收成一张小卡片（装 Termux → 授权 → 开启一键调用），每项做完自动打勾，全部完成后卡片自动消失。
   - 自动检测 64 位架构、Termux 安装状态与调用权限；命令发送失败时只复制「一次性开启 Termux 一键调用」的设置命令，不会再把完整安装命令甩给用户。
   - 端口、代理、管理密码、安装目录等参数全部折叠进「高级设置」，默认隐藏，不懂可以完全不管。
   - 实时轮询本机服务健康状态、WAA IPC 签名引擎运行状态、账号数、模型数与手机局域网 IP。

2. **多账号 Cookie 导入（「账号」页）**
   - **粘贴导入（推荐）**：支持一键读取剪贴板中的 Cookie-Editor JSON / Header / Netscape，直连本机 `/api/accounts/probe-import` 自动探测多登子账号（`u/0`, `u/1`...）并入库。
   - **内置网页登录提取**：内置 WebView 打开 `https://aistudio.google.com/`，登录后一键读取并导入 Cookie。

3. **内置 Web 管理控制台（「控制台」页）**
   - 直接内嵌 `http://127.0.0.1:8080` 原生 Vue 3 管理面板，管理多账号分组、预设方案与 `config.yaml` 热重载。

4. **客户端连接助手（「客户端」页）**
   - 一键测试 `/v1beta/models` 连通性并列出已同步的可用模型。
   - 一键复制本机地址、局域网地址（供电脑酒馆连手机）与 `/v1beta` 地址。
   - 内置 **SillyTavern（酒馆）**、**Cherry Studio / NextChat** 配置指引与防踩坑提示（严格使用 Google Gemini 协议，勿选 OpenAI 格式）。

5. **自动引导与内置教程（「教程」页 + 搭建页引导卡）**
   - 服务上线后，「搭建」页会自动弹出「下一步」引导卡：未导账号 → 引导导入 Cookie；已导账号 → 引导测试并配置客户端；全部完成 → 提示查看教程。
   - 「教程」页提供完整使用指南：首次部署流程、Cookie 导入详解、客户端配置、**手机重启/服务掉线后的恢复步骤（一键恢复服务）**、常见问题 FAQ 与更新修复，并根据当前状态自动给已完成步骤打勾。

---

## 如何免费云端编译出可安装的 APK（GitHub Actions）

无需在本地安装 Android Studio 或 JDK，直接推送到 GitHub 即可免费云端出包：

1. 在 GitHub 上新建一个空仓库（Public 或 Private 均可）。
2. 在本目录（`aistudio-proxy-android`）内执行：
   ```bash
   git init
   git add .
   git commit -m "Initial commit: AI Studio Proxy Helper Android App"
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/<你的仓库名>.git
   git push -u origin main
   ```
3. 打开 GitHub 仓库的 **Actions** 标签页，等待 `Build Android APK` 工作流跑完（约 2~3 分钟）。
4. 点进运行记录，在底部 **Artifacts** 下载 **`AIStudioProxyHelper-APK`**，解压后将 `AIStudioProxyHelper-v1.0.0.apk` 传到安卓手机直接安装即可！

> 如果本机已安装 Android Studio，也可直接打开 `aistudio-proxy-android` 目录，点击 **Build → Build APK(s)** 本地编译。

---

## 手机端使用流程

1. 安装 **Termux**（请务必从 [F-Droid](https://f-droid.org/packages/com.termux/) 或 [GitHub Releases](https://github.com/termux/termux-app/releases) 下载 64 位版本，**切勿使用 Google Play 商店旧版**）。
2. 打开 **AI Studio 反代助手** → 在「首次准备」卡片中：
   - 点击 **「去授权」** 允许 App 调用 Termux；
   - 点击 **「复制设置命令并打开 Termux」**，在 Termux 中长按粘贴回车执行一次即可（只需做一次）。
3. 点击 **【一键搭建并启动】**，等待自动下载依赖并编译本地 `waa-signer` 引擎完成，状态卡变绿即成功。
4. 切到 **「账号」** 页，粘贴通过 Firefox/Edge + Cookie-Editor 导出的 Google AI Studio Cookie（或使用内置网页登录提取），点击 **「自动探活并导入全部子账号」**。
5. 切到 **「客户端」** 页测试模型列表，复制地址填入酒馆 / Cherry Studio / NextChat（选择 **Google Gemini** 协议）即可使用！
6. **防杀后台**：下拉通知栏点击 Termux 通知上的 `Acquire Wakelock`，并在系统电池设置里将 Termux 设为「无限制」。
7. **重启恢复**：手机重启或服务掉线后，打开 App → 「搭建」页点【启动服务】即可恢复，无需重新安装；完整说明见 App 内「教程」页。
