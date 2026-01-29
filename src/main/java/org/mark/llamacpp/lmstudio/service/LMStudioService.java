package org.mark.llamacpp.lmstudio.service;

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
import org.mark.llamacpp.server.LlamaCommandParser;
import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.OpenAIService;
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
	
	/**
	 * 	响应：/api/v0/models
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	public void handleModelList(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		try {
			// 只支持POST请求
			if (request.method() != HttpMethod.GET) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			Map<String, LlamaCppProcess> loadedProcesses = manager.getLoadedProcesses();
			List<GGUFModel> allModels = manager.listModel();
			List<Map<String, Object>> data = new ArrayList<>();

			for (Map.Entry<String, LlamaCppProcess> entry : loadedProcesses.entrySet()) {
				String modelId = entry.getKey();
				GGUFModel modelInfo = findModelInfo(allModels, modelId);
				Map<String, Object> modelData = new HashMap<>();
				modelData.put("id", modelId);
				modelData.put("object", "model");

				String modelType = "llm";
				String architecture = null;
				Integer contextLength = null;
				String quantization = null;

				if (modelInfo != null) {
					GGUFMetaData primaryModel = modelInfo.getPrimaryModel();
					if (primaryModel != null) {
						architecture = primaryModel.getStringValue("general.architecture");
						contextLength = primaryModel.getIntValue(architecture + ".context_length");
						quantization = primaryModel.getQuantizationType();
					}
					modelType = resolveModelType(architecture, modelInfo.getMmproj() != null);
				}
				
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
				if (contextLength != null) {
					modelData.put("max_context_length", contextLength);
				}
				// 能力
				List<String> capabilities = new ArrayList<>(4);
				capabilities.add("tool_use");
				
				modelData.put("capabilities", capabilities);
				data.add(modelData);
			}

			Map<String, Object> response = new HashMap<>();
			response.put("data", data);
			response.put("object", "list");
			sendOpenAIJsonResponse(ctx, response);
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
			
			// 获取LlamaServerManager实例
			LlamaServerManager manager = LlamaServerManager.getInstance();
			
			// 检查模型是否已加载
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 404, null, "Model not found: " + modelName, "model");
				return;
			}
			
			// 在这加入特殊处理，判断是否存在特殊字符。
			String body = LlamaCommandParser.filterChatCompletion(ctx, modelName, requestJson);
			if(body == null)
				return;
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
		Map<String, String> headers = new HashMap<>();
		for (Map.Entry<String, String> entry : request.headers()) {
			headers.put(entry.getKey(), entry.getValue());
		}

		int requestBodyLength = requestBody == null ? 0 : requestBody.length();
		logger.info("转发请求到llama.cpp进程: {} 端口: {} 请求体长度: {}", method.name(), port, requestBodyLength);
		
		worker.execute(() -> {
			// 添加断开连接的事件监听
			HttpURLConnection connection = null;
			try {
				// 构建目标URL
				String targetUrl = String.format("http://localhost:%d/v1/chat/completions", port);
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
		} catch (IOException e) {
			//logger.info("读取llama.cpp非流式响应失败", e);
			//this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
			throw e;
		}

		if (!(responseCode >= 200 && responseCode < 300)) {
			byte[] rawBytes = responseBody.getBytes(StandardCharsets.UTF_8);
			FullHttpResponse rawResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
			rawResp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
			rawResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, rawBytes.length);
			rawResp.headers().set(HttpHeaderNames.ETAG, ParamTool.buildEtag(rawBytes));
			rawResp.headers().set("X-Powered-By", "Express");
			rawResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
			rawResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
			rawResp.headers().set(HttpHeaderNames.CONNECTION, "alive");
			rawResp.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
			rawResp.content().writeBytes(rawBytes);
			ctx.writeAndFlush(rawResp).addListener(f -> ctx.close());
			return;
		}

		JsonObject llama = null;
		try {
			llama = JsonUtil.fromJson(responseBody, JsonObject.class);
		} catch (Exception e) {
			logger.info("解析llama.cpp非流式JSON失败", e);
		}

		if (llama == null) {
			byte[] rawBytes = responseBody.getBytes(StandardCharsets.UTF_8);
			FullHttpResponse rawResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
			rawResp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
			rawResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, rawBytes.length);
			rawResp.headers().set(HttpHeaderNames.ETAG, ParamTool.buildEtag(rawBytes));
			rawResp.headers().set("X-Powered-By", "Express");
			rawResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
			rawResp.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
			rawResp.headers().set(HttpHeaderNames.CONNECTION, "alive");
			rawResp.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
			rawResp.content().writeBytes(rawBytes);
			ctx.writeAndFlush(rawResp).addListener(f -> ctx.close());
			return;
		}

		ensureToolCallIds(llama, new HashMap<>());

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

						boolean changed = ensureToolCallIds(parsed, toolCallIds);
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
				JsonObject completion = buildLmStudioCompletion(modelName, completionId, created, fullContent.toString(), timings, finishReason);
				
				// 这里做一个调试日志
				logger.info("测试输出 - lmstudio响应结果：{}", completion);
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
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		// 添加CORS头
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		//
		response.headers().set(HttpHeaderNames.CONNECTION, "alive");
		response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
		response.headers().set(HttpHeaderNames.ETAG, ParamTool.buildEtag(content));
		response.headers().set("X-Powered-By", "Express");
		
		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
	
	/**
	 * 	发送OpenAI格式的JSON响应并清理资源
	 * @param ctx
	 * @param data
	 * @param httpStatus
	 */
	private void sendOpenAIJsonResponseWithCleanup(ChannelHandlerContext ctx, Object data, HttpResponseStatus httpStatus) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, httpStatus);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		// 添加CORS头
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		//
		response.headers().set(HttpHeaderNames.CONNECTION, "alive");
		response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
		response.headers().set(HttpHeaderNames.ETAG, ParamTool.buildEtag(content));
		response.headers().set("X-Powered-By", "Express");
		
		
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

	/**
	 * 	
	 * @param obj
	 * @param indexToId
	 * @return
	 */
	private static boolean ensureToolCallIds(JsonObject obj, Map<Integer, String> indexToId) {
		if (obj == null) {
			return false;
		}
		boolean changed = false;
		JsonElement direct = obj.get("tool_calls");
		if (direct != null && direct.isJsonArray()) {
			changed |= ensureToolCallIdsInArray(direct.getAsJsonArray(), indexToId);
		}
		JsonElement choicesEl = obj.get("choices");
		if (choicesEl != null && choicesEl.isJsonArray()) {
			JsonArray choices = choicesEl.getAsJsonArray();
			for (int i = 0; i < choices.size(); i++) {
				JsonElement cEl = choices.get(i);
				if (!cEl.isJsonObject()) {
					continue;
				}
				JsonObject c = cEl.getAsJsonObject();
				JsonObject message = (c.has("message") && c.get("message").isJsonObject()) ? c.getAsJsonObject("message") : null;
				if (message != null) {
					JsonElement tcs = message.get("tool_calls");
					if (tcs != null && tcs.isJsonArray()) {
						changed |= ensureToolCallIdsInArray(tcs.getAsJsonArray(), indexToId);
					}
				}
				JsonObject delta = (c.has("delta") && c.get("delta").isJsonObject()) ? c.getAsJsonObject("delta") : null;
				if (delta != null) {
					JsonElement tcs = delta.get("tool_calls");
					if (tcs != null && tcs.isJsonArray()) {
						changed |= ensureToolCallIdsInArray(tcs.getAsJsonArray(), indexToId);
					}
				}
			}
		}
		return changed;
	}
	
	/**
	 * 	
	 * @param arr
	 * @param indexToId
	 * @return
	 */
	private static boolean ensureToolCallIdsInArray(JsonArray arr, Map<Integer, String> indexToId) {
		if (arr == null) {
			return false;
		}
		boolean changed = false;
		for (int i = 0; i < arr.size(); i++) {
			JsonElement el = arr.get(i);
			if (el == null || !el.isJsonObject()) {
				continue;
			}
			JsonObject tc = el.getAsJsonObject();
			Integer idx = readToolCallIndex(tc, i);
			String id = safeString(tc, "id");
			if (id == null || id.isBlank()) {
				String existing = (indexToId == null || idx == null) ? null : indexToId.get(idx);
				if (existing == null || existing.isBlank()) {
					existing = "call_" + UUID.randomUUID().toString().replace("-", "");
					if (indexToId != null && idx != null) {
						indexToId.put(idx, existing);
					}
				}
				tc.addProperty("id", existing);
				changed = true;
			} else if (indexToId != null && idx != null) {
				indexToId.putIfAbsent(idx, id);
			}
		}
		return changed;
	}
	
	/**
	 * 	
	 * @param tc
	 * @param fallback
	 * @return
	 */
	private static Integer readToolCallIndex(JsonObject tc, int fallback) {
		if (tc == null) {
			return fallback;
		}
		JsonElement idxEl = tc.get("index");
		if (idxEl == null || idxEl.isJsonNull()) {
			return fallback;
		}
		try {
			if (idxEl.isJsonPrimitive() && idxEl.getAsJsonPrimitive().isNumber()) {
				return idxEl.getAsInt();
			}
			if (idxEl.isJsonPrimitive() && idxEl.getAsJsonPrimitive().isString()) {
				String s = idxEl.getAsString();
				if (s != null && !s.isBlank()) {
					return Integer.parseInt(s.trim());
				}
			}
		} catch (Exception ignore) {
		}
		return fallback;
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
	 * 	
	 * @param modelName
	 * @param completionId
	 * @param created
	 * @param content
	 * @param timings
	 * @param finishReason
	 * @return
	 */
	private static JsonObject buildLmStudioCompletion(String modelName, String completionId, Long created, String content, JsonObject timings, String finishReason) {
		String id = (completionId == null || completionId.isBlank()) ? "chatcmpl-" + UUID.randomUUID().toString().replace("-", "") : completionId;
		long createdAt = created == null ? (System.currentTimeMillis() / 1000) : created.longValue();

		JsonObject resp = new JsonObject();
		resp.addProperty("id", id);
		resp.addProperty("object", "chat.completion");
		resp.addProperty("created", createdAt);
		resp.addProperty("model", modelName);

		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		message.addProperty("content", content == null ? "" : content);

		JsonObject choice = new JsonObject();
		choice.addProperty("index", 0);
		choice.add("logprobs", JsonNull.INSTANCE);
		choice.addProperty("finish_reason", finishReason == null || finishReason.isBlank() ? "stop" : finishReason);
		choice.add("message", message);

		JsonArray choices = new JsonArray();
		choices.add(choice);
		resp.add("choices", choices);

		JsonObject usage = new JsonObject();
		Integer promptN = timings == null ? null : safeInt(timings, "prompt_n");
		Integer predictedN = timings == null ? null : safeInt(timings, "predicted_n");
		int pt = promptN == null ? 0 : promptN.intValue();
		int ct = predictedN == null ? 0 : predictedN.intValue();
		usage.addProperty("prompt_tokens", pt);
		usage.addProperty("completion_tokens", ct);
		usage.addProperty("total_tokens", pt + ct);
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
		Integer ctx = null;
		if (found != null) {
			GGUFMetaData primary = found.getPrimaryModel();
			if (primary != null) {
				arch = primary.getStringValue("general.architecture");
				if (arch != null && !arch.isBlank()) {
					ctx = primary.getIntValue(arch + ".context_length");
				}
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
		if (ctx != null) {
			info.addProperty("context_length", ctx.intValue());
		}
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
	 * 	判断模型的类型。
	 * @param architecture
	 * @param multimodal
	 * @return
	 */
	private static String resolveModelType(String architecture, boolean multimodal) {
		if (multimodal) {
			return "vlm";
		}
		if (architecture == null || architecture.isEmpty()) {
			return "llm";
		}
		String arch = architecture.toLowerCase(Locale.ROOT);
		if (arch.contains("embed") || arch.contains("embedding") || arch.contains("bert")) {
			return "embeddings";
		}
		return "llm";
	}
}
