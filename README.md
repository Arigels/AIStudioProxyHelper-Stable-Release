# AI Studio 反代助手 (Android)

专为移动端打造的 **Google AI Studio 反向代理服务 ([DeconstructedCube/aistudio-api](https://github.com/DeconstructedCube/aistudio-api))** 一键搭建与管理 Android App。

通过联动手机上的 **Termux**（F-Droid / GitHub 版），将原本需要敲命令行的克隆、编译本地 WAA IPC 签名引擎（SpiderMonkey 152）、启动保活、导入 Google Cookie、查看模型列表与配置酒馆/第三方客户端全部图形化、一键化。

---

## 核心功能

App 有 **4 个页签**：搭建 / 账号 / 使用 / AI，围绕「一键部署反代」这一件事。

1. **搭建（首页）**
   - 主区只有一个大按钮：没装过显示【一键全自动搭建】，装过显示【一键启动服务】，运行中显示【停止服务】。
   - **支持无障碍自动操控**：开启系统无障碍里的「AI Studio 反代自动搭建助手」后，点一下按钮就会自动打开 Termux、自动等待首次环境初始化、自动长按粘贴、自动按回车开始搭建，同时顺带开启 `allow-external-apps` 与唤醒锁，全程无需手动输代码。
   - 回车识别采用 4 套策略（IME 回车动作 / 文案匹配 / 右下角按键定位 / 坐标兜底）并自动重试，命令末尾补换行，兼容常见中文输入法与「粘贴即执行」的 Termux 版本。
   - 开启前会先弹出图文说明，专门提示小米/红米/OPPO/vivo/荣耀等机型上的「**已限制设置 → 解除限制**」小字，避免新手在无障碍页卡住；无法开启时自动降级为「复制命令 + 打开 Termux」手动粘贴。
   - 端口、代理、管理密码、安装目录、防杀后台设置全部折叠进「高级设置」，默认隐藏。
   - **悬浮窗进度**：在高级设置里开启「显示在其他应用上层」后，Termux 上方会浮一个小胶囊，实时显示当前阶段（安装依赖 / 克隆 / 编译引擎 / 启动）与已用时长，失败时直接显示原因。
   - 实时显示服务状态、签名引擎状态、账号数、模型数与局域网 IP。

2. **账号（「账号」页）**
   - 内置 **5 步获取 Cookie 教程**：下载火狐 / 狐猴浏览器 → 安装 Cookie-Editor → 登录 AI Studio → 导出 JSON → 粘贴导入，每步都带可点的下载/打开按钮，并自动检测手机是否已装火狐或狐猴。
   - 复制 Cookie-Editor 导出的 JSON，粘贴后一键导入，自动识别全部已登录子账号（`u/0`, `u/1`...）。
   - 已导入账号的邮箱与额度等级一目了然。

3. **使用（「使用」页）**
   - 一键复制本机地址 / 局域网地址（供电脑酒馆连手机），一键测试 `/v1beta/models` 并列出可用模型。
   - **报错翻译器**：把 Termux 里的报错粘贴进来，自动用大白话解释原因与修法（端口权限、端口占用、网络下载失败、403/429、内存不足等常见问题）。
   - **手机重启/服务掉线后的一键恢复** 与常见问题解答。

4. **AI（「AI」页）—— 反代专属排障 Agent**
   - 填入你自己的 **OpenAI 兼容接口**（地址 + API Key + 模型名），Key 只存本机、直连你填的接口。
   - AI 通过 App 提供的**受限工具**工作：`get_status` 查状态、`get_log` 读日志、`run` 执行 shell 命令、`install/start/stop` 管理服务、`set_port` 改端口，最后 `finish` 给结论。
   - 只能做反代部署这一件事，被明确禁止执行删数据/改系统等危险操作；每一步「思考 / 执行 / 结果」都在界面上可见。
   - 配套的本地日志回传：安装/执行命令的输出会通过 `127.0.0.1:8099` 送回 App，AI 与「报错翻译器」都能读到。

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
4. 点进运行记录，在底部 **Artifacts** 下载 **`AIStudioProxyHelper-APK`**，解压后将 `AIStudioProxyHelper-v1.2.0.apk` 传到安卓手机直接安装即可！

> 如果本机已安装 Android Studio，也可直接打开 `aistudio-proxy-android` 目录，点击 **Build → Build APK(s)** 本地编译。

---

## 手机端使用流程

1. 安装 **Termux**（请务必从 [F-Droid](https://f-droid.org/packages/com.termux/) 或 [GitHub Releases](https://github.com/termux/termux-app/releases) 下载 64 位版本 `arm64-v8a`，**切勿使用 Google Play 商店旧版**）。
2. 打开 **AI Studio 反代助手** → 点击 **「去开启自动操控权限」**，在系统无障碍设置里开启 **「AI Studio 反代自动搭建助手」**。
3. 回到 App 点击 **【一键全自动搭建】**，App 会自动打开 Termux 并输入执行完整安装与启动脚本，等待编译完成、首页状态卡变绿即成功。
4. 切到 **「账号」** 页，按内置教程操作：装 [火狐国际版](https://www.mozilla.org/zh-CN/firefox/android/)（或国内应用商店的狐猴浏览器）→ 安装 [Cookie-Editor](https://addons.mozilla.org/zh-CN/firefox/addon/cookie-editor/) → 登录 aistudio.google.com → **Export as JSON** → 回到 App 粘贴并点 **「导入并自动识别所有账号」**。注意手机版 Chrome / Edge 不支持扩展。
5. 切到 **「使用」** 页测试模型列表，复制地址填入酒馆（选择 **Google Gemini** 协议）即可使用！
6. **防杀后台**：下拉通知栏点击 Termux 通知上的 `Acquire Wakelock`，并在系统电池设置里将 Termux 设为「无限制」（App 的「搭建 → 高级设置」里也有「开启唤醒锁」按钮）。
7. **重启恢复**：手机重启或服务掉线后，打开 App →「使用」页点【一键恢复服务】即可，无需重新安装。
