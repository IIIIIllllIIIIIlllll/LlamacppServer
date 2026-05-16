# API 端点文档

## 端口布局

| 端口 | 服务 | 默认 | 说明 |
|------|------|------|------|
| 8080 | 主服务：OpenAI/Anthropic API + WebUI + 内部 API + WebSocket | 开 | 主服务端口 |
| 8075 | MCP 服务器（Streamable HTTP） | 关 | MCP 工具服务 |
| 11434 | Ollama 兼容 API | 关 | Ollama 兼容层 |
| 1234 | LM Studio 兼容 API | 关 | LM Studio 兼容层 |
| 8081+ | 每个已加载的 llama-server 子进程 | 自动分配 | 模型推理进程 |

---

## 一、OpenAI 兼容 API（端口 8080）

由 `LlamaRouterHandler` → `OpenAIService` 处理。

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/v1/models` | 列出已加载模型（附带能力标志） | — | `{data: [{id, object, capabilities}]}` |
| GET | `/models` | 同上（无 /v1 前缀的别名） | — | 同上 |
| POST | `/v1/chat/completions` | 聊天补全（流式/非流式） | `{model, messages, stream, ...}` | SSE 流或完整 JSON |
| POST | `/v1/chat/completion` | 同上（别名路径） | 同上 | 同上 |
| POST | `/chat/completion` | 同上（裸路径） | 同上 | 同上 |
| POST | `/v1/completions` | 文本补全 | `{model, prompt, stream, max_tokens}` | SSE 流或完整 JSON |
| POST | `/completions` | 同上（裸路径） | 同上 | 同上 |
| POST | `/v1/embeddings` | 文本嵌入 | `{model, input, ...}` | `{data: [{embedding}]}` |
| POST | `/embeddings` | 同上（裸路径） | 同上 | 同上 |
| POST | `/v1/responses` | Responses API | `{model, input, ...}` | 响应对象或流 |
| POST | `/responses` | 同上（裸路径） | 同上 | 同上 |
| POST | `/v1/rerank` | 重排序 | `{model, query, documents}` | `{results: [{index, score}]}` |
| POST | `/v1/reranking` | 同上（别名路径） | 同上 | 同上 |
| POST | `/rerank` | 同上（裸路径） | 同上 | 同上 |
| POST | `/reranking` | 同上（裸路径别名） | 同上 | 同上 |
| POST | `/v1/audio/transcriptions` | 音频转录（multipart） | `model, file, language` | `{text}` |
| POST | `/audio/transcriptions` | 同上（裸路径） | 同上 | 同上 |

> **注意：** API 密钥验证通过 `Authorization: Bearer {key}` 或 `x-api-key: {key}`。仅当 `security.apiKeyEnabled=true` 时启用。仅适用于 `/v1/*` 路径。

---

## 二、Anthropic 兼容 API（端口 8080）

由 `LlamaRouterHandler` → `AnthropicService` 处理。客户端类型通过 `anthropic-version` 或 `x-api-key` 请求头自动检测。

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/v1/models` | 列出模型（Anthropic 格式） | — | `{data: [{type, id}]}` |
| POST | `/v1/messages` | 聊天消息（Anthropic↔OpenAI 格式转换） | `{model, messages, system, max_tokens, thinking, tools}` | `{id, type, content, usage}` |
| POST | `/v1/messages/count_tokens` | Token 计数 | `{model, messages, system}` | `{input_tokens}` |
| POST | `/v1/complete` | 遗留文本补全 | `{model, prompt, max_tokens_to_sample}` | `{completion, stop_reason}` |

---

## 三、Ollama 兼容 API（端口 11434）

由 `OllamaRouterHandler` 处理。默认关闭，通过 `application.json` → `compat.ollama.enabled` 启用。

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/` | 根路径检查 | — | `"Ollama is running"` |
| GET | `/api/tags` | 列出所有模型（Ollama 格式） | — | `{models: [{name, modified_at, size}]}` |
| GET | `/api/ps` | 列出已加载模型 | — | `{models: [{name, digest, expires_at, size}]}` |
| POST | `/api/show` | 模型详情 + 元数据 + 能力 | `{model}` | `{license, modelfile, parameters, template, details, capabilities}` |
| POST | `/api/chat` | 聊天补全（流式 + 工具，支持 nodeId） | `{model, messages, stream, options, tools}` | SSE 流或完整 JSON |
| POST | `/api/embed` | 文本嵌入 | `{model, input}` | `{embeddings, total_duration, ...}` |
| * | `/api/copy` | **不支持 — 返回 404** | — | — |
| * | `/api/delete` | **不支持 — 返回 404** | — | — |
| * | `/api/pull` | **不支持 — 返回 404** | — | — |
| * | `/api/push` | **不支持 — 返回 404** | — | — |
| * | `/api/generate` | **不支持 — 返回 404** | — | — |
| * | `/v1/*` | 同 8080 端口的 OpenAI 兼容端点 | — | — |

---

## 四、LM Studio 兼容 API（端口 1234）

由 `LMStudioRouterHandler` + `LMStudioWebServiceHandler` 处理。默认关闭。

| 方法 | 路径 | 说明 | 请求体 | 响应 |
|------|------|------|--------|------|
| GET | `/` | 根路径 | — | `"LMStudio Web Service"` |
| GET | `/health` | 健康检查 | — | `{"status":"ok","timestamp":...}` |
| ALL | `/echo` | 回声测试 | 任意 | 回显 method/path/body |
| GET | `/api/v0/models` | 列出已加载模型（量化/架构/能力） | — | `{data: [{id, object, engine, capabilities}]}` |
| GET | `/api/v0/models/{modelId}` | 单个模型详情 | — | 同上（单个） |
| POST | `/api/v0/chat/completions` | 聊天补全（增强响应含 stats/model_info/runtime） | `{model, messages, stream, ...}` | SSE 流（含 `stats`, `model_info`, `runtime`） |
| POST | `/api/v0/completions` | 文本补全 | `{model, prompt, ...}` | 同上 |
| POST | `/api/v0/embeddings` | 文本嵌入 | `{model, input}` | `{data: [{embedding}]}` |
| * | `/v1/*` | 同 8080 端口的 OpenAI 兼容端点 | — | — |

### LM Studio WebSocket RPC（路径 `/llm` 和 `/system`）

| RPC 方法 | 说明 |
|----------|------|
| `connect` | 连接确认 |
| `listDownloadedModels` | 所有 GGUF 模型（含架构/量化信息） |
| `listLoaded` | 已加载的 LLM（过滤掉嵌入模型） |
| `getModelInfo` | 通过 `instanceReference` 获取模型详情 |

---

## 五、模型管理 API

由 `ModelActionController` 处理。

### 模型列表与状态

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| GET | `/api/models/list` | 列出所有模型（本地 + 远程），可选 `?nodeId=` | Query: `nodeId` |
| GET | `/api/models/loaded` | 列出已加载模型（id/name/status/port/pid/size/path/node/busy） | — |
| GET | `/api/models/refresh` | 强制刷新本地 + 所有远程节点模型列表 | — |

### 模型加载与停止

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| POST | `/api/models/load` | 加载模型 | `{modelId, cmd, extraParams, enableVision, llamaBinPathSelect, device, mg, nodeId}` |
| POST | `/api/models/stop` | 停止模型 | `{modelId, nodeId}` |

### 基准测试

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| POST | `/api/models/benchmark` | V1 基准测试（运行 llama-bench CLI） | `{modelId, cmd, llamaBinPath, nodeId}` |
| GET | `/api/models/benchmark/list` | 列出 V1 结果文件 | Query: `modelId, nodeId` |
| GET | `/api/models/benchmark/get` | 获取 V1 结果 | Query: `fileName, nodeId` |
| POST | `/api/models/benchmark/delete` | 删除 V1 结果 | Body: `{fileName, nodeId}` |
| POST | `/api/v2/models/benchmark` | V2 基准测试（通过 BenchmarkService） | `{modelId, promptTokens, maxTokens, nodeId}` |
| GET | `/api/v2/models/benchmark/get` | 获取 V2 记录 | Query: `modelId, nodeId` |
| POST | `/api/v2/models/benchmark/delete` | 删除 V2 记录 | `{modelId, lineNumber, nodeId}` |

### 子进程代理

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| GET | `/api/models/metrics` | 代理到子进程 `/metrics` | Query: `modelId` |
| GET | `/api/models/props` | 代理到子进程 `/props` | Query: `modelId` |

---

## 六、模型信息 API

由 `ModelInfoController` 处理。

### 模型元数据

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| GET | `/api/models/openai/list` | OpenAI 格式模型列表（合并 models + data 数组） | — |
| POST | `/api/models/alias/set` | 设置模型别名 | `{modelId, alias, nodeId}` |
| POST | `/api/models/favourite` | 切换收藏 | `{modelId}` |
| GET | `/api/models/details` | 模型详情 | Query: `modelId, nodeId` |
| GET | `/api/models/record` | 推理性能记录 | Query: `modelId` |

### 启动配置

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| GET | `/api/models/config/get` | 获取启动配置包 | Query: `modelId, nodeId` |
| POST | `/api/models/config/set` | 保存启动配置 | `{modelId, configName, setSelected, config:{...}, nodeId}` |
| POST | `/api/models/config/delete` | 删除启动配置 | `{modelId, configName, nodeId}` |

### 能力

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| GET | `/api/models/capabilities/get` | 获取模型能力 | Query: `modelId, nodeId` |
| POST | `/api/models/capabilities/set` | 设置模型能力 | `{modelId, capabilities:{tools,thinking,...}, nodeId}` |

### 聊天模板

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| GET | `/api/model/template/get` | 获取自定义聊天模板 | Query: `modelId, nodeId` |
| POST | `/api/model/template/set` | 设置自定义模板 | `{modelId, chatTemplate, nodeId}` |
| POST | `/api/model/template/delete` | 删除自定义模板 | `{modelId, nodeId}` |
| GET | `/api/model/template/default` | 从 GGUF 读取默认模板 | Query: `modelId, nodeId` |

### 聊天模板 Kwargs

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| GET | `/api/model/chat_template_kwargs/get` | 获取 kwargs | Query: `modelId, nodeId` |
| POST | `/api/model/chat_template_kwargs/set` | 设置 kwargs | `{modelId, chat_template_kwargs:{...}, nodeId}` |
| POST | `/api/model/chat_template_kwargs/delete` | 删除 kwargs | `{modelId, nodeId}` |

### KV 缓存槽

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| GET | `/api/models/slots/get` | 获取槽信息 | Query: `modelId, nodeId` |
| POST | `/api/models/slots/save` | 保存 KV 缓存槽 | `{modelId, slotId, fileName, nodeId}` |
| POST | `/api/models/slots/load` | 加载 KV 缓存槽 | `{modelId, slotId, fileName, nodeId}` |

---

## 七、模型路径管理

由 `ModelPathController` 处理。

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| GET | `/api/model/path/list` | 列出模型搜索目录 | — |
| POST | `/api/model/path/add` | 添加搜索目录。验证存在性，无符号链接 | `{path, name, description}` |
| POST | `/api/model/path/update` | 更新目录 | `{originalPath, path, name, description}` |
| POST | `/api/model/path/remove` | 移除目录 | `{path}` |

---

## 八、llama.cpp 路径管理

由 `LlamacppController` 处理。

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| GET | `/api/llamacpp/list` | 列出所有 llama.cpp 目录（已保存 + 自动扫描） | — |
| POST | `/api/llamacpp/add` | 添加目录（验证包含 llama-server） | `{path, name, description}` |
| POST | `/api/llamacpp/remove` | 移除目录 | `{path}` |
| POST | `/api/llamacpp/test` | 测试目录（运行 `llama-cli --version` + `--list-devices`） | `{path}` |
| GET | `/api/llamacpp/release/latest` | 获取最新 GitHub Release（过滤相关后端资产） | Query: `proxy` |

---

## 九、代理端点

由 `LlamacppController` 处理。将请求转发到已加载模型的子进程。

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| POST | `/tokenize` | 代理到子进程 `/tokenize` | `{content, add_special, parse_special, with_pieces, modelId}` |
| POST | `/apply-template` | 代理到子进程 `/apply-template`，返回 `{prompt}` | `{messages, modelId}` |
| POST | `/infill` | 代理到子进程 `/infill`（透明转发请求头） | 透明转发 |

---

## 十、HuggingFace 搜索

由 `HuggingFaceController` 处理。支持 `hf-mirror.com` 和官方 HuggingFace 镜像切换。

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| GET | `/api/hf/search` | 搜索 HF 模型 | Query: `query`（必填）, `limit`（默认30）, `timeoutSeconds`（20）, `startPage`, `maxPages`, `base`（镜像） |
| GET | `/api/hf/gguf` | 获取 GGUF 文件信息 | Query: `model`/`repoId`/`input`（必填）, `timeoutSeconds`, `base` |

---

## 十一、下载管理

由 `FileDownloadRouterHandler` 处理。使用 `HttpURLConnection` 分块下载。

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| GET | `/api/downloads/list` | 列出所有下载任务 | — |
| POST | `/api/downloads/create` | 创建下载任务 | `{url, fileName, folderName, path, nodeId}` |
| POST | `/api/downloads/model/create` | 创建模型下载 | `{author, modelId, downloadUrl[], path, name}` |
| POST | `/api/downloads/pause` | 暂停下载任务 | `{taskId, nodeId}` |
| POST | `/api/downloads/resume` | 恢复下载任务 | `{taskId, nodeId}` |
| POST | `/api/downloads/delete` | 删除下载任务 | `{taskId, deleteFile, nodeId}` |
| GET | `/api/downloads/stats` | 下载统计（活跃/待处理/完成/失败/总计） | — |
| GET | `/api/downloads/path/get` | 获取下载目录 | — |
| POST | `/api/downloads/path/set` | 设置下载目录 | `{path}` |

---

## 十二、EasyRP 角色管理

由 `CompletionRouterHandler` 处理。角色数据存储在 `cache/charactors/`。

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| GET | `/api/chat/completion/list` | 列出所有角色（精简，按 updatedAt 降序） | — |
| POST | `/api/chat/completion/create` | 创建角色 | `{title}` |
| GET | `/api/chat/completion/get` | 获取角色 | Query: `name`（数字 ID） |
| POST | `/api/chat/completion/save` | 保存角色 | Query: `name`，Body: 完整 `CharactorDataStruct` JSON |
| DELETE | `/api/chat/completion/delete` | 删除角色 | Query: `name` |
| POST | `/api/chat/completion/file/upload` | 上传聊天文件（multipart，最大 16MB） | Query: `name` |
| GET | `/api/chat/completion/file/download` | 下载聊天文件 | Query: `name` |
| POST | `/api/chat/completion/avatar/upload` | 上传头像（multipart，最大 1MB） | Query: `name` |
| GET | `/api/chat/completion/avatar/get` | 获取头像（内联） | Query: `name` |

---

## 十三、Easy-Chat 状态同步

由 `EasyChatController` 处理。乐观并发控制的状态同步系统。

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| GET | `/api/easy-chat/state` | 加载聊天状态（修订版、对话、文件路径） | — |
| GET | `/api/easy-chat/state/revision` | 获取当前修订版字符串 | — |
| GET | `/api/easy-chat/conversation` | 加载对话 | Query: `id` |
| POST | `/api/easy-chat/sync` | 同步状态（乐观并发控制） | `{state, currentConversation, baseRevision}` |

---

## 十四、远程节点管理

由 `NodeController` 处理。仅主节点可操作。

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| GET | `/api/node/list` | 列出所有远程节点 | — |
| POST | `/api/node/add` | 添加节点（仅主节点） | `{nodeId, name, baseUrl, apiKey, tags, enabled}` |
| POST | `/api/node/remove` | 移除节点（仅主节点） | `{nodeId}` |
| POST | `/api/node/update` | 更新节点（仅主节点） | `{nodeId, name, baseUrl, apiKey, tags, enabled}` |
| POST | `/api/node/test` | 测试连通性（仅主节点） | `{nodeId}`。响应含延迟 |
| GET | `/api/node/status` | 所有节点状态（status/lastHeartbeat/enabled） | — |
| GET | `/api/node/info` | Hub 节点信息（本地 + 已连接节点） | — |

---

## 十五、参数列表

由 `ParamController` 处理。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/models/param/server/list` | 服务器参数定义（从 `server-params.json` classpath 资源加载） |
| GET | `/api/models/param/benchmark/list` | 基准测试参数定义（从 `benchmark-params.json` classpath 资源加载） |

---

## 十六、MCP 工具管理

由 `ToolController` 处理。

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| GET | `/api/mcp/tools` | 列出所有已注册的 MCP 服务器和工具 | — |
| POST | `/api/mcp/add` | 添加 MCP 服务器 | `{mcpServers:{...}}` |
| POST | `/api/mcp/remove` | 移除 MCP 服务器 | `{url}` 或 `{mcpServerUrl}` |
| POST | `/api/mcp/rename` | 重命名 MCP 服务器 | `{url/mcpServerUrl, name}` |
| POST | `/api/tools/execute` | 执行工具调用 | `{tool_name, arguments, mcpServerUrl}` |

---

## 十七、系统管理

由 `SystemController` 处理。

### 系统设置

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| GET | `/api/sys/setting` | 获取所有系统设置 | — |
| POST | `/api/sys/setting` | 保存系统设置 | 完整设置 JSON |
| GET | `/api/sys/version` | 构建版本信息（tag/version/createdTime） | — |
| GET | `/api/sys/compat/status` | 兼容服务状态（Ollama/LMStudio/MCP/日志） | — |
| GET | `/api/sys/console` | 控制台日志缓冲区文本 | Query: `nodeId` |
| GET | `/api/sys/fs/list` | 文件系统浏览器。最多 500 目录 + 10 文件。阻止符号链接 | Query: `path` |
| POST | `/api/shutdown` | 优雅关闭（停止所有模型 → NodeManager.shutdown() → System.exit(0)） | — |

### 兼容服务开关

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| POST | `/api/sys/ollama` | 启用/禁用 Ollama | `{enable, port}` |
| POST | `/api/sys/lmstudio` | 启用/禁用 LM Studio | `{enable, port}` |
| POST | `/api/sys/mcp` | 启用/禁用 MCP 服务器 | `{enable}` |

### GPU 信息

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| GET | `/api/sys/gpu/info` | GPU 初始化快照 | — |
| GET | `/api/sys/gpu/status` | 实时 GPU 状态（温度/利用率/内存/功耗/风扇） | Query: `nodeId` |

### 更新管理

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| GET | `/api/sys/update/check` | 检查 GitHub 更新 | — |
| POST | `/api/sys/update/download` | 下载更新包（仅 GitHub Release URL） | `{tagName, proxyUrl?}` |
| POST | `/api/sys/update/apply` | 应用下载的更新 | — |
| GET | `/api/sys/update/status` | 查询更新状态 | — |
| POST | `/api/sys/update/cancel` | 取消下载 | — |

### 采样预设

| 方法 | 路径 | 说明 | 参数 |
|------|------|------|------|
| GET | `/api/sys/model/sampling/setting/list` | 列出所有采样预设 | Query: `nodeId` |
| GET | `/api/sys/model/sampling/setting/get` | 获取模型的采样绑定 | Query: `modelId, nodeId` |
| POST | `/api/sys/model/sampling/setting/set` | 绑定采样预设到模型 | `{modelId, configName/samplingConfigName, nodeId}` |
| POST | `/api/sys/model/sampling/setting/add` | 添加/更新采样预设 | `{configName, temperature, top_p, ...}` |
| POST | `/api/sys/model/sampling/setting/delete` | 删除采样预设 | `{configName, nodeId}` |

### 其他

| 方法 | 路径 | 说明 | 请求体 |
|------|------|------|--------|
| POST | `/api/search/setting` | 保存智谱搜索 API 密钥 | `{zhipu_search_apikey}` 或 `{apiKey}` |
| GET | `/api/sys/fastfetch` | 获取硬件信息 | Query: `nodeId` |
| GET | `/api/model/device/list` | 列出 GPU 设备 | Query: `llamaBinPath, nodeId` |
| POST | `/api/models/vram/estimate` | VRAM 估算 | `{modelId, cmd, device, mg, llamaBinPath, ...}` |

---

## 十八、用量报告

由 `UsageReportController` 处理。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/report/token-summary` | 按模型的 Token 用量摘要 |
| GET | `/api/report/request-logs` | 逐请求推理日志 |

---

## 十九、MCP 服务器（端口 8075）

由 `McpRouterHandler` → `NettySseMcpServer` 处理。内置 MCP 服务器，JSON-RPC 2.0 协议。

### 路由

所有路径在 `/mcp/{serviceKey}` 下。支持 4 个 serviceKey：

| Service Key | 工具 |
|-------------|------|
| `llama_hub_info` | `GetModelsTool`, `GetModelPathTool`, `GetLlamaCppInfoTool`, `GetParamInfoTool`, `GetMcpServiceInfoTool`, `ExperienceLogTool`, `ExperienceListTool`, `ExperienceGetTool`, `ExperienceMatchTool`, `GetTimeTool` |
| `llama_hub_image` | `ReadStaticImageTool`（最大 2MB，base64） |
| `llama_hub_context` | `ContextSummaryTool`（持久化到 `cache/context-summary/`） |
| `llama_hub_file` | `WriteTextFileTool`（路径遍历保护） |

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/mcp/{serviceKey}` | Streamable HTTP SSE 连接 |
| POST | `/mcp/{serviceKey}` | Streamable HTTP JSON-RPC 请求 |
| DELETE | `/mcp/{serviceKey}` | Streamable HTTP 会话删除 |
| GET | `/mcp/{serviceKey}/sse` | 遗留 SSE 连接 |
| POST | `/mcp/{serviceKey}/message` | 遗留 SSE 消息 |

### 支持的 JSON-RPC 方法

`initialize`、`ping`、`notifications/ping`、`notifications/initialized`、`prompts/list`、`resources/list`、`resources/templates/list`、`tools/list`、`tools/call`

---

## 二十、WebSocket（端口 8080，路径 `/ws`）

### 事件（服务器 → 客户端）

| 事件 | 说明 | 负载字段 |
|------|------|----------|
| `connect_ack` | 连接确认 | — |
| `modelLoadStart` | 模型加载开始 | `modelId, port, message` |
| `modelLoad` | 模型加载成功/失败 | `modelId, success, message, port` |
| `modelStop` | 模型停止 | `modelId, success, message` |
| `model_status` | 模型状态更新 | — |
| `model_slots` | 槽状态 | `modelId, slots: [{id, speculative, is_processing}]` |
| `model_busy` | 模型繁忙状态 | `modelId, busy, activeCount` |
| `console` | 控制台日志（base64） | `modelId, line64, nodeId?, timestamp` |
| `notification` | 通用通知 | — |
| `download_update` | 下载状态变更 | `taskId, state, ...` |
| `download_progress` | 下载进度 | `taskId, bytes, speed, ...` |
| `app_update` | 应用更新进度 | `status, downloadedBytes, totalBytes, progressRatio, version, errorMessage` |
| `systemMonitor` | 系统指标（仅 Linux） | `cpu, memory, gpu, load, processes, network` |

心跳：服务器每 30 秒 ping，每 60 秒广播系统状态。

---

## 统计

| 类别 | 数量 |
|------|------|
| OpenAI 兼容端点 | 16 |
| Anthropic 兼容端点 | 4 |
| Ollama 兼容端点 | 8（含 5 个 404） |
| LM Studio 端点 | 7 + 4 RPC |
| 模型管理 | 14 |
| 模型信息 | 21 |
| 模型路径 | 4 |
| llama.cpp 路径 | 5 |
| 代理端点 | 3 |
| HuggingFace 搜索 | 2 |
| 下载管理 | 9 |
| EasyRP 角色 | 9 |
| Easy-Chat 状态同步 | 4 |
| 节点管理 | 7 |
| 参数列表 | 2 |
| MCP 工具管理 | 5 |
| 系统管理 | 21 |
| 用量报告 | 2 |
| MCP 服务器 | 5 路由 × 4 serviceKey |
| WebSocket 事件 | 12 |
| **总计** | **约 125 个独立端点** |
