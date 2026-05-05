package org.mark.llamacpp.ollama;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

import org.mark.llamacpp.server.LlamaHubNode;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.service.ModelRequestTracker;
import org.mark.llamacpp.server.struct.ActiveRequest;
import org.mark.llamacpp.server.struct.Timing;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * 	嵌入模型的API。
 */
public class OllamaEmbedService {
	
	
	/**
	 * 	
	 */
	private static final Logger logger = LoggerFactory.getLogger(OllamaEmbedService.class);
	
	/**
	 * 	
	 */
	private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
	
	
	
	public OllamaEmbedService() {
		
	}
	
	/**
	 * 	处理嵌入请求。
	 * @param ctx
	 * @param request
	 */
	public void handleEmbed(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST method is supported");
			return;
		}
		
		String content = request.content().toString(StandardCharsets.UTF_8);
		logger.info("收到 Ollama embed 请求: {}", content);
		if (content == null || content.trim().isEmpty()) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body is empty");
			return;
		}
		
		JsonObject ollamaReq = null;
		try {
			ollamaReq = JsonUtil.fromJson(content, JsonObject.class);
		} catch (Exception e) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
			return;
		}
		if (ollamaReq == null) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
			return;
		}
		
		String nodeId = JsonUtil.getJsonString(ollamaReq, "nodeId", null);
		if (nodeId != null) {
			ollamaReq.remove("nodeId");
		}

		final String modelName = JsonUtil.getJsonString(ollamaReq, "model", null);
		if (modelName == null || modelName.isBlank()) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing required parameter: model");
			return;
		}

		String targetUrl = null;
		String remoteApiKey = null;

		if (nodeId != null && !nodeId.isBlank()) {
			NodeManager nodeManager = NodeManager.getInstance();
			LlamaHubNode node = nodeManager.getNode(nodeId);
			if (node == null || !node.isEnabled()) {
				Ollama.sendOllamaError(ctx, HttpResponseStatus.NOT_FOUND, "Remote node not found or disabled: " + nodeId);
				return;
			}
			targetUrl = node.getBaseUrl() + "/v1/embeddings";
			remoteApiKey = node.getApiKey();
			logger.info("[OllamaEmbed路由] 请求体指定 nodeId，直接路由远程节点: nodeId={}, model={}", nodeId, modelName);
		} else {
			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (manager.getLoadedProcesses().containsKey(modelName)) {
				Integer port = manager.getModelPort(modelName);
				if (port != null) {
					targetUrl = String.format("http://localhost:%d/v1/embeddings", port.intValue());
				}
			}
			if (targetUrl == null) {
				logger.info("[OllamaEmbed路由] 本地未找到模型，开始搜索远程节点: model={}", modelName);
				String[] remote = this.resolveFromRemoteNodes(modelName);
				if (remote != null) {
					targetUrl = remote[0];
					remoteApiKey = remote[1];
				}
			}
		}

		if (targetUrl == null) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
			return;
		}

		final String finalTargetUrl = targetUrl;
		final String finalRemoteApiKey = remoteApiKey;

		JsonObject openAiReq = OllamaApiTool.toOpenAIEmbeddingsRequest(ollamaReq);
		if (openAiReq == null) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Missing required parameter: input");
			return;
		}
		openAiReq.addProperty("model", modelName);
		
		String requestBody = JsonUtil.toJson(openAiReq);
		
		this.worker.execute(() -> {
			String requestId = ModelRequestTracker.getInstance().createRequest(modelName, "/api/embed");
			HttpURLConnection connection = null;
			try {
				long startNs = System.nanoTime();
				logger.info("连接到目标: {}", finalTargetUrl);
				URL url = URI.create(finalTargetUrl).toURL();
				connection = (HttpURLConnection) url.openConnection();
				if (connection instanceof HttpsURLConnection) {
					NodeManager.trustAllCerts((HttpsURLConnection) connection);
				}
				connection.setRequestMethod("POST");
				connection.setConnectTimeout(36000 * 1000);
				connection.setReadTimeout(36000 * 1000);
				connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
				if (finalRemoteApiKey != null && !finalRemoteApiKey.isBlank()) {
					connection.setRequestProperty("Authorization", "Bearer " + finalRemoteApiKey);
				}
				connection.setDoOutput(true);
				byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
				connection.setRequestProperty("Content-Length", String.valueOf(input.length));
				try (OutputStream os = connection.getOutputStream()) {
					os.write(input, 0, input.length);
				}
				
				int responseCode = connection.getResponseCode();
				ModelRequestTracker.getInstance().updatePhase(requestId, ActiveRequest.Phase.GENERATION);
				String responseBody = OllamaApiTool.readBody(connection, responseCode >= 200 && responseCode < 300);
				long totalDurationNs = Math.max(0L, System.nanoTime() - startNs);
				if (!(responseCode >= 200 && responseCode < 300)) {
					String msg = OllamaApiTool.extractOpenAIErrorMessage(responseBody);
					Ollama.sendOllamaError(ctx, HttpResponseStatus.valueOf(responseCode), msg == null ? responseBody : msg);
					return;
				}
				
				JsonObject parsed = null;
				try {
					parsed = JsonUtil.fromJson(responseBody, JsonObject.class);
				} catch (Exception ignore) {
				}
				if (parsed != null && parsed.has("timings")) {
					try {
						Timing timing = JsonUtil.fromJson(parsed.get("timings"), Timing.class);
						ModelRequestTracker.getInstance().updateTiming(requestId, timing);
					} catch (Exception ignore) {}
				}
				// 回复客户端
				Map<String, Object> out = OllamaApiTool.toOllamaEmbedResponse(modelName, parsed, totalDurationNs);
				Ollama.sendOllamaChunkedJson(ctx, HttpResponseStatus.OK, out);
			} catch (Exception e) {
				logger.info("处理Ollama embed请求时发生错误", e);
				Ollama.sendOllamaError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
			} catch (Throwable t) {
				logger.error("虚拟线程异常已兜底: {}", t.getMessage(), t);
			} finally {
				ModelRequestTracker.getInstance().removeRequest(requestId);
				if (connection != null) {
					connection.disconnect();
				}
			}
		});
		
	}

	/**
	 * 从远程节点中查找模型
	 */
	private String[] resolveFromRemoteNodes(String modelName) {
		NodeManager nodeManager = NodeManager.getInstance();
		List<LlamaHubNode> enabledNodes = nodeManager.listEnabledNodes();
		logger.info("[OllamaEmbed路由] 远程节点列表: count={}", enabledNodes.size());

		for (LlamaHubNode node : enabledNodes) {
			logger.info("[OllamaEmbed路由] 检查远程节点: nodeId={}, baseUrl={}", node.getNodeId(), node.getBaseUrl());
			try {
				NodeManager.HttpResult result = nodeManager.callRemoteApi(node.getNodeId(), "GET", "/v1/models", null);
				if (!result.isSuccess()) {
					logger.warn("[OllamaEmbed路由] 远程节点请求失败: nodeId={}, body={}", node.getNodeId(), result.getBody());
					continue;
				}

				JsonObject root = JsonUtil.fromJson(result.getBody(), JsonObject.class);
				if (root == null) continue;

				if (root.has("models") && root.get("models").isJsonArray()) {
					JsonArray remoteModels = root.getAsJsonArray("models");
					for (JsonElement el : remoteModels) {
						if (!el.isJsonObject()) continue;
						JsonObject m = el.getAsJsonObject();
						String remoteKey = JsonUtil.getJsonString(m, "model");
						if (remoteKey.isEmpty()) remoteKey = JsonUtil.getJsonString(m, "name");
						if (modelName.equals(remoteKey)) {
							logger.info("[OllamaEmbed路由] 匹配成功: model={}, nodeId={}", modelName, node.getNodeId());
							return new String[]{ node.getBaseUrl() + "/v1/embeddings", node.getApiKey() };
						}
					}
				}

				if (root.has("data") && root.get("data").isJsonArray()) {
					JsonArray dataArr = root.getAsJsonArray("data");
					for (JsonElement el : dataArr) {
						if (!el.isJsonObject()) continue;
						JsonObject d = el.getAsJsonObject();
						String id = JsonUtil.getJsonString(d, "id", "");
						if (modelName.equals(id)) {
							logger.info("[OllamaEmbed路由] data匹配成功: model={}, nodeId={}", modelName, node.getNodeId());
							return new String[]{ node.getBaseUrl() + "/v1/embeddings", node.getApiKey() };
						}
					}
				}
			} catch (Exception e) {
				logger.warn("[OllamaEmbed路由] 异常: nodeId={}, error={}", node.getNodeId(), e.getMessage());
			}
		}
		logger.warn("[OllamaEmbed路由] 所有远程节点均未找到: model={}", modelName);
		return null;
	}
}
