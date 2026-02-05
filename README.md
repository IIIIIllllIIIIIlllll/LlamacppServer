# Llama.cpp 模型管理系统

这是一个个人自用的 llama.cpp 模型管理工具，提供完整的模型加载、管理和交互功能，尽量提供不同API的兼容性。

> **注意**：尽管本项目作为 Java 应用具有跨平台的特性，并且llama.cpp也支持多平台，但开发目标是专门用于 **AI MAX+ 395** 这台机器，对于‘手动GPU分层’的功能暂不考虑开发（反正llama.cpp也会自动使用fit功能计算CPU和GPU的最佳配比，我觉得这个手动分层不是必须的）。
---
> **注意**：关于编译脚本，请注意JAVA_HOME的配置，默认使用系统环境变量中配置的值，如果你有多个不同版本的JDK，请确认脚本可以找到大于等于21的版本。如果系统环境变量中配置的值不是JDK21，请修改脚本，更改为正确的路径再进行编译，并且修改时请务必注意：Windows 使用 CRLF（\r\n）作为换行符，而 Linux 使用 LF（\n）。Java程序的编译是比较简单的，如果编译脚本存在问题，你也可以将它作为Maven项目拉进IDE操作。实在不行还以用release傻瓜包。
---
> **提醒**：目前支持英语版本，会根据浏览器的语言设置自动切换，也可以在url中通过lang参数手动指定英语（如：http:127.0.0.1:8080/?lang=en）。
---
> **提醒**：最新开源的Step-3.5-Flash，似乎无法通过Ollama兼容API调用，存在聊天模板的问题，待解决。
---
> **提醒**：我在使用RTX 4080S + GTX 1080时发现，基于CUDA的llamacpp会非常非常卡顿（如--list-devices），似乎是异构的问题，换vulkan没有这个情况，因此多GPU的兄弟请注意，如果本软件卡顿（因为要调用llamacpp），可能是CUDA版llamacpp的问题。
---

## API兼容情况（llamacpp自身支持OpenAI Compatible和Anthropic API）
| 类型 | 接口路径 | 说明 |
|------|----------|------|
| 兼容 Ollama | `/api/tags`<br>`/api/show`<br>`/api/chat`<br>`/api/embed`<br>`/api/ps` | 支持 Ollama 兼容接口，可用于模型查看、聊天、嵌入向量等操作 |
| 不兼容 Ollama | `/api/copy`<br>`/api/delete`<br>`/api/pull`<br>`/api/push`<br>`/api/generate` | 不支持 Ollama 的相关操作，如模型复制、删除、拉取、推送和生成 |
| 兼容 LM Studio | `/api/v0/models`<br>`/api/v0/chat/completions`<br>`/api/v0/completions`<br>`/api/v0/embeddings` | 支持 LM Studio 的模型查询、对话、嵌入和生成功能 |



## 主要功能

### 📦 模型管理

- **模型扫描与管理**：自动扫描指定目录下的所有 GGUF 格式模型，支持多个模型根目录
- **模型收藏与别名**：为常用模型设置收藏标记和自定义别名，方便快速识别
- **模型详情查看**：查看模型的详细信息，包括元数据、运行指标（metrics）、属性（props）和聊天模板（可编辑模板）
- **分卷模型支持**：自动识别和处理分卷模型文件（如 `*-00001-of-*.gguf`）
- **多模态模型支持**：支持带视觉组件的模型（mmproj 文件）
- **聊天模板**： 在模型的详细信息中，聊天模板默认不会自动加载，需要手动点击‘默认’按钮才会加载。如果点击加载后依然是空值，说明GGUF模型中可能不包含默认的聊天模板，需要在‘内置聊天模板’中选择适合的模板，或者自己手动设置一个模板。
<img width="2880" height="1800" alt="14f92988319d6ed4d4280bc41f350803" src="https://github.com/user-attachments/assets/b5dacf12-c0ca-4200-992a-b4f2b8eab727" />
<img width="2880" height="1800" alt="e7db8db4c33a1f27f312b83a1c0d2d62" src="https://github.com/user-attachments/assets/957128a5-79eb-4e78-8bc3-329d415ce329" />
<img width="2880" height="1800" alt="60bab939c769d2ac0988c7fbb4773d38" src="https://github.com/user-attachments/assets/49443d49-3fdb-488e-9192-ee0f7a4a5f31" />



