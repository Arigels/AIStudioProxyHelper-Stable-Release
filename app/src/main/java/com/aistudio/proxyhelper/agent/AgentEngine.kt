package com.aistudio.proxyhelper.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 一行 Agent 轨迹，供界面展示。role: think / action / observation / answer / error */
data class AgentLine(val role: String, val text: String)

data class AgentConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val useLocalProxy: Boolean = false,
    val localBaseUrl: String = "http://127.0.0.1:8080",
    val localModel: String = "gemini-3.7-flash",
    val localApiKey: String = "aistudio-api"
)

/**
 * 只做「手机端 aistudio-api 反代」这一件事的 ReAct Agent。
 * 模型通过固定格式调用 App 提供的受限工具，不接触任意系统权限。
 */
class AgentEngine {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun run(
        config: AgentConfig,
        problem: String,
        execute: suspend (String, String) -> String,
        onLine: suspend (AgentLine) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val history = mutableListOf<Pair<String, String>>()
        history.add("system" to SYSTEM_PROMPT)
        history.add("user" to "用户描述的问题：$problem")

        repeat(MAX_STEPS) {
            val reply = try {
                chat(config, history)
            } catch (e: Exception) {
                onLine(AgentLine("error", "调用 AI 接口失败：${e.message}"))
                return@withContext "调用 AI 接口失败：${e.message}"
            }
            onLine(AgentLine("think", reply.trim()))
            history.add("assistant" to reply)

            val action = parseAction(reply)
            if (action == null) {
                onLine(AgentLine("error", "AI 没有按格式输出动作，已停止。"))
                return@withContext "AI 输出格式异常，请重试或换一个模型。"
            }
            if (action.name == "finish") {
                val answer = action.args.ifBlank { "处理完成。" }
                onLine(AgentLine("answer", answer))
                return@withContext answer
            }

            onLine(AgentLine("action", ("${action.name} ${action.args}").trim()))
            val observation = try {
                execute(action.name, action.args)
            } catch (e: Exception) {
                "工具执行出错：${e.message}"
            }
            onLine(AgentLine("observation", observation))
            history.add("user" to "OBSERVATION:\n$observation")
        }
        "已尝试 $MAX_STEPS 步仍未解决。可以把上面的记录复制给开发者进一步排查。"
    }

    private fun chat(config: AgentConfig, history: List<Pair<String, String>>): String {
        return if (config.useLocalProxy) chatLocalGemini(config, history) else chatOpenAi(config, history)
    }

    /** 简单连通性测试：发一句最短请求，返回模型的原话。 */
    fun ping(config: AgentConfig): String {
        val history = listOf("user" to "只回复两个字：正常")
        val reply = chat(config, history).trim()
        if (reply.isEmpty()) throw IllegalStateException("接口返回内容为空")
        return reply.take(80)
    }

