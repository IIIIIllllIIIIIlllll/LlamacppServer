package org.mark.llamacpp.lmstudio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.ModelSamplingService;
import org.mark.llamacpp.server.service.OpenAIService;
			import org.mark.llamacpp.server.service.LlamaRecordService;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;



/**
 * 	LM Studio的兼容API实现。
 */
public class LMStudioService {

	/**
	 * 	
	 */
	private static final Logger logger = LoggerFactory.getLogger(OpenAIService.class);
	
	/**
	 * 	线程池。
	 */
	private static final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
	
	/**
	 * 	
	 */
	private final Map<ChannelHandlerContext, HttpURLConnection> channelConnectionMap = new HashMap<>();

	private static final int LLAMA_CONNECT_TIMEOUT_MS = 36000 * 1000;
	private static final int LLAMA_READ_TIMEOUT_MS = 36000 * 1000;
	
	/**
	 * 	响应：/api/v0/models
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	public void handleModelList(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.handleModelList(ctx, request, null);
	}
	
	public void handleModelList(ChannelHandlerContext ctx, FullHttpRequest request, String modelIdFilter) throws RequestMethodException {
		try {
			// 只支持POST请求
			if (request.method() != HttpMethod.GET) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			String trimmedModelIdFilter = null;
			if (modelIdFilter != null) {
				String trimmed = modelIdFilter.trim();
				if (!trimmed.isEmpty()) {
					trimmedModelIdFilter = trimmed;
				}
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			Map<String, LlamaCppProcess> loadedProcesses = manager.getLoadedProcesses();
			List<GGUFModel> allModels = manager.listModel();
			List<Map<String, Object>> data = new ArrayList<>();

			for (Map.Entry<String, LlamaCppProcess> entry : loadedProcesses.entrySet()) {
				String modelId = entry.getKey();
				if (trimmedModelIdFilter != null && !trimmedModelIdFilter.equals(modelId)) {
					continue;
				}
				GGUFModel modelInfo = findModelInfo(allModels, modelId);
				Map<String, Object> modelData = new HashMap<>();
				modelData.put("id", modelId);
				modelData.put("object", "model");

				String modelType = "llm";
				String architecture = null;
				Integer contextLength = null;
				String quantization = null;
				JsonObject modelCaps = manager.getModelCapabilities(modelId);
				boolean multimodal = false;

				if (modelInfo != null) {
					GGUFMetaData primaryModel = modelInfo.getPrimaryModel();
					if (primaryModel != null) {
						//architecture = primaryModel.getStringValue("general.architecture");
						//contextLength = primaryModel.getIntValue(architecture + ".context_length");
						architecture = primaryModel.getArchitecture();
						contextLength = primaryModel.getContextLength();
						quantization = primaryModel.getQuantizationType();
					}
					multimodal = modelInfo.getMmproj() != null;
				}
				modelType = resolveModelType(modelCaps, multimodal);
				
				// 模型类型
				modelData.put("type", modelType);
				if (architecture != null) {
					modelData.put("arch", architecture);
				}
				// 这个固定写这玩意
				modelData.put("publisher", "GGUF");
				modelData.put("compatibility_type", "gguf");
				// 量化等级
				if (quantization != null) {
					modelData.put("quantization", quantization);
				}
				// 状态
				modelData.put("state", "loaded");
				// 最大上下文长度
				if (contextLength != null) {
					modelData.put("max_context_length", contextLength);
				}
				// 加载后上下文长度
				modelData.put("loaded_context_length", entry.getValue().getCtxSize());
				
				// 能力
				List<String> capabilities = new ArrayList<>(4);
				if (ParamTool.parseJsonBoolean(modelCaps, "tools", false)) {
					capabilities.add("tool_use");
				}
				
				modelData.put("capabilities", capabilities);
				data.add(modelData);
			}

			Map<String, Object> response = new HashMap<>();
			response.put("data", data);
			response.put("object", "list");
			this.sendOpenAIJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("获取模型列表时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAIChatCompletionsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 只支持POST请求
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			// 读取请求体
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "messages");
				return;
			}

			// 解析JSON请求体
			JsonObject requestJson = JsonUtil.fromJson(content, JsonObject.class);
			
			// 获取模型名称
			if (!requestJson.has("model")) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Missing required parameter: model", "model");
				return;
			}
			
			String modelName = requestJson.get("model").getAsString();
			
			// 检查是否为流式请求
			boolean isStream = false;
			if (requestJson.has("stream")) {
				isStream = requestJson.get("stream").getAsBoolean();
			}
			
			// 这里做采样代理，针对llamacpp中的请求，注入采样参数。
			ModelSamplingService service = ModelSamplingService.getInstance();
			service.handleOpenAI(requestJson);
			
			// 获取LlamaServerManager实例
			LlamaServerManager manager = LlamaServerManager.getInstance();
			
			// 检查模型是否已加载
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Model not found: " + modelName, "model");
				return;
			}

			String body = JsonUtil.toJson(requestJson);
			
			// 获取模型端口
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + modelName, null);
				return;
			}
			// 转发请求到对应的llama.cpp进程
			this.forwardRequestChatCompletionToLlamaCpp(ctx, request, modelName, modelPort, isStream, body);
		} catch (Exception e) {
			logger.info("处理OpenAI聊天补全请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	public void handleOpenAICompletionsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "prompt");
				return;
			}
			
			JsonObject requestJson = JsonUtil.fromJson(content, JsonObject.class);
			if (requestJson == null || !requestJson.has("model")) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Missing required parameter: model", "model");
				return;
			}
			if (!requestJson.has("prompt")) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Missing required parameter: prompt", "prompt");
				return;
			}
			
			String modelName = requestJson.get("model").getAsString();
			boolean isStream = false;
			if (requestJson.has("stream")) {
				isStream = requestJson.get("stream").getAsBoolean();
			}
			
			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Model not found: " + modelName, "model");
				return;
			}
			
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + modelName, null);
				return;
			}
			
			this.forwardRequestTextCompletionToLlamaCpp(ctx, request, modelName, modelPort.intValue(), isStream, content);
		} catch (Exception e) {
			logger.info("处理OpenAI文本补全请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}

	public void handleOpenAIEmbeddingsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}

			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "input");
				return;
			}

			JsonObject requestJson = JsonUtil.fromJson(content, JsonObject.class);
			if (requestJson == null || !requestJson.has("model")) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Missing required parameter: model", "model");
				return;
			}

			String requestedModelName = requestJson.get("model").getAsString();
			String loadedModelName = requestedModelName;
			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (!manager.getLoadedProcesses().containsKey(loadedModelName)) {
				String mapped = this.tryMapToLoadedModelId(manager, loadedModelName);
				if (mapped != null && manager.getLoadedProcesses().containsKey(mapped)) {
					loadedModelName = mapped;
				}
			}

			if (!manager.getLoadedProcesses().containsKey(loadedModelName)) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Model not found: " + requestedModelName, "model");
				return;
			}

			Integer modelPort = manager.getModelPort(loadedModelName);
			if (modelPort == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + requestedModelName, null);
				return;
			}

			this.forwardRequestEmbeddingsToLlamaCpp(ctx, request, requestedModelName, loadedModelName, modelPort.intValue(), content);
		} catch (Exception e) {
			logger.info("处理OpenAI嵌入请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	private String tryMapToLoadedModelId(LlamaServerManager manager, String modelName) {
		if (manager == null || modelName == null || modelName.isBlank()) {
			return null;
		}
		String trimmed = modelName.trim();
		List<GGUFModel> models = manager.listModel();
		String base = trimmed;
		int at = base.indexOf('@');
		if (at > 0) {
			base = base.substring(0, at);
		}
		for (GGUFModel m : models) {
			if (m == null) continue;
			String alias = m.getAlias();
			if (alias != null && (alias.equals(trimmed) || alias.equals(base))) {
				return m.getModelId();
			}
		}
		return null;
	}

	private static Map<String, String> copyHeaders(FullHttpRequest request) {
		Map<String, String> headers = new HashMap<>();
		if (request == null) {
			return headers;
		}
		for (Map.Entry<String, String> entry : request.headers()) {
			headers.put(entry.getKey(), entry.getValue());
		}
		return headers;
	}

	private HttpURLConnection openAndTrack(ChannelHandlerContext ctx, String targetUrl) throws IOException {
		URL url = URI.create(targetUrl).toURL();
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		synchronized (this.channelConnectionMap) {
			this.channelConnectionMap.put(ctx, connection);
		}
		return connection;
	}

	private void configureAndSend(HttpURLConnection connection, HttpMethod method, Map<String, String> headers, String requestBody) throws IOException {
		connection.setRequestMethod(method.name());
		for (Map.Entry<String, String> entry : headers.entrySet()) {
			String key = entry.getKey();
			if (!key.equalsIgnoreCase("Connection") &&
				!key.equalsIgnoreCase("Content-Length") &&
				!key.equalsIgnoreCase("Transfer-Encoding")) {
				connection.setRequestProperty(key, entry.getValue());
			}
		}

		connection.setConnectTimeout(LLAMA_CONNECT_TIMEOUT_MS);
		connection.setReadTimeout(LLAMA_READ_TIMEOUT_MS);

		if (method == HttpMethod.POST && requestBody != null && !requestBody.isEmpty()) {
			connection.setDoOutput(true);
			try (OutputStream os = connection.getOutputStream()) {
				byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
				os.write(input, 0, input.length);
				logger.info("已发送请求体到llama.cpp进程，大小: {} 字节", input.length);
			}
		}
	}
	
	private void forwardRequestEmbeddingsToLlamaCpp(
			ChannelHandlerContext ctx,
			FullHttpRequest request,
			String requestedModelName,
			String loadedModelName,
			int port,
			String requestBody) {
		HttpMethod method = request.method();
		Map<String, String> headers = copyHeaders(request);

		int requestBodyLength = requestBody == null ? 0 : requestBody.length();
		logger.info("转发请求到llama.cpp进程: {} 端口: {} 请求体长度: {}", method.name(), port, requestBodyLength);

		worker.execute(() -> {
			HttpURLConnection connection = null;
			try {
				String targetUrl = String.format("http://localhost:%d/v1/embeddings", port);
				logger.info("连接到llama.cpp进程: {}", targetUrl);

				connection = openAndTrack(ctx, targetUrl);
				configureAndSend(connection, method, headers, requestBody);
				long t = System.currentTimeMillis();
				int responseCode = connection.getResponseCode();
				logger.info("llama.cpp进程响应码: {}，等待时间：{}", responseCode, System.currentTimeMillis() - t);
				this.handleEmbeddingsNonStreamResponse(ctx, connection, responseCode, requestedModelName, loadedModelName);
			} catch (Exception e) {
				logger.info("转发嵌入请求到llama.cpp进程时发生错误", e);
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
			} finally {
				if (connection != null) {
					connection.disconnect();
				}
				synchronized (this.channelConnectionMap) {
					this.channelConnectionMap.remove(ctx);
				}
			}
		});
	}
	
	private void handleEmbeddingsNonStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String requestedModelName, String loadedModelName) throws IOException {
		String responseBody = "";
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
			responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream(),
			StandardCharsets.UTF_8
		))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
			responseBody = sb.toString();
		}

		if (!(responseCode >= 200 && responseCode < 300)) {
			LlamaServer.sendExpressRawJsonResponse(ctx, HttpResponseStatus.valueOf(responseCode), responseBody.getBytes(StandardCharsets.UTF_8), false);
			return;
		}

		JsonObject llama = null;
		try {
			llama = JsonUtil.fromJson(responseBody, JsonObject.class);
		} catch (Exception e) {
			logger.info("解析llama.cpp embeddings JSON失败", e);
		}

		if (llama == null) {
			LlamaServer.sendExpressRawJsonResponse(ctx, HttpResponseStatus.OK, responseBody.getBytes(StandardCharsets.UTF_8), false);
			return;
		}

		JsonObject resp = new JsonObject();
		resp.addProperty("object", safeString(llama, "object") == null ? "list" : safeString(llama, "object"));
		if (llama.has("data") && llama.get("data").isJsonArray()) {
			resp.add("data", llama.getAsJsonArray("data"));
		} else {
			resp.add("data", new JsonArray());
		}
		resp.addProperty("model", toLmStudioEmbeddingModelName(requestedModelName, loadedModelName));

		JsonObject usage = new JsonObject();
		JsonObject llamaUsage = llama.has("usage") && llama.get("usage").isJsonObject() ? llama.getAsJsonObject("usage") : null;
		int promptTokens = llamaUsage == null ? 0 : (safeInt(llamaUsage, "prompt_tokens") == null ? 0 : safeInt(llamaUsage, "prompt_tokens").intValue());
		int totalTokens = llamaUsage == null ? 0 : (safeInt(llamaUsage, "total_tokens") == null ? 0 : safeInt(llamaUsage, "total_tokens").intValue());
		usage.addProperty("prompt_tokens", promptTokens);
		usage.addProperty("total_tokens", totalTokens);
		resp.add("usage", usage);

		this.sendOpenAIJsonResponseWithCleanup(ctx, resp, HttpResponseStatus.OK);
	}
	
	private static String toLmStudioEmbeddingModelName(String requestedModelName, String loadedModelName) {
		if (requestedModelName == null) {
			return "";
		}
		String n = requestedModelName.trim();
		if (n.isEmpty() || n.contains("@")) {
			return n;
		}
		JsonObject info = buildModelInfo(loadedModelName == null ? n : loadedModelName.trim());
		String quant = info == null ? null : safeString(info, "quant");
		if (quant == null || quant.isBlank()) {
			return n;
		}
		return n + "@" + quant.trim().toLowerCase(Locale.ROOT);
	}
	
	/**
	 * 转发请求到对应的llama.cpp进程
	 */
	private void forwardRequestChatCompletionToLlamaCpp(
			ChannelHandlerContext ctx, 
			FullHttpRequest request, 
			String modelName, int port, 
			boolean isStream, String 
			requestBody) {
		// 在异步执行前先读取请求体，避免ByteBuf引用计数问题
		HttpMethod method = request.method();
		// 复制请求头，避免在异步任务中访问已释放的请求对象
		Map<String, String> headers = copyHeaders(request);

		int requestBodyLength = requestBody == null ? 0 : requestBody.length();
		logger.info("转发请求到llama.cpp进程: {} 端口: {} 请求体长度: {}", method.name(), port, requestBodyLength);
		
		worker.execute(() -> {
			// 添加断开连接的事件监听
			HttpURLConnection connection = null;
			try {
				// 构建目标URL
				String targetUrl = String.format("http://localhost:%d/v1/chat/completions", port);
				logger.info("连接到llama.cpp进程: {}", targetUrl);
				connection = openAndTrack(ctx, targetUrl);
				configureAndSend(connection, method, headers, requestBody);
				
				// 获取响应码
				long t = System.currentTimeMillis();
				int responseCode = connection.getResponseCode();
				logger.info("llama.cpp进程响应码: {}，等待时间：{}", responseCode, System.currentTimeMillis() - t);
				
				if (isStream) {
					// 处理流式响应
					this.handleStreamResponse(ctx, connection, responseCode, modelName);
				} else {
					// 处理非流式响应
					this.handleNonStreamResponse(ctx, connection, responseCode, modelName);
				}
			} catch (Exception e) {
				logger.info("转发请求到llama.cpp进程时发生错误", e);
				// 检查是否是客户端断开连接导致的异常
				if (e.getMessage() != null && e.getMessage().contains("Connection reset by peer")) {
					
				}
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
			} finally {
				// 关闭连接
				if (connection != null) {
					connection.disconnect();
				}
				// 清理 
				synchronized (this.channelConnectionMap) {
					this.channelConnectionMap.remove(ctx);
				}
			}
		});
	}
	
