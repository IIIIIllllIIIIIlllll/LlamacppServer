# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

This is a Java-based HTTP server for llama.cpp that provides OpenAI/Ollama-compatible APIs and a web management interface.

## Environment

| Component | Version |
|-----------|---------|
| **Java** | 21 (OpenJDK, system default: `/usr/lib/jvm/java-21-openjdk-amd64`) |
| **Build Tool** | javac with Netty framework |
| **Default Ports** | 8080 (OpenAI API), 8070 (Anthropic API) |
| **Systemd Service** | llama-server.service |

## Project Structure

```
src/main/java/org/mark/llamacpp/
├── server/               # Core server logic
│   ├── LlamaServer.java              # Main entry point, Netty server bootstrap
│   ├── LlamaServerManager.java       # Model lifecycle & process management
│   ├── LlamaCppProcess.java         # Wrapper for llama.cpp processes
│   ├── ConfigManager.java           # Configuration persistence
│   ├── channel/                     # Netty channel handlers
│   │   ├── BasicRouterHandler.java    # Routes requests to controllers
│   │   ├── OpenAIRouterHandler.java   # OpenAI API (/v1/*)
│   │   └── AnthropicRouterHandler.java # Anthropic API (port 8070)
│   ├── controller/                  # API endpoint handlers
│   │   ├── LlamacppController.java   # llama.cpp binary management
│   │   ├── ModelInfoController.java  # Model list/details/slots
│   │   └── ModelActionController.java # Model load/stop operations
│   ├── service/                     # Business logic
│   │   ├── OpenAIService.java        # OpenAI API request handling
│   │   ├── CompletionService.java    # Chat completion state
│   │   └── SessionService.java       # Chat session management
│   ├── tools/                       # Utilities
│   │   ├── JsonUtil.java             # JSON serialization
│   │   ├── ParamTool.java           # Parameter parsing
│   │   └── CommandLineRunner.java   # Process execution
│   └── struct/                      # Data structures
├── ollama/               # Ollama API compatibility
│   ├── Ollama.java                  # Main Ollama service
│   ├── OllamaChatService.java        # /api/chat endpoint
│   └── channel/OllamaRouterHandler.java
├── lmstudio/            # LM Studio API compatibility
│   ├── LMStudio.java               # Main LM Studio service
│   └── channel/
├── gguf/                 # GGUF model metadata reader
├── download/             # Model download from HuggingFace
└── cli/                  # Command-line interface tools
```

## Common Commands

### Build

```bash
# Linux (requires JAVA_HOME configured in javac-linux.sh)
./javac-linux.sh

# Output: build/classes/ with run.sh startup script
```

**Important**:
- `JAVA_HOME` in `javac-linux.sh` defaults to system OpenJDK 21
- Modify `JAVA_HOME` if using a custom JDK path

### Run

```bash
# Using systemd
sudo systemctl start llama-server.service
sudo systemctl stop llama-server.service
sudo systemctl restart llama-server.service

# Or directly
./build/run.sh
```

### Development

```bash
# Recompile after changes
./javac-linux.sh

# Check logs
journalctl -u llama-server -f

# View process
ps aux | grep LlamaServer
```

## Architecture Overview

### Request Routing Pipeline

```
HTTP Request
    ↓
Netty ServerBootstrap (Port 8080/8070)
    ↓
BasicRouterHandler
    ↓ (if /v1/* or /api/*)
Controller Pipeline (7 controllers)
    ↓
OpenAIRouterHandler / AnthropicRouterHandler
    ↓
Service Layer (OpenAIService / AnthropicService)
    ↓
LlamaServerManager (find model by name)
    ↓
LlamaCppProcess (forward to llama.cpp on assigned port)
```

### Model Management Architecture

**Key Classes:**

1. **LlamaServerManager**: Singleton managing all model processes
   - `loadedProcesses`: Map<String, LlamaCppProcess> - key is modelId
   - `modelPorts`: Map<String, Integer> - modelId to port mapping
   - Scans model directories for GGUF files using GGUFBundle

2. **LlamaCppProcess**: Wrapper around llama.cpp binary
   - Starts llama-server with model-specific parameters
   - Monitors stdout/stderr for "all slots are idle" to detect ready state
   - Captures process PID and port assignment

3. **Model Name Resolution** (Important):
   - `loadedProcesses` key = model folder name (e.g., "gpt-oss-120b")
   - llama.cpp returns full filename (e.g., "gpt-oss-120b-Derestricted.MXFP4_MOE.gguf")
   - **Fix**: OpenAIService.findModelIdByName() performs fuzzy matching by:
     1. Checking exact match in `loadedProcesses`
     2. If not found, queries `/v1/models` for each loaded model
     3. Matches against `model.name`, `model.model`, or `data.id` fields

### API Compatibility Layers

| API | Handler | Port | Endpoints |
|-----|----------|------|-----------|
| **OpenAI** | OpenAIRouterHandler → OpenAIService | 8080 | /v1/chat/completions, /v1/completions, /v1/models, /v1/embeddings |
| **Anthropic** | AnthropicRouterHandler → AnthropicService | 8070 | /v1/messages |
| **Ollama** | OllamaRouterHandler → OllamaChatService | 8080 | /api/tags, /api/show, /api/chat, /api/embed |
| **LM Studio** | LMStudioRouterHandler | 8080 | /api/v0/chat/completions |

### Controller Pipeline

Controllers in `BasicRouterHandler.pipeline` (executed in order):
1. HuggingFaceController
2. LlamacppController
3. ModelActionController
4. ModelInfoController
5. ModelPathController
6. ParamController
7. ToolController
8. SystemController