    /** 拉取可用模型列表：本机反代走 Gemini 协议，外部走 OpenAI 兼容 /models。 */
    fun listModels(config: AgentConfig): List<String> {
        val candidates: List<Pair<String, Map<String, String>>> = if (config.useLocalProxy) {
            val base = config.localBaseUrl.trim().trimEnd('/')
            val key = config.localApiKey.ifBlank { "aistudio-api" }
            listOf(
                "$base/v1beta/models" to mapOf("x-goog-api-key" to key),
                "$base/v1/models" to mapOf("x-goog-api-key" to key)
            )
        } else {
            val base = config.baseUrl.trim().trimEnd('/')
            if (base.isBlank() || config.apiKey.isBlank()) {
                throw IllegalStateException("还没有填写接口地址或 API Key")
            }
            val auth = mapOf("Authorization" to "Bearer ${config.apiKey}")
            listOf(
                "$base/models" to auth,
                "$base/v1/models" to auth
            ).distinctBy { it.first }
        }

        val errors = mutableListOf<String>()
        for ((url, headers) in candidates) {
            try {
                return fetchModelsAt(url, headers)
            } catch (e: Exception) {
                errors.add("${url.substringAfter("//").take(40)} → ${e.message?.take(60)}")
            }
        }
        throw IllegalStateException(errors.joinToString("；").ifBlank { "没有可用的模型接口" })
    }

    private fun fetchModelsAt(url: String, headers: Map<String, String>): List<String> {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${body.take(120)}")
            }
            val obj = JSONObject(body)
            val result = mutableListOf<String>()
            obj.optJSONArray("models")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val name = arr.optJSONObject(i)?.optString("name").orEmpty()
                    if (name.isNotBlank()) result.add(name.removePrefix("models/"))
                }
            }
            obj.optJSONArray("data")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank()) result.add(id)
                }
            }
            if (result.isEmpty()) {
                throw IllegalStateException("返回里没有模型字段：${body.take(100)}")
            }
            return result.distinct().sorted()
        }
    }

    private fun chatOpenAi(config: AgentConfig, history: List<Pair<String, String>>): String {
        val base = config.baseUrl.trim().trimEnd('/')
        if (base.isBlank() || config.apiKey.isBlank()) {
            throw IllegalStateException("还没有填写 AI 接口地址或 API Key")
        }
        val messages = JSONArray()
        history.forEach { (role, content) ->
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val payload = JSONObject().apply {
            put("model", config.model.ifBlank { "deepseek-chat" })
            put("messages", messages)
            put("temperature", 0.2)
            put("stream", false)
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${body.take(300)}")
            }
            val obj = JSONObject(body)
            val choices = obj.optJSONArray("choices")
                ?: throw IllegalStateException("响应里没有 choices：${body.take(200)}")
            val message = choices.optJSONObject(0)?.optJSONObject("message")
            val content = message?.optString("content").orEmpty()
            if (content.isNotBlank()) return content
            return message?.optString("reasoning_content").orEmpty()
        }
    }

    /** 直接使用本机反代出来的 Gemini 模型（免费，无需外部 Key）。 */
    private fun chatLocalGemini(config: AgentConfig, history: List<Pair<String, String>>): String {
        val base = config.localBaseUrl.trim().trimEnd('/')
        if (base.isBlank()) throw IllegalStateException("本机反代地址为空")
        val model = config.localModel.ifBlank { "gemini-3.7-flash" }
        val url = "$base/v1beta/models/$model:generateContent"

        var systemText: String? = null
        val contents = JSONArray()
        var lastRole: String? = null

        history.forEach { (role, content) ->
            when (role) {
                "system" -> systemText = content
                else -> {
                    val geminiRole = if (role == "assistant") "model" else "user"
                    if (geminiRole == lastRole && contents.length() > 0) {
                        // 合并连续同角色，避免 Gemini 拒绝
                        val last = contents.optJSONObject(contents.length() - 1)
                        val parts = last?.optJSONArray("parts")
                        parts?.put(JSONObject().put("text", "\n\n$content"))
                    } else {
                        contents.put(
                            JSONObject().put("role", geminiRole).put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", content))
                            )
                        )
                        lastRole = geminiRole
                    }
                }
            }
        }

        val payload = JSONObject().apply {
            if (systemText != null) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText)))
                )
            }
            put("contents", contents)
            put("generationConfig", JSONObject().put("temperature", 0.2))
        }

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", config.localApiKey.ifBlank { "aistudio-api" })
            .post(payload.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${body.take(300)}")
            }
            val obj = JSONObject(body)
            val parts = obj.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
            if (parts == null) throw IllegalStateException("响应里没有 candidates：${body.take(200)}")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
            return sb.toString()
        }
    }

    private data class Action(val name: String, val args: String)

    private fun parseAction(text: String): Action? {
        val nameMatch = Regex("(?im)^\\s*ACTION:\\s*([a-zA-Z_]+)").find(text) ?: return null
        val argsMatch = Regex("(?ims)^\\s*ARGS:\\s*(.*)$").find(text)
        var args = argsMatch?.groupValues?.get(1)?.trim().orEmpty()
        if (args.startsWith("```")) {
            args = args.removePrefix("```").substringBefore("```").trim()
        }
        return Action(nameMatch.groupValues[1].lowercase(), args)
    }

    companion object {
        private const val MAX_STEPS = 8

        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val SYSTEM_PROMPT = """
你是「AI Studio 反代助手」App 内置的手机排障助手，只处理在安卓 Termux 上部署 aistudio-api 反向代理服务相关的问题。

你能调用 App 提供的工具（一次只能用一个），必须严格按下面格式输出，不要输出多余内容：

THOUGHT: 一句话说明你的判断
ACTION: 工具名
ARGS: 参数（没有参数就写空）

可用工具：
1. get_status —— 查看 App/服务/环境当前状态（是否在线、端口、账号数、模型数、Termux 是否安装、自动操控权限是否开启等）
2. get_log —— 读取 Termux 最近一次命令的输出日志（可能为空）
3. run —— 在 Termux 执行一条 shell 命令，ARGS 为命令本身。命令输出会回传，稍后可用 get_log 读取
4. install —— 重新安装并启动反代服务（会重新拉取依赖与编译，耗时较长）
5. start —— 启动反代服务
6. stop —— 停止反代服务
7. set_port —— 修改监听端口，ARGS 为数字（必须 >= 1024，推荐 8080）
8. wait —— 等待一段时间再继续，ARGS 为秒数（1~120）。用于等待安装/编译完成后再查询状态
9. finish —— 结束并给用户最终答复，ARGS 为给用户看的中文说明

【从零搭建的标准流程（用户说“帮我从零搭建/我还没弄好”时照做）】
1) get_status 查看当前状态
2) 如果 Termux 未安装或无障碍未开启：直接 finish，告诉用户先去完成 App 首页的「首次准备」，不要瞎试
3) 如果服务不在线：ACTION: install
4) 然后 ACTION: wait，ARGS: 60，再用 get_status 检查；最多重复 8 次
5) 服务在线后，finish 并告诉用户下一步（去账号页导入 Cookie）
6) 若中途出现端口/梯子/空间等错误，按知识库给出对应修复建议，必要时 set_port 后重新 start

重要规则：
- 先 get_status 了解现状，别急着执行危险动作。
- 端口小于 1024 一定会报 permission denied，必须改成 8080 之类。
- 遇到 "Address already in use" 先 stop 再 start 或换端口。
- 遇到网络相关报错（Could not resolve host / Failed to fetch），提醒用户检查代理设置。
- 不要执行删除用户数据的命令，不要执行 rm -rf、格式化、修改系统设置等危险操作。
- 用户可能是完全不懂技术的小白，最终答复要用大白话、分点、给明确的操作步骤。
- 工具执行失败时不要反复重试同一条命令，换思路。

【手机端部署知识库（必须记住）】
- 运行环境：安卓 Termux。必须用 F-Droid 或 GitHub 版 Termux，Google Play 版已停更会报错。
- 标准部署步骤：pkg update -y && pkg install -y git；git clone https://github.com/DeconstructedCube/aistudio-api.git；cd aistudio-api；bash install.sh；bash start.sh。
- 服务默认监听 8080，管理后台是 http://localhost:8080；客户端 Base URL 用 http://127.0.0.1:8080（需要版本号则加 /v1beta）。
- 端口小于 1024 会报 bind: permission denied；1024~65535 才可用。
- App 通过 Termux 的 RUN_COMMAND 发送命令，前提是 Termux 里开启了 allow-external-apps=true 且已授予权限。
- 编译阶段（安装 Go/Zig 并编译 SpiderMonkey 签名引擎）通常要 5~15 分钟且几乎无输出，属于正常现象，不要误判为卡死。
- 服务常驻内存约 35~50MB，冷启动很快；不需要浏览器，也不需要远程签名节点。
- 防掉线：Termux 通知栏 Acquire Wakelock + 电池无限制白名单。
- Cookie 获取：手机装 Firefox 安卓版或狐猴浏览器（Chrome/Edge 安卓版不支持扩展），安装 Cookie-Editor，登录 aistudio.google.com 后 Export as JSON。
- 导入 Cookie：服务启动后在浏览器打开 http://localhost:8080 → 账号管理 → 导入凭据；一个 Cookie 可自动识别多个子账号（u/0、u/1...）。
- 客户端配置：酒馆选 Chat Completion + 来源 Google AI Studio；其他客户端必须选 Google Gemini 协议，绝不能选 OpenAI 格式；本项目没有 /v1/chat/completions。
- 限流：429 按模型独立冷却并自动切号，每天下午 4 点（美西午夜）重置额度。
- 代理：如果需要代理才能访问 Google，可在安装时加 --proxy http://127.0.0.1:7890。
- 修复：bash install.sh --rebuild 可重装并重编；服务异常时可先 stop 再 start。
"""
    }
}