### 🌐 模型下载
- **模型搜索**：支持从HuggingFace和hf-mirror上搜索并下载gguf模型
- **断点续传**：支持断点续传功能，网络中断后可继续下载
- **并发下载**：最多支持 4 个任务同时下载，其余任务进入等待队列
- **进度监控**：通过 WebSocket 实时推送下载进度
- **任务管理**：支持任务的暂停、恢复、删除和状态持久化
- **已知缺陷**：下载过程中，程序如果因意外停止，会导致下载进度丢失，如需重启程序请手动暂停下载任务
![屏幕截图_18-1-2026_173859_192 168 5 12](https://github.com/user-attachments/assets/06d3688d-9e33-443a-8993-7ef539b7f8fb)
![屏幕截图_18-1-2026_17395_192 168 5 12](https://github.com/user-attachments/assets/d8efe2a3-6439-4a11-9252-5ade3a48387b)

### 🖥️ Web 管理界面

- **模型列表**：直观展示所有模型，支持搜索、排序（按名称、大小、参数量）
- **加载配置**：配置模型启动参数，包括上下文大小、批处理、温度、Top-P、Top-K 等
- **对话界面**：内置聊天界面，可直接与加载的模型进行对话，用于快捷测试和验证
- **下载管理**：管理下载任务，查看进度和状态
- **控制台日志**：实时查看系统日志，支持自动刷新
- **系统设置**：配置模型目录和 llama.cpp 可执行文件路径，设置Ollama和LM Studio兼容API，配置MCP服务，进行并发测试
<img width="2880" height="1800" alt="87c9dc5e8213035e79c859f2fabfed08" src="https://github.com/user-attachments/assets/f887c78b-79d9-428c-abe9-5d053ba77a54" />
<img width="2880" height="1800" alt="3b60ca902a477b258c2b32450f4ce219" src="https://github.com/user-attachments/assets/5249ef7e-122f-4774-9146-1476f5b282c9" />
<img width="2880" height="1800" alt="bd3f923f8c557fc93f8013930be3ed71" src="https://github.com/user-attachments/assets/8de9f79c-4e38-478a-9fbd-9cd32aebb8bb" />
<img width="2880" height="1800" alt="452ea5375b3034405af95654512b98db" src="https://github.com/user-attachments/assets/cf0884fc-1456-42ac-aedf-56e2d1701542" />



### 🔌 API 兼容性

- **OpenAI API**：兼容 OpenAI API 格式（默认端口 8080），可直接接入现有应用
- **Anthropic API**：兼容 Anthropic API 格式（端口 8070）
- **Ollama API**： 兼容Ollama部分API，可以用于那些只支持Ollama的应用
- **LM Studio**：兼容LM Studio的/api/v0/** API，目前实际意义不明

### ⚡ 性能测试

- **模型基准测试**：对模型进行性能测试，评估推理速度
- **多参数配置**：支持配置重复次数、提示长度、生成长度、批量大小等测试参数
- **结果对比**：保存和对比多次测试结果，分析性能差异
- **测试结果管理**：查看、追加、删除测试结果文件

<img width="2880" height="1800" alt="259ac4d62ad19ebacd299395acccb488" src="https://github.com/user-attachments/assets/499d9d9b-d73e-4d06-b43c-92f2dc320036" />

### 📊 系统监控

- **实时状态**：通过 WebSocket 实时推送模型加载/停止事件
- **日志广播**：控制台日志实时广播到 Web 界面
<img width="2880" height="1800" alt="8a9a6ebc449d15f9daef74aa5d47ab93" src="https://github.com/user-attachments/assets/2e8984c7-f90c-4cdf-a2a0-0407b3b1013d" />


### ⚙️ 配置管理

- **启动配置保存**：为每个模型保存独立的启动参数配置
- **多版本支持**：支持配置多个 llama.cpp 版本路径，加载时选择
- **多目录支持**：支持配置多个模型目录，自动合并检索
- **配置持久化**：所有配置自动保存到本地文件

### 📱 移动端适配

<img src="https://github.com/user-attachments/assets/f848a936-1e8e-4bfb-8a47-59a2b32b856a" width="20%">
<img src="https://github.com/user-attachments/assets/82fe0c28-53f0-4fa9-812f-1ecb7c9cf3c0" width="20%">
<img src="https://github.com/user-attachments/assets/b40fdcf3-595e-47db-b2a4-7b204a84902a" width="20%">
<img src="https://github.com/user-attachments/assets/8baef036-2eb8-407e-875b-04d63c6a5ab4" width="20%">

### 🔧 其它功能

- **显存估算**：根据上下文大小、批处理等参数估算所需的显存占用（对于视觉模型不准确）
---

## 使用说明

### 手动编译

```bash
# Windows
javac-win.bat

# Linux
javac-linux.sh
```

> **注意**：关于Linux的编译脚本，请注意JAVA_HOME的配置，默认使用该路径：/opt/jdk-24.0.2/。请修改为你所使用的路径再进行编译，并且修改时请务必注意：Windows 使用 CRLF（\r\n）作为换行符，而 Linux 使用 LF（\n）。
---

### 直接下载
直接从release下载编译好的程序使用

### 启动程序
编译成功后，在build目录下找到启动脚本：run.sh或者run.bat，运行即可。
- 注意：默认会占用8080和8070端口，如果这两个端口不可以，请手动在**application.json**中修改监听的端口。
### 访问 Web 界面

启动成功后，在浏览器中访问：

- 主界面：`http://localhost:8080`
- 对话界面：`http://localhost:8080/chat/completion.html`

### 配置模型目录和 llama.cpp 路径

1. 打开 Web 界面
2. 点击左侧菜单的「系统设置」
3. 添加模型目录（可添加多个）
4. 添加 llama.cpp 可执行文件路径（可添加多个版本）

### 加载模型

1. 在模型列表中找到要加载的模型
2. 点击「加载」按钮
3. 配置启动参数（可使用已保存的配置）
4. 点击「加载模型」开始加载

### 使用 API

加载模型后，可通过以下方式调用：

- **OpenAI API**：`http://localhost:8080/v1/chat/completions`
- **Anthropic API**：`http://localhost:8070/v1/messages`
- **Completion API**：`http://localhost:8080/completion`

---

## 系统要求

- Java 21 运行环境
- 已编译的 llama.cpp 可执行文件