If no controller handles the request, it falls through to `OpenAIRouterHandler`.

## Key Design Patterns

### 1. Model Process Lifecycle

```java
// Loading (LlamaServerManager.loadModelAsyncFromCmd)
1. Assign next available port (8081+)
2. Build llama-server command with model path and parameters
3. Start LlamaCppProcess with output handler
4. Wait for "srv  update_slots: all slots are idle" in stdout
5. On success: add to loadedProcesses, save port mapping
```

### 2. Configuration Management

- **Launch Config**: `~/.cache/llama-server/launch-config.json` (per-model)
- **Model Paths**: `~/.cache/llama-server/model-path-config.json`
- **Model Aliases**: Stored in model config
- **Chat Templates**: Cached in `~/.cache/llama-server/cache-templates/`

### 3. Request Forwarding

OpenAI requests are forwarded to llama.cpp with these transformations:
- Parse request JSON
- Resolve modelName to actual modelId (with fuzzy matching)
- Get assigned port from modelPorts
- Forward via HttpURLConnection to `localhost:<port>/v1/chat/completions`
- Return response with CORS headers

## Important Implementation Details

### Model Loading Status Check (Recent Fix)

**Problem**: Frontend shows model as "loaded" even after llama-server process exits, because `ModelInfoController` only checks if modelId exists in `loadedProcesses` Map, without verifying if the process is actually running.

**Solution in ModelInfoController.handleModelDetailsRequest()** (line 566-571):
```java
// Check if model is truly loaded (process must exist AND be running)
boolean isLoaded = false;
LlamaCppProcess loadedProcess = manager.getLoadedProcesses().get(modelId);
if (loadedProcess != null && loadedProcess.isRunning()) {
    isLoaded = true;
}
```

**Key Point**: Always call `LlamaCppProcess.isRunning()` to verify process status, which checks:
- `process.isAlive()` - Process is still alive
- Internal `isRunning` flag

### Model Name Matching (Recent Fix)

**Problem**: Users call API with full filename (e.g., "gpt-oss-120b-Derestricted.MXFP4_MOE.gguf") but `loadedProcesses` uses folder name as key (e.g., "gpt-oss-120b").

**Solution in OpenAIService.handleOpenAIChatCompletionsRequest()**:
```java
String actualModelId = null;
if (manager.getLoadedProcesses().containsKey(modelName)) {
    actualModelId = modelName;
} else {
    // Fuzzy matching
    actualModelId = this.findModelIdByName(manager, modelName);
}
```

**findModelIdByName()** logic:
- Iterates through all loaded models
- Calls `handleModelInfo(modelId)` to get `/v1/models` response
- Matches user's modelName against:
  - `items[].model.model`
  - `items[].model.name`
  - `items[].data.id`

### Startup Parameters

llama-server is launched with these critical flags:
- `--alias <modelId>` - Sets model alias for API compatibility (added in upstream commit 850b46a)
- `--port <assigned_port>` - Dynamic port assignment starting from 8081
- `--no-webui` - Disable built-in web UI
- `--metrics` - Enable metrics endpoint
- `--slot-save-path` - Enable session persistence
- `--cache-ram -1` - Disable RAM cache (use full VRAM)

## Testing

### Test API Endpoints

```bash
# Check loaded models
curl http://localhost:8080/v1/models | jq '.'

# Test chat completion
curl http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model": "model-name", "messages": [{"role": "", "content": "Hello"}], "stream": false}'

# Check model slots
curl http://localhost:8080/slots | jq '.slots[0] | {n_ctx, n_ctx_train}'

# View process info
curl http://localhost:8080/api/models/details?modelId=<model-id>
```

## Troubleshooting

### Model "Not Found" Errors

1. Check `/v1/models` - returns actual model names from llama.cpp
2. Check `loadedProcesses` - compare with request model name
3. Enable debug logging in `OpenAIService.findModelIdByName()`
4. Verify `--alias` parameter is passed to llama-server

### Port Conflicts

- llama.cpp processes are assigned ports dynamically (8081+)
- Check `modelPorts` mapping in LlamaServerManager
- Use `sudo netstat -tlnp | grep java` to see Java server listening ports
- Each model gets its own llama.cpp instance on unique port

### Compilation Issues

- Ensure JAVA_HOME points to JDK 21+
- Check lib/ directory contains: gson-2.8.9.jar, netty-all-4.1.35.Final.jar, slf4j-api-1.7.30.jar, slf4j-simple-1.7.30.jar
- Verify line endings: CRLF for Windows, LF for Linux

### Process Management

```bash
# Stop all model processes (via API)
curl -X POST http://localhost:8080/api/models/stop \
  -H "Content-Type: application/json" \
  -d '{"modelId": "<model-id>"}'

# Or kill Java server (stops all models)
sudo systemctl stop llama-server.service
```

## Git Workflow

This project uses a fork-based workflow with upstream repository:

```bash
# Add upstream if not already added
git remote add upstream https://github.com/IIIIIllllIIIIIlllll/LlamacppServer.git

# Fetch upstream updates
git fetch upstream

# Check for new commits
git log HEAD..upstream/master --oneline

# Rebase local branch on upstream/master
git checkout master
git pull --rebase upstream master

# Merge upstream into feature branch
git checkout fix/model-name-mismatch-in-chat-completions
git merge master
```

**Branch Naming**: Use `fix/` or `feature/` prefix for topic branches

## Related Code

- **llama-cpp-for-strix-halo/****: AMD GPU-optimized llama.cpp fork
- **Qwen3-ASR/**, Qwen3-TTS/**: Speech models for ASR/TTS
- **sherpa-onnx/**: ONNX-based speech processing
