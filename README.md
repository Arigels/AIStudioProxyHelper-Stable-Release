# AI Studio 反代助手 (Android)

专为移动端打造的 **Google AI Studio 反向代理服务 ([DeconstructedCube/aistudio-api](https://github.com/DeconstructedCube/aistudio-api))** 一键搭建与管理 Android App。

通过联动手机上的 **Termux**（F-Droid / GitHub 版），将原本需要敲命令行的克隆、编译本地 WAA IPC 签名引擎（SpiderMonkey 152）、启动保活、导入 Google Cookie、查看模型列表与配置酒馆/第三方客户端全部图形化、一键化。

---

## 核心功能

App 只有 **3 个页签**：搭建 / 账号 / 使用，围绕「一键部署反代」这一件事。

1. **搭建（首页）**
   - 主区只有一个大按钮：没装过显示【一键搭建并启动】，装过显示【启动服务】，运行中显示【停止服务】。
   - 首次准备收成一张小卡片（装 Termux → 授权 → 开启一键调用），做完自动打勾、全部完成自动消失。
   - 发送命令失败时只复制「一次性开启 Termux 一键调用」的设置命令，不会把完整安装命令甩给用户。
   - 端口、代理、管理密码、安装目录等参数折叠进「高级设置」，默认隐藏。
   - 实时显示服务状态、签名引擎状态、账号数、模型数与局域网 IP。

2. **账号（「账号」页）**
   - 复制 Cookie-Editor 导出的 JSON，粘贴后一键导入，自动识别全部已登录子账号（`u/0`, `u/1`...）。
   - 已导入账号的邮箱与额度等级一目了然。

3. **使用（「使用」页）**
   - 一键复制本机地址 / 局域网地址（供电脑酒馆连手机），一键测试 `/v1beta/models` 并列出可用模型。
   - 内置 **SillyTavern（酒馆）**、**Cherry Studio / NextChat** 配置要点（必须选 Google Gemini 协议，勿选 OpenAI 格式）。
   - **手机重启/服务掉线后的一键恢复** 与常见问题解答。

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
4. 切到 **「账号」** 页导入 Cookie。注意 **Chrome / Edge 安卓版不支持扩展**，装不了 Cookie-Editor，请用下面任一方式导出：
   - **手机**：装 [Firefox 安卓版](https://www.mozilla.org/firefox/browsers/mobile/android/)，在 addons.mozilla.org 搜 Cookie-Editor 安装，登录 aistudio.google.com 后 Export as JSON；
   - **电脑（最稳）**：电脑 Chrome/Edge 装 Cookie-Editor 导出 JSON，发到手机（微信文件传输助手等）后再粘贴。
   然后点击 **「导入并自动识别所有账号」**。
5. 切到 **「使用」** 页测试模型列表，复制地址填入酒馆 / Cherry Studio / NextChat（选择 **Google Gemini** 协议）即可使用！
6. **防杀后台**：下拉通知栏点击 Termux 通知上的 `Acquire Wakelock`，并在系统电池设置里将 Termux 设为「无限制」（App 的「搭建 → 高级设置」里也有「开启唤醒锁」按钮）。
7. **重启恢复**：手机重启或服务掉线后，打开 App →「使用」页点【一键恢复服务】即可，无需重新安装。