	private void forwardRequestTextCompletionToLlamaCpp(
			ChannelHandlerContext ctx,
			FullHttpRequest request,
			String modelName, int port,
			boolean isStream, String requestBody) {
		HttpMethod method = request.method();
		Map<String, String> headers = copyHeaders(request);
		
		int requestBodyLength = requestBody == null ? 0 : requestBody.length();
		logger.info("转发请求到llama.cpp进程: {} 端口: {} 请求体长度: {}", method.name(), port, requestBodyLength);
		
		worker.execute(() -> {
			HttpURLConnection connection = null;
			try {
				String targetUrl = String.format("http://localhost:%d/v1/completions", port);
				logger.info("连接到llama.cpp进程: {}", targetUrl);
				connection = openAndTrack(ctx, targetUrl);
				configureAndSend(connection, method, headers, requestBody);
				long t = System.currentTimeMillis();
				int responseCode = connection.getResponseCode();
				logger.info("llama.cpp进程响应码: {}，等待时间：{}", responseCode, System.currentTimeMillis() - t);
				
				if (isStream) {
					this.handleTextCompletionStreamResponse(ctx, connection, responseCode, modelName);
				} else {
					this.handleTextCompletionNonStreamResponse(ctx, connection, responseCode, modelName);
				}
			} catch (Exception e) {
				logger.info("转发文本补全请求到llama.cpp进程时发生错误", e);
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
			} finally {
				if (connection != null) {
					connection.disconnect();
				}
				synchronized (this.channelConnectionMap) {
					this.channelConnectionMap.remove(ctx);
				}
			}
		});
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param connection
	 * @param responseCode
	 * @param modelName
	 */
	private void handleNonStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName) throws IOException {
		String responseBody = "";
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
			responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream(),
			StandardCharsets.UTF_8
		))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
			responseBody = sb.toString();
			LlamaRecordService.getInstance().handleStream(modelName, responseBody);
		} catch (IOException e) {
			//logger.info("读取llama.cpp非流式响应失败", e);
			//this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
			throw e;
		}

		if (!(responseCode >= 200 && responseCode < 300)) {
			LlamaServer.sendExpressRawJsonResponse(ctx, HttpResponseStatus.valueOf(responseCode), responseBody.getBytes(StandardCharsets.UTF_8), false);
			return;
		}

		JsonObject llama = null;
		try {
			llama = JsonUtil.fromJson(responseBody, JsonObject.class);
		} catch (Exception e) {
			logger.info("解析llama.cpp非流式JSON失败", e);
		}

		if (llama == null) {
			LlamaServer.sendExpressRawJsonResponse(ctx, HttpResponseStatus.OK, responseBody.getBytes(StandardCharsets.UTF_8), false);
			return;
		}

		JsonUtil.ensureToolCallIds(llama, new HashMap<>());

		String completionId = safeString(llama, "id");
		Long created = safeLong(llama, "created");
		JsonObject timings = llama.has("timings") && llama.get("timings").isJsonObject() ? llama.getAsJsonObject("timings") : null;

		String content = "";
		String finishReason = null;
		JsonArray choices = llama.has("choices") && llama.get("choices").isJsonArray() ? llama.getAsJsonArray("choices") : null;
		if (choices != null && choices.size() > 0 && choices.get(0).isJsonObject()) {
			JsonObject c0 = choices.get(0).getAsJsonObject();
			finishReason = safeString(c0, "finish_reason");
			JsonObject msg = c0.has("message") && c0.get("message").isJsonObject() ? c0.getAsJsonObject("message") : null;
			if (msg != null) {
				String s = safeString(msg, "content");
				if (s != null) {
					content = s;
				}
			}
		}

		JsonObject completion = buildLmStudioCompletion(modelName, completionId, created, content, timings, finishReason);
		this.sendOpenAIJsonResponseWithCleanup(ctx, completion, HttpResponseStatus.OK);
	}
	
	private void handleTextCompletionNonStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName) throws IOException {
		String responseBody = "";
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
			responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream(),
			StandardCharsets.UTF_8
		))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
			responseBody = sb.toString();
		} catch (IOException e) {
			throw e;
		}
		
		if (!(responseCode >= 200 && responseCode < 300)) {
			LlamaServer.sendExpressRawJsonResponse(ctx, HttpResponseStatus.valueOf(responseCode), responseBody.getBytes(StandardCharsets.UTF_8), false);
			return;
		}
		
		JsonObject llama = null;
		try {
			llama = JsonUtil.fromJson(responseBody, JsonObject.class);
		} catch (Exception e) {
			logger.info("解析llama.cpp非流式JSON失败", e);
		}
		
		if (llama == null) {
			LlamaServer.sendExpressRawJsonResponse(ctx, HttpResponseStatus.OK, responseBody.getBytes(StandardCharsets.UTF_8), false);
			return;
		}
		
		String completionId = safeString(llama, "id");
		Long created = safeLong(llama, "created");
		JsonObject timings = llama.has("timings") && llama.get("timings").isJsonObject() ? llama.getAsJsonObject("timings") : null;
		JsonObject usage = llama.has("usage") && llama.get("usage").isJsonObject() ? llama.getAsJsonObject("usage") : null;
		
		JsonArray llamaChoices = llama.has("choices") && llama.get("choices").isJsonArray() ? llama.getAsJsonArray("choices") : null;
		String finishReason = null;
		String text = "";
		if (llamaChoices != null && llamaChoices.size() > 0 && llamaChoices.get(0).isJsonObject()) {
			JsonObject c0 = llamaChoices.get(0).getAsJsonObject();
			finishReason = safeString(c0, "finish_reason");
			String t = safeString(c0, "text");
			if (t != null) {
				text = t;
			}
		}
		
		JsonObject completion = buildLmStudioTextCompletion(modelName, completionId, created, llamaChoices, usage, timings, finishReason, text);
		this.sendOpenAIJsonResponseWithCleanup(ctx, completion, HttpResponseStatus.OK);
	}

	/**
	 * 	处理流式响应
	 * @param ctx
	 * @param connection
	 * @param responseCode
	 * @param modelName
	 * @throws IOException
	 */
	private void handleStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName) throws IOException {
		// 创建响应头
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.ETAG, ParamTool.buildEtag((modelName + ":" + responseCode + ":" + System.nanoTime()).getBytes(StandardCharsets.UTF_8)));
		
		// 发送响应头
		ctx.write(response);
		ctx.flush();
		
		logger.info("开始处理流式响应，响应码: {}", responseCode);
		
		// 读取流式响应
		try (BufferedReader br = new BufferedReader(
			new InputStreamReader(
				responseCode >= 200 && responseCode < 300 ?
					connection.getInputStream() : connection.getErrorStream(),
				StandardCharsets.UTF_8
			)
		)) {
			String line;
			int chunkCount = 0;
			Map<Integer, String> toolCallIds = new HashMap<>();
			String completionId = null;
			Long created = null;
			StringBuilder fullContent = new StringBuilder();
			JsonObject timings = null;
			String finishReason = null;
			while ((line = br.readLine()) != null) {
				// 检查客户端连接是否仍然活跃
				if (!ctx.channel().isActive()) {
					logger.info("检测到客户端连接已断开，停止流式响应处理");
					if (connection != null) {
						connection.disconnect();
					}
					break;
				}
				
				// 处理SSE格式的数据行
				if (line.startsWith("data: ")) {
					String data = line.substring(6); // 去掉 "data: " 前缀
					
					// 检查是否为结束标记
					if (data.equals("[DONE]")) {
						logger.info("收到流式响应结束标记");
						break;
					}
					
					String outLine = line;
					JsonObject parsed = ParamTool.tryParseObject(data);
					if (parsed != null) {
						if (completionId == null) {
							completionId = safeString(parsed, "id");
						}
						if (created == null) {
							created = safeLong(parsed, "created");
						}

						JsonObject extractedTimings = parsed.has("timings") && parsed.get("timings").isJsonObject() ? parsed.getAsJsonObject("timings") : null;
						if (extractedTimings != null) {
							timings = extractedTimings;
							LlamaRecordService.getInstance().handleStream(modelName, data);
						}

						JsonArray choices = parsed.has("choices") && parsed.get("choices").isJsonArray() ? parsed.getAsJsonArray("choices") : null;
						if (choices != null && choices.size() > 0 && choices.get(0).isJsonObject()) {
							JsonObject c0 = choices.get(0).getAsJsonObject();
							String fr = safeString(c0, "finish_reason");
							if (fr != null && !fr.isBlank()) {
								finishReason = fr;
							}
							JsonObject delta = c0.has("delta") && c0.get("delta").isJsonObject() ? c0.getAsJsonObject("delta") : null;
							if (delta != null) {
								String piece = safeString(delta, "content");
								if (piece != null) {
									fullContent.append(piece);
								}
							} else {
								JsonObject msg = c0.has("message") && c0.get("message").isJsonObject() ? c0.getAsJsonObject("message") : null;
								if (msg != null) {
									String piece = safeString(msg, "content");
									if (piece != null) {
										fullContent.append(piece);
									}
								}
							}
						}

						boolean changed = JsonUtil.ensureToolCallIds(parsed, toolCallIds);
						if (changed) {
							outLine = "data: " + JsonUtil.toJson(parsed);
						}
					}
					
					// 创建数据块
					ByteBuf content = ctx.alloc().buffer();
					content.writeBytes(outLine.getBytes(StandardCharsets.UTF_8));
					content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
					
					// 创建HTTP内容块
					HttpContent httpContent = new DefaultHttpContent(content);
					
					// 发送数据块，并添加监听器检查写入是否成功
					ChannelFuture future = ctx.writeAndFlush(httpContent);
					
					// 检查写入是否失败，如果失败可能是客户端断开连接
					future.addListener((ChannelFutureListener) channelFuture -> {
						if (!channelFuture.isSuccess()) {
							logger.info("写入流式数据失败，可能是客户端断开连接: {}", channelFuture.cause().getMessage());
							ctx.close();
						}
					});
					
					chunkCount++;
					
					// 每发送10个数据块记录一次日志
					if (chunkCount % 10 == 0) {
						//logger.info("已发送 {} 个流式数据块", chunkCount);
					}
				} else if (line.startsWith("event: ")) {
					// 处理事件行
					ByteBuf content = ctx.alloc().buffer();
					content.writeBytes(line.getBytes(StandardCharsets.UTF_8));
					content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
					
					HttpContent httpContent = new DefaultHttpContent(content);
					ctx.writeAndFlush(httpContent);
				} else if (line.isEmpty()) {
					// 发送空行作为分隔符
					ByteBuf content = ctx.alloc().buffer();
					content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
					
					HttpContent httpContent = new DefaultHttpContent(content);
					ctx.writeAndFlush(httpContent);
				}
			}
			
			// 构造响应！
			if (responseCode >= 200 && responseCode < 300) {
				// 这里生成最后的性能状态信息。
				JsonObject completion = this.buildLmStudioCompletion(modelName, completionId, created, fullContent.toString(), timings, finishReason);
				
				//// 这里做一个调试日志
				//logger.info("测试输出 - lmstudio响应结果：{}", completion);
				String out = "data: " + JsonUtil.toJson(completion) + "\r\n\r\n";
				ByteBuf buf = ctx.alloc().buffer();
				buf.writeBytes(out.getBytes(StandardCharsets.UTF_8));
				ctx.writeAndFlush(new DefaultHttpContent(buf));
				chunkCount++;

				String done = "data: [DONE]\r\n\r\n";
				ByteBuf doneBuf = ctx.alloc().buffer();
				doneBuf.writeBytes(done.getBytes(StandardCharsets.UTF_8));
				ctx.writeAndFlush(new DefaultHttpContent(doneBuf));
				chunkCount++;
			}
			
			logger.info("流式响应处理完成，共发送 {} 个数据块", chunkCount);
		} catch (Exception e) {
			logger.info("处理流式响应时发生错误", e);
			// 检查是否是客户端断开连接导致的异常
			if (e.getMessage() != null &&
				(e.getMessage().contains("Connection reset by peer") ||
				 e.getMessage().contains("Broken pipe") ||
				 e.getMessage().contains("Connection closed"))) {
				logger.info("检测到客户端断开连接，尝试断开与llama.cpp的连接");
				if (connection != null) {
					connection.disconnect();
				}
			}
			throw e;
		}
		
		// 发送结束标记
		LastHttpContent lastContent = LastHttpContent.EMPTY_LAST_CONTENT;
		ctx.writeAndFlush(lastContent).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
	
	private void handleTextCompletionStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName) throws IOException {
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.ETAG, ParamTool.buildEtag((modelName + ":" + responseCode + ":" + System.nanoTime()).getBytes(StandardCharsets.UTF_8)));
		
		ctx.write(response);
		ctx.flush();
		
		try (BufferedReader br = new BufferedReader(
			new InputStreamReader(
				responseCode >= 200 && responseCode < 300 ?
					connection.getInputStream() : connection.getErrorStream(),
				StandardCharsets.UTF_8
			)
		)) {
			String line;
			int chunkCount = 0;
			String completionId = null;
			Long created = null;
			StringBuilder fullText = new StringBuilder();
			JsonObject timings = null;
			JsonObject usage = null;
			String finishReason = null;
			JsonArray lastChoices = null;
			
			while ((line = br.readLine()) != null) {
				if (!ctx.channel().isActive()) {
					if (connection != null) {
						connection.disconnect();
					}
					break;
				}
				
				if (line.startsWith("data: ")) {
					String data = line.substring(6);
					if (data.equals("[DONE]")) {
						break;
					}
					
					JsonObject parsed = ParamTool.tryParseObject(data);
					if (parsed != null) {
						if (completionId == null) {
							completionId = safeString(parsed, "id");
						}
						if (created == null) {
							created = safeLong(parsed, "created");
						}
						
						JsonObject extractedTimings = parsed.has("timings") && parsed.get("timings").isJsonObject() ? parsed.getAsJsonObject("timings") : null;
						if (extractedTimings != null) {
							timings = extractedTimings;
							LlamaRecordService.getInstance().handleStream(modelName, data);
						}
						JsonObject extractedUsage = parsed.has("usage") && parsed.get("usage").isJsonObject() ? parsed.getAsJsonObject("usage") : null;
						if (extractedUsage != null) {
							usage = extractedUsage;
						}
						
						JsonArray choices = parsed.has("choices") && parsed.get("choices").isJsonArray() ? parsed.getAsJsonArray("choices") : null;
						if (choices != null) {
							lastChoices = choices;
						}
						if (choices != null && choices.size() > 0 && choices.get(0).isJsonObject()) {
							JsonObject c0 = choices.get(0).getAsJsonObject();
							String fr = safeString(c0, "finish_reason");
							if (fr != null && !fr.isBlank()) {
								finishReason = fr;
							}
							String piece = safeString(c0, "text");
							if (piece != null) {
								fullText.append(piece);
							}
						}
					}
					
					ByteBuf content = ctx.alloc().buffer();
					content.writeBytes(line.getBytes(StandardCharsets.UTF_8));
					content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
					HttpContent httpContent = new DefaultHttpContent(content);
					ChannelFuture future = ctx.writeAndFlush(httpContent);
					future.addListener((ChannelFutureListener) channelFuture -> {
						if (!channelFuture.isSuccess()) {
							ctx.close();
						}
					});
					chunkCount++;
				} else if (line.startsWith("event: ")) {
					ByteBuf content = ctx.alloc().buffer();
					content.writeBytes(line.getBytes(StandardCharsets.UTF_8));
					content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
					HttpContent httpContent = new DefaultHttpContent(content);
					ctx.writeAndFlush(httpContent);
				} else if (line.isEmpty()) {
					ByteBuf content = ctx.alloc().buffer();
					content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
					HttpContent httpContent = new DefaultHttpContent(content);
					ctx.writeAndFlush(httpContent);
				}
			}
			
			if (responseCode >= 200 && responseCode < 300) {
				JsonObject completion = buildLmStudioTextCompletion(modelName, completionId, created, lastChoices, usage, timings, finishReason, fullText.toString());
				String out = "data: " + JsonUtil.toJson(completion) + "\r\n\r\n";
				ByteBuf buf = ctx.alloc().buffer();
				buf.writeBytes(out.getBytes(StandardCharsets.UTF_8));
				ctx.writeAndFlush(new DefaultHttpContent(buf));
				chunkCount++;
				
				String done = "data: [DONE]\r\n\r\n";
				ByteBuf doneBuf = ctx.alloc().buffer();
				doneBuf.writeBytes(done.getBytes(StandardCharsets.UTF_8));
				ctx.writeAndFlush(new DefaultHttpContent(doneBuf));
				chunkCount++;
			}
			
			logger.info("流式文本补全响应处理完成，共发送 {} 个数据块", chunkCount);
		} catch (Exception e) {
			logger.info("处理流式文本补全响应时发生错误", e);
			if (e.getMessage() != null &&
				(e.getMessage().contains("Connection reset by peer") ||
				 e.getMessage().contains("Broken pipe") ||
				 e.getMessage().contains("Connection closed"))) {
				if (connection != null) {
					connection.disconnect();
				}
			}
			throw e;
		}
		
		LastHttpContent lastContent = LastHttpContent.EMPTY_LAST_CONTENT;
		ctx.writeAndFlush(lastContent).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
	
	/**
	 * 发送OpenAI格式的错误响应并清理资源
	 */
	private void sendOpenAIErrorResponseWithCleanup(ChannelHandlerContext ctx, int httpStatus, String openAiErrorCode, String message, String param) {
		String type = "invalid_request_error";
		// 通过code判断错误类型
		if(httpStatus == 401) {
			type = "authentication_error";
		}
		if(httpStatus == 403) {
			type = "permission_error";
		}
		if(httpStatus == 404 || httpStatus == 400) {
			type = "invalid_request_error";
		}
		if(httpStatus == 429) {
			type = "rate_limit_error";
		}
		if(httpStatus == 500 || httpStatus == 502 || httpStatus == 503 || httpStatus == 504) {
			type = "server_error";
		}
		
		Map<String, Object> error = new HashMap<>();
		error.put("message", message);
		error.put("type", type);
		error.put("code", openAiErrorCode);
		error.put("param", param);
		
		Map<String, Object> response = new HashMap<>();
		response.put("error", error);
		this.sendOpenAIJsonResponseWithCleanup(ctx, response, HttpResponseStatus.valueOf(httpStatus));
	}
	
	

	
	/**
	 * 发送OpenAI格式的JSON响应
	 */
	private void sendOpenAIJsonResponse(ChannelHandlerContext ctx, Object data) {
		LlamaServer.sendExpressJsonResponse(ctx, HttpResponseStatus.OK, data, false);
	}
	
	/**
	 * 	发送OpenAI格式的JSON响应并清理资源
	 * @param ctx
	 * @param data
	 * @param httpStatus
	 */
	private void sendOpenAIJsonResponseWithCleanup(ChannelHandlerContext ctx, Object data, HttpResponseStatus httpStatus) {
		LlamaServer.sendExpressJsonResponse(ctx, httpStatus, data, true);
	}
	
	/**
	 * 	当连接断开时调用，用于清理{@link #channelConnectionMap}
	 * 
	 * @param ctx
	 * @throws Exception
	 */
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		// 关闭正在进行的链接
		synchronized (this.channelConnectionMap) {
			HttpURLConnection conn = this.channelConnectionMap.remove(ctx);
			if (conn != null) {
				try {
					conn.disconnect();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	/**	
	 * 	
	 * @param obj
	 * @param key
	 * @return
	 */
	private static String safeString(JsonObject obj, String key) {
		try {
			if (obj == null || key == null) {
				return null;
			}
			JsonElement el = obj.get(key);
			if (el == null || el.isJsonNull()) {
				return null;
			}
			return el.getAsString();
		} catch (Exception e) {
			return null;
		}
	}

	private static Long safeLong(JsonObject obj, String key) {
		try {
			if (obj == null || key == null) {
				return null;
			}
			JsonElement el = obj.get(key);
			if (el == null || el.isJsonNull()) {
				return null;
			}
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
				return el.getAsLong();
			}
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
				String s = el.getAsString();
				if (s == null || s.isBlank()) {
					return null;
				}
				return Long.parseLong(s.trim());
			}
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	private static Integer safeInt(JsonObject obj, String key) {
		try {
			if (obj == null || key == null) {
				return null;
			}
			JsonElement el = obj.get(key);
			if (el == null || el.isJsonNull()) {
				return null;
			}
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
				return el.getAsInt();
			}
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
				String s = el.getAsString();
				if (s == null || s.isBlank()) {
					return null;
				}
				return Integer.parseInt(s.trim());
			}
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	private static Double safeDouble(JsonObject obj, String key) {
		try {
			if (obj == null || key == null) {
				return null;
			}
			JsonElement el = obj.get(key);
			if (el == null || el.isJsonNull()) {
				return null;
			}
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
				return el.getAsDouble();
			}
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
				String s = el.getAsString();
				if (s == null || s.isBlank()) {
					return null;
				}
				return Double.parseDouble(s.trim());
			}
			return null;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 	构建lmstudio响应的最终性能信息
	 * @param modelName
	 * @param completionId
	 * @param created
	 * @param content
	 * @param timings
	 * @param finishReason
	 * @return
	 */
	private JsonObject buildLmStudioCompletion(String modelName, String completionId, Long created, String content, JsonObject timings, String finishReason) {
		String id = (completionId == null || completionId.isBlank()) ? "chatcmpl-" + UUID.randomUUID().toString().replace("-", "") : completionId;
		long createdAt = created == null ? (System.currentTimeMillis() / 1000) : created.longValue();

		JsonObject resp = new JsonObject();
		resp.addProperty("id", id);
		resp.addProperty("object", "chat.completion");
		resp.addProperty("created", createdAt);
		resp.addProperty("model", modelName);
		// message
		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		message.addProperty("content", content == null ? "" : content);
		
		JsonObject choice = new JsonObject();
		choice.addProperty("index", 0);
		choice.add("logprobs", JsonNull.INSTANCE);
		choice.addProperty("finish_reason", finishReason == null || finishReason.isBlank() ? "stop" : finishReason);
		choice.add("message", message);
		// choices
		JsonArray choices = new JsonArray();
		choices.add(choice);
		resp.add("choices", choices);
		// usage
		JsonObject usage = new JsonObject();
		Integer promptN = timings == null ? null : safeInt(timings, "prompt_n");
		Integer predictedN = timings == null ? null : safeInt(timings, "predicted_n");
		int pt = promptN == null ? 0 : promptN.intValue();
		int ct = predictedN == null ? 0 : predictedN.intValue();
		usage.addProperty("prompt_tokens", pt);
		usage.addProperty("completion_tokens", ct);
		usage.addProperty("total_tokens", pt + ct);
		resp.add("usage", usage);
		// stats
		JsonObject stats = new JsonObject();
		Double predictedPerSecond = timings == null ? null : safeDouble(timings, "predicted_per_second");
		Double promptMs = timings == null ? null : safeDouble(timings, "prompt_ms");
		Double predictedMs = timings == null ? null : safeDouble(timings, "predicted_ms");
		stats.addProperty("tokens_per_second", predictedPerSecond == null ? 0d : predictedPerSecond.doubleValue());
		stats.addProperty("time_to_first_token", promptMs == null ? 0d : (promptMs.doubleValue() / 1000d));
		stats.addProperty("generation_time", predictedMs == null ? 0d : (predictedMs.doubleValue() / 1000d));
		stats.addProperty("stop_reason", mapStopReason(finishReason));
		resp.add("stats", stats);
		// model_info
		resp.add("model_info", buildModelInfo(modelName));
		resp.add("runtime", buildRuntime());

		return resp;
	}
	
	
	/**
	 * 	构建lmstudio响应的最终性能信息
	 * @param modelName
	 * @param completionId
	 * @param created
	 * @param llamaChoices
	 * @param llamaUsage
	 * @param timings
	 * @param finishReason
	 * @param combinedText
	 * @return
	 */
	private JsonObject buildLmStudioTextCompletion(
			String modelName,
			String completionId,
			Long created,
			JsonArray llamaChoices,
			JsonObject llamaUsage,
			JsonObject timings,
			String finishReason,
			String combinedText) {
		String id = (completionId == null || completionId.isBlank()) ? "cmpl-" + UUID.randomUUID().toString().replace("-", "") : completionId;
		long createdAt = created == null ? (System.currentTimeMillis() / 1000) : created.longValue();
		
		JsonObject resp = new JsonObject();
		resp.addProperty("id", id);
		resp.addProperty("object", "text_completion");
		resp.addProperty("created", createdAt);
		resp.addProperty("model", modelName);
		
		JsonArray outChoices = new JsonArray();
		if (llamaChoices != null) {
			for (int i = 0; i < llamaChoices.size(); i++) {
				if (!llamaChoices.get(i).isJsonObject()) continue;
				JsonObject c = llamaChoices.get(i).getAsJsonObject();
				JsonObject out = new JsonObject();
				Integer idx = safeInt(c, "index");
				out.addProperty("index", idx == null ? i : idx.intValue());
				String t = safeString(c, "text");
				if (t == null && i == 0) {
					t = combinedText;
				}
				out.addProperty("text", t == null ? "" : t);
				out.add("logprobs", c.has("logprobs") ? c.get("logprobs") : JsonNull.INSTANCE);
				String fr = safeString(c, "finish_reason");
				if (fr == null || fr.isBlank()) {
					fr = finishReason;
				}
				out.addProperty("finish_reason", fr == null || fr.isBlank() ? "stop" : fr);
				outChoices.add(out);
			}
		}
		if (outChoices.size() == 0) {
			JsonObject out = new JsonObject();
			out.addProperty("index", 0);
			out.addProperty("text", combinedText == null ? "" : combinedText);
			out.add("logprobs", JsonNull.INSTANCE);
			out.addProperty("finish_reason", finishReason == null || finishReason.isBlank() ? "stop" : finishReason);
			outChoices.add(out);
		}
		resp.add("choices", outChoices);
		
		JsonObject usage = new JsonObject();
		Integer promptTokens = llamaUsage == null ? null : safeInt(llamaUsage, "prompt_tokens");
		Integer completionTokens = llamaUsage == null ? null : safeInt(llamaUsage, "completion_tokens");
		Integer totalTokens = llamaUsage == null ? null : safeInt(llamaUsage, "total_tokens");
		if (promptTokens == null || completionTokens == null || totalTokens == null) {
			Integer promptN = timings == null ? null : safeInt(timings, "prompt_n");
			Integer predictedN = timings == null ? null : safeInt(timings, "predicted_n");
			int pt = promptTokens == null ? (promptN == null ? 0 : promptN.intValue()) : promptTokens.intValue();
			int ct = completionTokens == null ? (predictedN == null ? 0 : predictedN.intValue()) : completionTokens.intValue();
			usage.addProperty("prompt_tokens", pt);
			usage.addProperty("completion_tokens", ct);
			usage.addProperty("total_tokens", pt + ct);
		} else {
			usage.addProperty("prompt_tokens", promptTokens.intValue());
			usage.addProperty("completion_tokens", completionTokens.intValue());
			usage.addProperty("total_tokens", totalTokens.intValue());
		}
		resp.add("usage", usage);
		
		JsonObject stats = new JsonObject();
		Double predictedPerSecond = timings == null ? null : safeDouble(timings, "predicted_per_second");
		Double promptMs = timings == null ? null : safeDouble(timings, "prompt_ms");
		Double predictedMs = timings == null ? null : safeDouble(timings, "predicted_ms");
		stats.addProperty("tokens_per_second", predictedPerSecond == null ? 0d : predictedPerSecond.doubleValue());
		stats.addProperty("time_to_first_token", promptMs == null ? 0d : (promptMs.doubleValue() / 1000d));
		stats.addProperty("generation_time", predictedMs == null ? 0d : (predictedMs.doubleValue() / 1000d));
		stats.addProperty("stop_reason", mapStopReason(finishReason));
		resp.add("stats", stats);
		
		resp.add("model_info", buildModelInfo(modelName));
		resp.add("runtime", buildRuntime());
		
		return resp;
	}
	
	/**
	 * 	转换停止标签。
	 * @param finishReason
	 * @return
	 */
	private static String mapStopReason(String finishReason) {
		if (finishReason == null) {
			return "eosFound";
		}
		String fr = finishReason.trim().toLowerCase(Locale.ROOT);
		if (fr.isEmpty()) {
			return "eosFound";
		}
		if ("stop".equals(fr)) {
			return "eosFound";
		}
		if ("length".equals(fr)) {
			return "maxPredictedTokensReached";
		}
		return finishReason;
	}
	
	/**
	 * 	构建模型信息
	 * @param modelName
	 * @return
	 */
	private static JsonObject buildModelInfo(String modelName) {
		JsonObject info = new JsonObject();
		LlamaServerManager manager = LlamaServerManager.getInstance();
		LlamaCppProcess proc = manager.getLoadedProcesses().get(modelName);
		GGUFModel found = null;
		for (GGUFModel m : manager.listModel()) {
			if (m == null) continue;
			String id = m.getModelId();
			if (id != null && id.equals(modelName)) {
				found = m;
				break;
			}
			String alias = m.getAlias();
			if (alias != null && alias.equals(modelName)) {
				found = m;
				break;
			}
		}
		String arch = null;
		String quant = null;
		if (found != null) {
			GGUFMetaData primary = found.getPrimaryModel();
			if (primary != null) {
				arch = primary.getArchitecture();
				quant = primary.getQuantizationType();
			}
		}

		if (arch != null) {
			info.addProperty("arch", arch);
		}
		if (quant != null) {
			info.addProperty("quant", quant);
		}
		info.addProperty("format", "gguf");
		info.addProperty("context_length", proc != null ? proc.getCtxSize() : 0);
		return info;
	}

	/**
	 * 	
	 * @return
	 */
	private static JsonObject buildRuntime() {
		JsonObject runtime = new JsonObject();
		runtime.addProperty("name", "llama.cpp-server");
		runtime.addProperty("version", "1.0.0");
		JsonArray formats = new JsonArray();
		formats.add("gguf");
		runtime.add("supported_formats", formats);
		return runtime;
	}
	
	/**
	 * 	查找模型的信息。
	 * @param allModels
	 * @param modelId
	 * @return
	 */
	private GGUFModel findModelInfo(List<GGUFModel> allModels, String modelId) {
		if (allModels == null || modelId == null) {
			return null;
		}
		for (GGUFModel model : allModels) {
			if (modelId.equals(model.getModelId())) {
				return model;
			}
		}
		return null;
	}
	
	/**
	 * 	判断模型的类型。这个严重不准确
	 * @param caps
	 * @param multimodal
	 * @return
	 */
	private static String resolveModelType(JsonObject caps, boolean multimodal) {
		if (multimodal) {
			return "vlm";
		}
		if (ParamTool.parseJsonBoolean(caps, "rerank", false)) {
			return "llm";
		}
		if (ParamTool.parseJsonBoolean(caps, "embedding", false)) {
			return "embeddings";
		}
		return "llm";
	}
}
