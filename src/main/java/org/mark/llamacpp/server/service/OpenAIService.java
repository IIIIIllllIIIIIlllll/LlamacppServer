package org.mark.llamacpp.server.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
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
 * 	预留。
 */
public class OpenAIService {
	
	private static final Logger logger = LoggerFactory.getLogger(OpenAIService.class);
	
	/**
	 * 	存储当前通道正在处理的模型链接，用于在连接关闭时停止对应的模型进程
	 */
	private final Map<ChannelHandlerContext, HttpURLConnection> channelConnectionMap = new HashMap<>();
	
	/**
	 * 	线程池。
	 */
	private static final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

	private SimpleDateFormat sdf = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);
	
	public OpenAIService() {
		
	}
	
	/**
	 * 	处理模型列表请求
	 * 	/api/models
	 * 	
	 * @param ctx
	 * @param request
	 */
	public void handleOpenAIModelsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
//		try {
//			// 只支持GET请求
//			if (request.method() != HttpMethod.GET) {
//				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only GET method is supported", "method");
//				return;
//			}
//
//			// 获取LlamaServerManager实例
//			LlamaServerManager manager = LlamaServerManager.getInstance();
//			
//			// 获取已加载的进程信息
//			Map<String, LlamaCppProcess> loadedProcesses = manager.getLoadedProcesses();
//			
//			// 构建OpenAI格式的模型列表
//			List<Map<String, Object>> openAIModels = new ArrayList<>();
//			
//			for (Map.Entry<String, LlamaCppProcess> entry : loadedProcesses.entrySet()) {
//				String modelId = entry.getKey();
//				// 构建OpenAI格式的模型信息
//				Map<String, Object> modelData = new HashMap<>();
//				modelData.put("id", modelId);
//				modelData.put("object", "model");
//				modelData.put("owned_by", "organization_owner");
//				
//				openAIModels.add(modelData);
//			}
//			
//			// 构建OpenAI格式的响应
//			Map<String, Object> response = new HashMap<>();
//			response.put("object", "list");
//			response.put("data", openAIModels);
//			sendOpenAIJsonResponse(ctx, response);
//		} catch (Exception e) {
//			logger.info("处理OpenAI模型列表请求时发生错误", e);
//			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
//		}
		try {
			// 只支持GET请求
			if (request.method() != HttpMethod.GET) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only GET method is supported", "method");
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			Map<String, LlamaCppProcess> loaded = manager.getLoadedProcesses();

			Map<String, JsonObject> modelsByKey = new LinkedHashMap<>();
			Map<String, JsonObject> dataById = new LinkedHashMap<>();

			for (Map.Entry<String, LlamaCppProcess> e : loaded.entrySet()) {
				String modelId = e.getKey();
				if (modelId == null || modelId.isBlank()) {
					continue;
				}
				JsonObject info = manager.getLoadedModelInfo(modelId);
				if (info == null) {
					try {
						info = manager.handleModelInfo(modelId);
					} catch (Exception ignore) {
						info = null;
					}
				}
				if (info == null) {
					continue;
				}

				if (!info.has("items") || !info.get("items").isJsonArray()) {
					continue;
				}
				JsonArray items = info.getAsJsonArray("items");
				for (JsonElement itemEl : items) {
					if (itemEl == null || itemEl.isJsonNull() || !itemEl.isJsonObject()) {
						continue;
					}
					JsonObject item = itemEl.getAsJsonObject();

					if (item.has("model") && item.get("model").isJsonObject()) {
						JsonObject m = item.getAsJsonObject("model");
						String key = JsonUtil.getJsonString(m, "model");
						if (key.isEmpty()) {
							key = JsonUtil.getJsonString(m, "name");
						}
						if (!key.isEmpty() && !modelsByKey.containsKey(key)) {
							modelsByKey.put(key, m.deepCopy());
						}
					}

					if (item.has("data") && item.get("data").isJsonObject()) {
						JsonObject d = item.getAsJsonObject("data");
						String id = JsonUtil.getJsonString(d, "id");
						if (!id.isEmpty() && !dataById.containsKey(id)) {
							dataById.put(id, d.deepCopy());
						}
					}
				}
			}

			JsonArray models = new JsonArray();
			for (JsonObject m : modelsByKey.values()) {
				models.add(m);
			}
			JsonArray data = new JsonArray();
			for (JsonObject d : dataById.values()) {
				data.add(d);
			}

			JsonObject response = new JsonObject();
			response.addProperty("object", "list");
			response.add("models", models);
			response.add("data", data);
			sendOpenAIJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("获取模型列表时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	
	/**
	 * 	处理 OpenAI 聊天补全请求
	 * 	/v1/chat/completions
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
			
			// 获取LlamaServerManager实例
			LlamaServerManager manager = LlamaServerManager.getInstance();
			
			// 检查模型是否已加载
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Model not found: " + modelName, "model");
				return;
			}

			String body = content;
			// 获取模型端口
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + modelName, null);
				return;
			}
			// 转发请求到对应的llama.cpp进程
			this.forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, "/v1/chat/completions", isStream, body);
		} catch (Exception e) {
			logger.info("处理OpenAI聊天补全请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	/**
	 * 处理 OpenAI 文本补全请求
	 */
	public void handleOpenAICompletionsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
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

			// 获取LlamaServerManager实例
			LlamaServerManager manager = LlamaServerManager.getInstance();

			String modelName = null;

			// 搜索模型的名字，如果没有这个字段，则直接取用第一个模型。
			if (!requestJson.has("model")) {
				modelName = manager.getFirstModelName();
				if (modelName == null) {
					this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "No models are currently loaded", null);
					return;
				}
			} else {
				modelName = requestJson.get("model").getAsString();
			}

			// 检查是否为流式请求
			boolean isStream = false;
			if (requestJson.has("stream")) {
				isStream = requestJson.get("stream").getAsBoolean();
			}

			// 检查模型是否已加载
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Model not found: " + modelName, "model");
				return;
			}
			
			// 在这加入特殊处理，判断是否存在特殊字符。
			//String body = LlamaCommandParser.filterCompletion(ctx, modelName, requestJson);
			//if(body == null)
				//return;
			// 获取模型端口
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + modelName, null);
				return;
			}
			// 转发请求到对应的llama.cpp进程
			this.forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, "/v1/completions", isStream, JsonUtil.toJson(requestJson));
		} catch (Exception e) {
			logger.info("处理OpenAI文本补全请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	/**
	 * 处理 OpenAI 嵌入请求
	 */
	public void handleOpenAIEmbeddingsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "messages");
				return;
			}
			JsonObject requestJson = JsonUtil.fromJson(content, JsonObject.class);
			LlamaServerManager manager = LlamaServerManager.getInstance();
			String modelName = null;
			if (!requestJson.has("model")) {
				modelName = manager.getFirstModelName();
				if (modelName == null) {
					this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "No models are currently loaded", null);
					return;
				}
			} else {
				modelName = requestJson.get("model").getAsString();
			}
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Model not found: " + modelName, "model");
				return;
			}
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + modelName, null);
				return;
			}
			this.forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, "/v1/embeddings", false, request.content().toString(StandardCharsets.UTF_8));
		} catch (Exception e) {
			logger.info("处理OpenAI嵌入请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	
	/**
	 * 转发请求到对应的llama.cpp进程
	 */
	private void forwardRequestToLlamaCpp(ChannelHandlerContext ctx, FullHttpRequest request, String modelName, int port, String endpoint, boolean isStream, String requestBody) {
		// 在异步执行前先读取请求体，避免ByteBuf引用计数问题
		HttpMethod method = request.method();
		// 复制请求头，避免在异步任务中访问已释放的请求对象
		Map<String, String> headers = new HashMap<>();
		for (Map.Entry<String, String> entry : request.headers()) {
			headers.put(entry.getKey(), entry.getValue());
		}

		int requestBodyLength = requestBody == null ? 0 : requestBody.length();
		logger.info("转发请求到llama.cpp进程: {} {} 端口: {} 请求体长度: {}", method.name(), endpoint, port, requestBodyLength);
		
		worker.execute(() -> {
			// 添加断开连接的事件监听
			HttpURLConnection connection = null;
			try {
				// 构建目标URL
				String targetUrl = String.format("http://localhost:%d%s", port, endpoint);
				logger.info("连接到llama.cpp进程: {}", targetUrl);
				
				URL url = URI.create(targetUrl).toURL();
				connection = (HttpURLConnection) url.openConnection();
				
				// 保存本次请求的链接到缓存
				synchronized (this.channelConnectionMap) {
					this.channelConnectionMap.put(ctx, connection);
				}
				
				// 设置请求方法
				connection.setRequestMethod(method.name());
				
				// 设置必要的请求头
				for (Map.Entry<String, String> entry : headers.entrySet()) {
					// 跳过一些可能导致问题的头
					if (!entry.getKey().equalsIgnoreCase("Connection") &&
						!entry.getKey().equalsIgnoreCase("Content-Length") &&
						!entry.getKey().equalsIgnoreCase("Transfer-Encoding")) {
						connection.setRequestProperty(entry.getKey(), entry.getValue());
					}
				}
				
				// 设置连接和读取超时
				connection.setConnectTimeout(36000 * 1000);
				connection.setReadTimeout(36000 * 1000);
				
				// 对于POST请求，设置请求体
				if (method == HttpMethod.POST && requestBody != null && !requestBody.isEmpty()) {
					connection.setDoOutput(true);
					try (OutputStream os = connection.getOutputStream()) {
						byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
						os.write(input, 0, input.length);
						logger.info("已发送请求体到llama.cpp进程，大小: {} 字节", input.length);
					}
				}
				
				// 获取响应码
				int responseCode = connection.getResponseCode();
				logger.info("llama.cpp进程响应码: {}", responseCode);
				
				if (isStream) {
					// 处理流式响应
					this.handleStreamResponse(ctx, connection, responseCode, modelName);
				} else {
					// 处理非流式响应
					this.handleNonStreamResponse(ctx, connection, responseCode);
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
	
	/**
	 * 处理非流式响应
	 */
	private void handleNonStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode) throws IOException {
		// 读取响应
		String responseBody;
		if (responseCode >= 200 && responseCode < 300) {
			try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
				StringBuilder response = new StringBuilder();
				String responseLine;
				while ((responseLine = br.readLine()) != null) {
					response.append(responseLine.trim());
				}
				responseBody = response.toString();
			}
		} else {
			// 读取错误响应
			try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
				StringBuilder response = new StringBuilder();
				String responseLine;
				while ((responseLine = br.readLine()) != null) {
					response.append(responseLine.trim());
				}
				responseBody = response.toString();
			}
		}

		if (responseCode >= 200 && responseCode < 300) {
			JsonObject parsed = JsonUtil.tryParseObject(responseBody);
			if (parsed != null) {
				boolean changed = JsonUtil.ensureToolCallIds(parsed, null);
				if (changed) {
					responseBody = JsonUtil.toJson(parsed);
				}
			}
		}
		
		// 创建响应
		FullHttpResponse response = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1,
			HttpResponseStatus.valueOf(responseCode)
		);
		
		// 设置响应头
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseBytes.length);
		response.headers().set(HttpHeaderNames.ETAG, buildEtag(responseBytes));
		// 添加CORS头
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");
		
		// 设置响应体
		response.content().writeBytes(responseBytes);
		
		// 发送响应
		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
	
	/**
	 * 处理流式响应
	 */
	private void handleStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName) throws IOException {
		// 创建响应头
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.ETAG, buildEtag((modelName + ":" + responseCode + ":" + System.nanoTime()).getBytes(StandardCharsets.UTF_8)));
		
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
			while ((line = br.readLine()) != null) {
				// 检查客户端连接是否仍然活跃
				if (!ctx.channel().isActive() || !ctx.channel().isWritable()) {
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
					JsonObject parsed = JsonUtil.tryParseObject(data);
					if (parsed != null) {
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

//	private static String safeString(JsonObject obj, String key) {
//		try {
//			if (obj == null || key == null) {
//				return null;
//			}
//			JsonElement el = obj.get(key);
//			if (el == null || el.isJsonNull()) {
//				return null;
//			}
//			return el.getAsString();
//		} catch (Exception e) {
//			return null;
//		}
//	}

	private static String buildEtag(byte[] content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(content == null ? new byte[0] : content);
			StringBuilder sb = new StringBuilder(hash.length * 2 + 2);
			sb.append('"');
			for (byte b : hash) {
				sb.append(String.format("%02x", b));
			}
			sb.append('"');
			return sb.toString();
		} catch (Exception e) {
			return "\"" + UUID.randomUUID().toString().replace("-", "") + "\"";
		}
	}

	/**
	 * 发送OpenAI格式的JSON响应
	 */
	private void sendOpenAIJsonResponse(ChannelHandlerContext ctx, Object data) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.headers().set(HttpHeaderNames.ETAG, buildEtag(content));
		response.headers().set("X-Powered-By", "Express");
		// 添加CORS头
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		//response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.CONNECTION, "alive");
		response.headers().set(HttpHeaderNames.DATE, this.sdf.format(new Date()));
		
		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
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
		sendOpenAIJsonResponseWithCleanup(ctx, response, HttpResponseStatus.valueOf(httpStatus));
	}
	
	
	/**
	 * 发送OpenAI格式的JSON响应并清理资源
	 */
	private void sendOpenAIJsonResponseWithCleanup(ChannelHandlerContext ctx, Object data, HttpResponseStatus httpStatus) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, httpStatus);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.headers().set(HttpHeaderNames.ETAG, buildEtag(content));
		response.headers().set("X-Powered-By", "Express");
		// 添加CORS头
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		response.headers().set(HttpHeaderNames.CONNECTION, "alive");
		response.headers().set(HttpHeaderNames.DATE, this.sdf.format(new Date()));
		
		
		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
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
}
