package org.mark.llamacpp.server.controller;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaCppProcessFix;
import org.mark.llamacpp.server.LlamaHubNode;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.BenchmarkService;
import org.mark.llamacpp.server.service.ModelRequestTracker;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.ChatTemplateFileTool;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;

/**
 * 	关于模型的控制器。
 */
public class ModelActionController implements BaseController {
	
	
	private static final Logger logger = LoggerFactory.getLogger(ModelActionController.class);
	
	/**
	 * 	
	 */
	private BenchmarkService benchmarkService = new BenchmarkService();
	
	/**
	 * 	远程节点 HTTP 连接追踪，用于客户端中断时断开远程请求。
	 */
	private ConcurrentHashMap<ChannelHandlerContext, HttpURLConnection> remoteConnections = new ConcurrentHashMap<>();
	
	
	public ModelActionController() {
		
	}
	
	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 强制刷新模型列表API
		if (uri.startsWith("/api/models/refresh")) {
			this.handleRefreshModelListRequest(ctx, request);
			return true;
		}
		// 列出全部的模型
		if (uri.startsWith("/api/models/list")) {
			this.handleModelListRequest(ctx, request);
			return true;
		}
		// 查询已经被加载的模型
		if (uri.startsWith("/api/models/loaded")) {
			this.handleLoadedModelsRequest(ctx, request);
			return true;
		}
		// 加载指定的模型
		if (uri.startsWith("/api/models/load")) {
			this.handleLoadModelRequest(ctx, request);
			return true;
		}
		// 停止指定的运行中的模型
		if (uri.startsWith("/api/models/stop")) {
			this.handleStopModelRequest(ctx, request);
			return true;
		}
		// 执行benchmark
		if (uri.equals("/api/models/benchmark")) {
			this.handleModelBenchmark(ctx, request);
			return true;
		}
		// 获取指定模型的测试记录
		if (uri.startsWith("/api/models/benchmark/list")) {
			this.handleModelBenchmarkList(ctx, request);
			return true;
		}
		// 查询指定的测试记录
		if (uri.startsWith("/api/models/benchmark/get")) {
			this.handleModelBenchmarkGet(ctx, request);
			return true;
		}
		// 删除指定的测试记录
		if (uri.startsWith("/api/models/benchmark/delete")) {
			this.handleModelBenchmarkDelete(ctx, request);
			return true;
		}
		if (uri.equals("/api/v2/models/benchmark")) {
			this.handleModelBenchmarkV2(ctx, request);
			return true;
		}

		if (uri.startsWith("/api/v2/models/benchmark/get")) {
			this.handleModelBenchmarkV2Get(ctx, request);
			return true;
		}
		if (uri.startsWith("/api/v2/models/benchmark/delete")) {
			this.handleModelBenchmarkV2Delete(ctx, request);
			return true;
		}
		
		// 对应URL-GET：/metrics
		// 客户端传入modelId作为参数
		if (uri.startsWith("/api/models/metrics")) {
			this.handleModelMetrics(ctx, request);
			return true;
		}
		// 对应URL-GET：/props
		if (uri.startsWith("/api/models/props")) {
			this.handleModelProps(ctx, request);
			return true;
		}
		
		return false;
	}
	
	/**
	 * 处理强制刷新模型列表请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleRefreshModelListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			// 检查是否指定了远程节点
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				// 刷新单个远程节点
				logger.info("[模型操作] 远程节点刷新模型: nodeId={}", nodeId);
				NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
						nodeId, "GET", "api/models/refresh", null);
				if (result.isSuccess()) {
					NodeManager.writeHttpResultToChannel(ctx, result, "[模型操作刷新远程]");
				} else {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("远程节点刷新失败: code=" + result.getStatusCode()));
				}
				return;
			}

			// 刷新本地模型列表
			LlamaServerManager manager = LlamaServerManager.getInstance();
			manager.listModel(true);

			// 同步刷新所有已启用的远程节点
			List<LlamaHubNode> enabledNodes = NodeManager.getInstance().listEnabledNodes();
			for (LlamaHubNode node : enabledNodes) {
				try {
					NodeManager.getInstance().callRemoteApi(node.getNodeId(), "GET", "api/models/refresh", null);
					logger.info("[模型操作] 已刷新远程节点: nodeId={}", node.getNodeId());
				} catch (Exception e) {
					logger.warn("[模型操作] 刷新远程节点失败: nodeId={}, error={}", node.getNodeId(), e.getMessage());
				}
			}

			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("refreshed", true);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("强制刷新模型列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("强制刷新模型列表失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 处理模型列表请求
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

		try {
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");

			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				List<Map<String, Object>> models = this.fetchRemoteModelList(nodeId);
				Map<String, Object> response = new HashMap<>();
				response.put("success", true);
				response.put("models", models);
				LlamaServer.sendJsonResponse(ctx, response);
				return;
			}

			List<Map<String, Object>> modelList = this.buildLocalModelList();

			List<LlamaHubNode> enabledNodes = NodeManager.getInstance().listEnabledNodes();
			for (LlamaHubNode node : enabledNodes) {
				List<Map<String, Object>> remoteModels = this.fetchRemoteModelList(node.getNodeId());
				modelList.addAll(remoteModels);
			}

			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("models", modelList);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("获取模型列表时发生错误", e);
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("success", false);
			errorResponse.put("error", "获取模型列表失败: " + e.getMessage());
			LlamaServer.sendJsonResponse(ctx, errorResponse);
		}
	}

	/**
	 * 构建本地模型列表
	 */
	private List<Map<String, Object>> buildLocalModelList() {
		LlamaServerManager manager = LlamaServerManager.getInstance();
		List<GGUFModel> models = manager.listModel();

		List<Map<String, Object>> modelList = new ArrayList<>();
		for (GGUFModel model : models) {
			Map<String, Object> modelInfo = new HashMap<>();

			GGUFMetaData primaryModel = model.getPrimaryModel();
			GGUFMetaData mmproj = model.getMmproj();

			String modelName = "未知模型";
			String modelId = "unknown-model-" + System.currentTimeMillis();

			if (primaryModel != null) {
				modelName = model.getName();
				if (modelName == null || modelName.trim().isEmpty()) {
					modelName = "未命名模型";
				}
				modelId = model.getModelId();
			}

			modelInfo.put("id", modelId);
			modelInfo.put("name", modelName);
			modelInfo.put("alias", model.getAlias());
			modelInfo.put("favourite", model.isFavourite());
			modelInfo.put("size", model.getSize());

			boolean isMultimodal = mmproj != null;
			boolean supportsVision = mmproj != null && mmproj.isSupportsVision();
			boolean supportsAudio = mmproj != null && mmproj.isSupportsAudio();
			modelInfo.put("isMultimodal", isMultimodal);
			modelInfo.put("supportsVision", supportsVision);
			modelInfo.put("supportsAudio", supportsAudio);

			if (manager.isLoading(modelId)) {
				modelInfo.put("isLoading", true);
			}

			String architecture = "未知";
			String quantization = "";
			if (primaryModel != null) {
				String value = primaryModel.getStringValue("general.architecture");
				if (value != null && !value.trim().isEmpty()) {
					architecture = value;
				}
				String quantizationValue = primaryModel.getQuantizationType();
				if (quantizationValue != null) {
					quantization = quantizationValue;
				}
			}
			modelInfo.put("architecture", architecture);
			modelInfo.put("quantization", quantization);
			modelInfo.put("nodeId", "local");
			modelInfo.put("nodeName", "本机");

			modelList.add(modelInfo);
		}
		return modelList;
	}

	/**
	 * 从远程节点获取模型列表
	 */
	private List<Map<String, Object>> fetchRemoteModelList(String nodeId) {
		List<Map<String, Object>> result = new ArrayList<>();
		NodeManager manager = NodeManager.getInstance();
		NodeManager.HttpResult httpResult = manager.fetchRemoteModels(nodeId);
		if (!httpResult.isSuccess()) {
			logger.warn("获取远程节点模型列表失败: nodeId={}, code={}", nodeId, httpResult.getStatusCode());
			return result;
		}
		LlamaHubNode node = manager.getNode(nodeId);
		String nodeName = node != null ? node.getName() : nodeId;

		try {
			JsonObject root = JsonUtil.fromJson(httpResult.getBody(), JsonObject.class);
			if (root == null || !root.has("models")) {
				return result;
			}
			com.google.gson.JsonArray modelsArray = root.getAsJsonArray("models");
			if (modelsArray == null) {
				return result;
			}
			for (com.google.gson.JsonElement elem : modelsArray) {
				if (!elem.isJsonObject()) continue;
				JsonObject modelObj = elem.getAsJsonObject();
				Map<String, Object> modelInfo = new HashMap<>();
				for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : modelObj.entrySet()) {
					com.google.gson.JsonElement value = entry.getValue();
					if (value == null || value.isJsonNull()) {
						modelInfo.put(entry.getKey(), null);
					} else if (value.isJsonPrimitive()) {
						com.google.gson.JsonPrimitive prim = (com.google.gson.JsonPrimitive) value;
						if (prim.isBoolean()) {
							modelInfo.put(entry.getKey(), value.getAsBoolean());
						} else if (prim.isNumber()) {
							// 防止 Gson 把 LazilyParsedNumber 序列化成 {"value": 123}
							// 整数用 Long，小数用 Double
							String numStr = prim.getAsString();
							if (numStr.indexOf('.') >= 0 || numStr.indexOf('e') >= 0 || numStr.indexOf('E') >= 0) {
								modelInfo.put(entry.getKey(), value.getAsDouble());
							} else {
								modelInfo.put(entry.getKey(), value.getAsLong());
							}
						} else {
							modelInfo.put(entry.getKey(), value.getAsString());
						}
					} else {
						modelInfo.put(entry.getKey(), JsonUtil.jsonValueToString(value));
					}
				}
				modelInfo.put("nodeId", nodeId);
				modelInfo.put("nodeName", nodeName);
				result.add(modelInfo);
			}
		} catch (Exception e) {
			logger.warn("解析远程节点模型列表失败: nodeId={}, error={}", nodeId, e.getMessage());
		}
		return result;
	}
	
	/**
	 * 处理停止模型请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleStopModelRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}

			String modelId = JsonUtil.getJsonString(obj, "modelId");
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}

			String nodeId = JsonUtil.getJsonString(obj, "nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.stopRemoteModel(ctx, nodeId, modelId);
				return;
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (manager.getLoadedProcesses().containsKey(modelId) || manager.isLoading(modelId)) {
				logger.info("[模型操作] 本地停止模型: modelId={}", modelId);
				boolean success = manager.stopModel(modelId);
				if (success) {
					Map<String, Object> data = new HashMap<>();
					data.put("message", "模型停止成功");
					LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
					LlamaServer.sendModelStopEvent(modelId, true, "模型停止成功");
				} else {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("模型停止失败或模型未加载"));
					LlamaServer.sendModelStopEvent(modelId, false, "模型停止失败或模型未加载");
				}
			} else {
				logger.info("[模型操作] 本地未找到模型，搜索远程节点: modelId={}", modelId);
				this.findAndStopOnRemoteNode(ctx, modelId);
			}
		} catch (Exception e) {
			logger.info("停止模型时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("停止模型失败: " + e.getMessage()));
		}
	}

	/**
	 * 在远程节点上查找并停止模型
	 */
	private void findAndStopOnRemoteNode(ChannelHandlerContext ctx, String modelId) {
		String nodeId = this.findNodeByLoadedModel(modelId);
		if (nodeId == null) {
			logger.warn("[模型操作] 远程节点也未找到已加载模型: modelId={}", modelId);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("模型未加载: " + modelId));
			return;
		}
		logger.info("[模型操作] 找到模型所在远程节点: modelId={}, nodeId={}", modelId, nodeId);
		this.stopRemoteModel(ctx, nodeId, modelId);
	}

	/**
	 * 停止远程节点上的模型（不主动发 modelStop 事件，由 WS 中继传递）
	 */
	private void stopRemoteModel(ChannelHandlerContext ctx, String nodeId, String modelId) {
		NodeManager manager = NodeManager.getInstance();
		JsonObject body = new JsonObject();
		body.addProperty("modelId", modelId);
		NodeManager.HttpResult result = manager.callRemoteApi(nodeId, "POST", "api/models/stop", body);
		if (result.isSuccess()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success());
		} else {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("远程节点调用失败: " + result.getBody()));
		}
	}
	
	/**
	 * 处理已加载模型请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleLoadedModelsRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

		try {
			String nodeId = ParamTool.getQueryParam(request.uri()).get("nodeId");

			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				List<Map<String, Object>> remoteLoaded = this.fetchRemoteLoadedModels(nodeId);
				Map<String, Object> response = new HashMap<>();
				response.put("success", true);
				response.put("models", remoteLoaded);
				LlamaServer.sendJsonResponse(ctx, response);
				return;
			}

			List<Map<String, Object>> loadedModels = this.buildLocalLoadedModels();

			List<LlamaHubNode> enabledNodes = NodeManager.getInstance().listEnabledNodes();
			for (LlamaHubNode node : enabledNodes) {
				List<Map<String, Object>> remoteLoaded = this.fetchRemoteLoadedModels(node.getNodeId());
				loadedModels.addAll(remoteLoaded);
			}

			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("models", loadedModels);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.info("获取已加载模型时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取已加载模型失败: " + e.getMessage()));
		}
	}

	/**
	 * 构建本地已加载模型列表
	 */
	private List<Map<String, Object>> buildLocalLoadedModels() {
		LlamaServerManager manager = LlamaServerManager.getInstance();
		Map<String, LlamaCppProcessFix> loadedProcesses = manager.getLoadedProcesses();
		List<GGUFModel> allModels = manager.listModel();

		List<Map<String, Object>> loadedModels = new ArrayList<>();

		for (Map.Entry<String, LlamaCppProcessFix> entry : loadedProcesses.entrySet()) {
			String modelId = entry.getKey();
			LlamaCppProcessFix process = entry.getValue();

			GGUFModel modelInfo = null;
			for (GGUFModel model : allModels) {
				if (model.getModelId().equals(modelId)) {
					modelInfo = model;
					break;
				}
			}

			Map<String, Object> modelData = new HashMap<>();
			modelData.put("id", modelId);
			modelData.put("name",
					modelInfo != null ? (modelInfo.getPrimaryModel() != null
							? modelInfo.getPrimaryModel().getStringValue("general.name")
							: "未知模型") : "未知模型");
			modelData.put("status", process.isRunning() ? "running" : "stopped");
			modelData.put("port", manager.getModelPort(modelId));
			modelData.put("pid", process.getPid());
			modelData.put("size", modelInfo != null ? modelInfo.getSize() : 0);
			modelData.put("path", modelInfo != null ? modelInfo.getPath() : "");
			modelData.put("nodeId", "local");
			modelData.put("nodeName", "本机");
			modelData.put("busy", ModelRequestTracker.getInstance().isModelBusy(modelId));

			loadedModels.add(modelData);
		}
		return loadedModels;
	}

	/**
	 * 从远程节点获取已加载模型列表
	 */
	private List<Map<String, Object>> fetchRemoteLoadedModels(String nodeId) {
		List<Map<String, Object>> result = new ArrayList<>();
		NodeManager manager = NodeManager.getInstance();
		NodeManager.HttpResult httpResult = manager.fetchRemoteLoadedModels(nodeId);
		if (!httpResult.isSuccess()) {
			logger.warn("获取远程节点已加载模型失败: nodeId={}, code={}", nodeId, httpResult.getStatusCode());
			return result;
		}
		LlamaHubNode node = manager.getNode(nodeId);
		String nodeName = node != null ? node.getName() : nodeId;

		try {
			JsonObject root = JsonUtil.fromJson(httpResult.getBody(), JsonObject.class);
			if (root == null || !root.has("models")) {
				return result;
			}
			com.google.gson.JsonArray modelsArray = root.getAsJsonArray("models");
			if (modelsArray == null) {
				return result;
			}
			for (com.google.gson.JsonElement elem : modelsArray) {
				if (!elem.isJsonObject()) continue;
				JsonObject modelObj = elem.getAsJsonObject();
				Map<String, Object> modelInfo = new HashMap<>();
				for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : modelObj.entrySet()) {
					com.google.gson.JsonElement value = entry.getValue();
					if (value == null || value.isJsonNull()) {
						modelInfo.put(entry.getKey(), null);
					} else if (value.isJsonPrimitive()) {
						com.google.gson.JsonPrimitive prim = (com.google.gson.JsonPrimitive) value;
						if (prim.isBoolean()) {
							modelInfo.put(entry.getKey(), value.getAsBoolean());
						} else if (prim.isNumber()) {
							// 防止 Gson 把 LazilyParsedNumber 序列化成 {"value": 123}
							String numStr = prim.getAsString();
							if (numStr.indexOf('.') >= 0 || numStr.indexOf('e') >= 0 || numStr.indexOf('E') >= 0) {
								modelInfo.put(entry.getKey(), value.getAsDouble());
							} else {
								modelInfo.put(entry.getKey(), value.getAsLong());
							}
						} else {
							modelInfo.put(entry.getKey(), value.getAsString());
						}
					} else {
						modelInfo.put(entry.getKey(), JsonUtil.jsonValueToString(value));
					}
				}
				modelInfo.put("nodeId", nodeId);
				modelInfo.put("nodeName", nodeName);
				result.add(modelInfo);
			}
		} catch (Exception e) {
			logger.warn("解析远程节点已加载模型失败: nodeId={}, error={}", nodeId, e.getMessage());
		}
		return result;
	}
	
	/**
	 * 处理加载模型的请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleLoadModelRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}

			String nodeId = JsonUtil.getJsonString(obj, "nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.loadRemoteModel(ctx, nodeId, obj);
				return;
			}

			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (manager.findModelById(modelId) != null) {
				logger.info("[模型操作] 本地加载模型: modelId={}", modelId);
				this.loadLocalModel(ctx, obj, manager);
			} else {
				logger.info("[模型操作] 本地未找到模型，搜索远程节点: modelId={}", modelId);
				this.findAndLoadOnRemoteNode(ctx, modelId, obj);
			}
		} catch (Exception e) {
			logger.info("加载模型时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("加载模型失败: " + e.getMessage()));
		}
	}

	/**
	 * 在远程节点上查找并加载模型
	 */
	private void findAndLoadOnRemoteNode(ChannelHandlerContext ctx, String modelId, JsonObject obj) {
		String nodeId = this.findNodeByModel(modelId);
		if (nodeId == null) {
			logger.warn("[模型操作] 远程节点也未找到模型: modelId={}", modelId);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到ID为 " + modelId + " 的模型"));
			return;
		}
		logger.info("[模型操作] 找到模型所在远程节点: modelId={}, nodeId={}", modelId, nodeId);
		this.loadRemoteModel(ctx, nodeId, obj);
	}

	/**
	 * 加载本地模型（原有逻辑）
	 */
	private void loadLocalModel(ChannelHandlerContext ctx, JsonObject obj, LlamaServerManager manager) {
		String cmd = JsonUtil.getJsonString(obj, "cmd", "");
		String extraParams = JsonUtil.getJsonString(obj, "extraParams", "");
		if (cmd != null) cmd = cmd.trim();
		if (extraParams != null) extraParams = extraParams.trim();
		if ((cmd == null || cmd.isEmpty()) && (extraParams == null || extraParams.isEmpty())) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的启动参数"));
			return;
		}
		boolean enableVision = ParamTool.parseJsonBoolean(obj, "enableVision", true);
		String modelId = JsonUtil.getJsonString(obj, "modelId", null);
		String modelNameCmd = JsonUtil.getJsonString(obj, "modelName", null);
		String llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPathSelect", null);
		if (llamaBinPathSelect == null || llamaBinPathSelect.trim().isEmpty()) {
			llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPath", null);
		}
		List<String> device = JsonUtil.getJsonStringList(obj.get("device"));
		Integer mg = JsonUtil.getJsonInt(obj, "mg", null);

		if (manager.getLoadedProcesses().containsKey(modelId)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("模型已经加载"));
			return;
		}
		if (manager.isLoading(modelId)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("该模型正在加载中"));
			return;
		}
		if (llamaBinPathSelect == null || llamaBinPathSelect.trim().isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未提供llamaBinPath"));
			return;
		}
		String chatTemplateFilePath = ChatTemplateFileTool.getChatTemplateCacheFilePathIfExists(modelId);
		boolean started = manager.loadModelAsyncFromCmd(modelId, llamaBinPathSelect, device, mg, enableVision, cmd, extraParams, chatTemplateFilePath);
		if (!started) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("提交加载任务失败"));
			return;
		}

		Map<String, Object> data = new HashMap<>();
		data.put("async", true);
		data.put("modelId", modelId);
		data.put("modelName", modelNameCmd);
		data.put("llamaBinPathSelect", llamaBinPathSelect);
		data.put("device", device);
		data.put("mg", mg);
		data.put("cmd", cmd);
		data.put("extraParams", extraParams);
		data.put("enableVision", enableVision);
		LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
	}

	/**
	 * 在远程节点上加载模型（移除 nodeId 避免回环）
	 */
	private void loadRemoteModel(ChannelHandlerContext ctx, String nodeId, JsonObject body) {
		if (body != null) {
			body.remove("nodeId");
		}
		NodeManager manager = NodeManager.getInstance();
		NodeManager.HttpResult result = manager.callRemoteApi(nodeId, "POST", "api/models/load", body);
		if (result.isSuccess()) {
			Map<String, Object> data = new HashMap<>();
			data.put("async", true);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} else {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("远程节点调用失败: " + result.getBody()));
		}
	}
	
	/**
	 * 执行bench测试
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelBenchmark(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject json = JsonUtil.fromJson(content, JsonObject.class);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}

			String nodeId = JsonUtil.getJsonString(json, "nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				json.remove("nodeId");
				NodeManager.HttpResult result = callRemoteApiTracked(ctx, nodeId, "POST", "api/models/benchmark", json);
				writeRemoteResult(ctx, result);
				return;
			}

			String modelId = json.has("modelId") ? json.get("modelId").getAsString() : null;
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			String cmd = JsonUtil.getJsonString(json, "cmd", null);
			if (cmd != null) {
				cmd = cmd.trim();
				if (cmd.isEmpty()) cmd = null;
			}
			if (cmd == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的cmd参数"));
				return;
			}
			String llamaBinPath = null;
			if (json.has("llamaBinPath") && !json.get("llamaBinPath").isJsonNull()) {
				llamaBinPath = json.get("llamaBinPath").getAsString();
				if (llamaBinPath != null) {
					llamaBinPath = llamaBinPath.trim();
					if (llamaBinPath.isEmpty()) {
						llamaBinPath = null;
					}
				}
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			manager.listModel();
			GGUFModel model = manager.findModelById(modelId);
			if (model == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到指定模型: " + modelId));
				return;
			}
			if (model.getPrimaryModel() == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("模型元数据不完整，无法执行基准测试"));
				return;
			}
			String modelPath = model.getPrimaryModel().getFilePath();
			if (llamaBinPath == null || llamaBinPath.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的llama.cpp路径参数: llamaBinPath"));
				return;
			}
			String osName = System.getProperty("os.name").toLowerCase();
			String executableName = "llama-bench";
			if (osName.contains("win")) {
				executableName = "llama-bench.exe";
			}
			File benchFile = new File(llamaBinPath, executableName);
			if (!benchFile.exists() || !benchFile.isFile()) {
				LlamaServer.sendJsonResponse(ctx,
						ApiResponse.error("llama-bench可执行文件不存在: " + benchFile.getAbsolutePath()));
				return;
			}
			List<String> command = new ArrayList<>();
			command.add(benchFile.getAbsolutePath());
			command.add("-m");
			command.add(modelPath);
			
			List<String> cmdArgs = sanitizeBenchmarkCmdArgs(ParamTool.splitCmdArgs(cmd));
			command.addAll(cmdArgs);
			String commandStr = String.join(" ", command);
			ProcessBuilder pb = new ProcessBuilder(command);
			pb.redirectErrorStream(true);
			String benchPath = benchFile.getAbsolutePath();
			if (benchPath.startsWith("/")) {
				int lastSlash = benchPath.lastIndexOf('/');
				if (lastSlash > 0) {
					String libPath = benchPath.substring(0, lastSlash);
					Map<String, String> env = pb.environment();
					String currentLdPath = env.get("LD_LIBRARY_PATH");

					// 构建 LD_LIBRARY_PATH
					StringBuilder newLdPath = new StringBuilder(libPath);
					if (currentLdPath != null && !currentLdPath.isEmpty()) {
						newLdPath.append(":").append(currentLdPath);
					}

					// ROCm 7.2 库路径
					String[] rocmPaths = {
						"/opt/rocm-7.2.0/lib",
						"/opt/rocm-7.2.0/lib64",
						"/opt/rocm/lib",
						"/opt/rocm/lib64",
						"/usr/local/rocm/lib",
						"/usr/local/rocm/lib64",
						"/usr/local/lib64",
						"/usr/local/lib"
					};
					for (String rocmPath : rocmPaths) {
						if (!newLdPath.toString().contains(rocmPath)) {
							newLdPath.append(":").append(rocmPath);
						}
					}

					env.put("LD_LIBRARY_PATH", newLdPath.toString());
				}
			}
			Process process = pb.start();
			StringBuilder output = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					output.append(line).append('\n');
				}
			}
			boolean finished = process.waitFor(600, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("llama-bench执行超时"));
				return;
			}
			int exitCode = process.exitValue();
			String text = output.toString().trim();
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("command", command);
			data.put("commandStr", commandStr);
			data.put("exitCode", exitCode);
			if (!text.isEmpty()) {
				data.put("rawOutput", text);
				try {
					String safeModelId = modelId == null ? "unknown" : modelId.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
					String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
					String fileName = safeModelId + "_" + timestamp + ".txt";
					File dir = new File("benchmarks");
					if (!dir.exists()) {
						dir.mkdirs();
					}
					File outFile = new File(dir, fileName);
					try (FileOutputStream fos = new FileOutputStream(outFile)) {
						StringBuilder fileContent = new StringBuilder();
						fileContent.append("command: ").append(commandStr).append(System.lineSeparator())
								.append(System.lineSeparator());
						fileContent.append(text);
						fos.write(fileContent.toString().getBytes(StandardCharsets.UTF_8));
					}
					data.put("savedPath", outFile.getAbsolutePath());
				} catch (Exception ex) {
					logger.info("保存基准测试结果到文件失败", ex);
				}
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("执行模型基准测试时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("执行模型基准测试失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	基准测试V2
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelBenchmarkV2(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject json = JsonUtil.fromJson(content, JsonObject.class);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}

			String nodeId = JsonUtil.getJsonString(json, "nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				json.remove("nodeId");
				NodeManager.HttpResult result = callRemoteApiTracked(ctx, nodeId, "POST", "api/v2/models/benchmark", json);
				writeRemoteResult(ctx, result);
				return;
			}

			Map<String, Object> data = this.benchmarkService.handleBenchmark(ctx, json);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (IllegalArgumentException | IllegalStateException e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		} catch (Exception e) {
			String msg = e.getMessage();
			if (msg != null && msg.startsWith("执行模型基准测试失败")) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(msg));
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("执行模型基准测试失败: " + e.getMessage()));
			}
		}
	}
	
	
	/**
	 * 返回测试结果列表。
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelBenchmarkList(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		
		try {
			String query = request.uri();
			Map<String, String> params = ParamTool.getQueryParam(query);
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.proxyGetRemote(ctx, request, nodeId, "api/models/benchmark/list");
				return;
			}
			String modelId = params.get("modelId");

			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			String safeModelId = modelId.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
			File dir = new File("benchmarks");
			List<Map<String, Object>> files = new ArrayList<>();
			if (dir.exists() && dir.isDirectory()) {
				File[] all = dir.listFiles();
				if (all != null) {
					Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
					for (File f : all) {
						String name = f.getName();
						if (f.isFile() && name.startsWith(safeModelId + "_") && name.endsWith(".txt")) {
							Map<String, Object> info = new HashMap<>();
							info.put("name", name);
							info.put("size", f.length());
							info.put("modified",
									new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(f.lastModified())));
							files.add(info);
						}
					}
				}
			}
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("files", files);
			data.put("count", files.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取基准测试结果列表失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	性能测试V2版，查询指定模型的测试结果。
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelBenchmarkV2Get(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		
		try {
			String query = request.uri();
			Map<String, String> params = ParamTool.getQueryParam(query);
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.proxyGetRemote(ctx, request, nodeId, "api/v2/models/benchmark/get");
				return;
			}
			String modelId = params.get("modelId");
			if (modelId != null) modelId = modelId.trim();
			if (modelId == null || modelId.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			String safeModelId = modelId.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
			File dir = new File("benchmarks");
			String fileName = safeModelId + "_V2.jsonl";
			File target = new File(dir, fileName);
			if (!target.exists() || !target.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("文件不存在"));
				return;
			}
			List<String> lines = Files.readAllLines(target.toPath(), StandardCharsets.UTF_8);
			List<Object> records = new ArrayList<>();
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				if (line == null) continue;
				String trimmed = line.trim();
				if (trimmed.isEmpty()) continue;
				JsonObject obj = JsonUtil.fromJson(trimmed, JsonObject.class);
				if (obj != null) {
					obj.addProperty("_lineNumber", Integer.valueOf(i + 1));
					records.add(JsonUtil.fromJson(obj, Object.class));
				}
			}
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("fileName", fileName);
			data.put("records", records);
			data.put("savedPath", target.getAbsolutePath());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("读取基准测试V2结果失败: " + e.getMessage()));
		}
	}

	/**
	 * 删除指定的性能测试 V2 记录。
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelBenchmarkV2Delete(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject json = JsonUtil.fromJson(content, JsonObject.class);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}

			String nodeId = JsonUtil.getJsonString(json, "nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				json.remove("nodeId");
				NodeManager.HttpResult result = callRemoteApiTracked(ctx, nodeId, "POST", "api/v2/models/benchmark/delete", json);
				writeRemoteResult(ctx, result);
				return;
			}

			String modelId = JsonUtil.getJsonString(json, "modelId", null);
			if (modelId != null) modelId = modelId.trim();
			if (modelId == null || modelId.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			Integer lineNumber = JsonUtil.getJsonInt(json, "lineNumber", null);
			if (lineNumber == null || lineNumber.intValue() <= 0) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的lineNumber参数"));
				return;
			}

			String safeModelId = modelId.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
			File dir = new File("benchmarks");
			String fileName = safeModelId + "_V2.jsonl";
			File target = new File(dir, fileName);
			if (!target.exists() || !target.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("文件不存在"));
				return;
			}

			List<String> lines = Files.readAllLines(target.toPath(), StandardCharsets.UTF_8);
			int lineIndex = lineNumber.intValue() - 1;
			if (lineIndex < 0 || lineIndex >= lines.size()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("记录不存在"));
				return;
			}
			lines.remove(lineIndex);
			Files.write(target.toPath(), lines, StandardCharsets.UTF_8);

			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("fileName", fileName);
			data.put("lineNumber", lineNumber);
			data.put("deleted", true);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("删除基准测试V2记录失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 获取指定的测试结果。
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelBenchmarkGet(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		
		try {
			String query = request.uri();
			Map<String, String> params = ParamTool.getQueryParam(query);
			String nodeId = params.get("nodeId");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				this.proxyGetRemote(ctx, request, nodeId, "api/models/benchmark/get");
				return;
			}
			String fileName = null;
			
			fileName = params.get("fileName");
			if (fileName == null || fileName.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的fileName参数"));
				return;
			}
			if (!fileName.matches("[a-zA-Z0-9._\\-]+")) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("文件名不合法"));
				return;
			}
			File dir = new File("benchmarks");
			File target = new File(dir, fileName);
			if (!target.exists() || !target.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("文件不存在"));
				return;
			}
			byte[] bytes = Files.readAllBytes(target.toPath());
			String text = new String(bytes, StandardCharsets.UTF_8);
			Map<String, Object> data = new HashMap<>();
			data.put("fileName", fileName);
			data.put("rawOutput", text);
			data.put("savedPath", target.getAbsolutePath());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("读取基准测试结果失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 删除指定的测试结果。
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelBenchmarkDelete(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		
		try {
			String query = request.uri();
			Map<String, String> params = ParamTool.getQueryParam(query);
			String nodeId = params.get("nodeId");
			String fileName = params.get("fileName");
			if (nodeId != null && !nodeId.isBlank() && !"local".equals(nodeId)) {
				JsonObject body = new JsonObject();
				body.addProperty("fileName", fileName);
				String remotePath = "api/models/benchmark/delete?fileName=" + java.net.URLEncoder.encode(fileName != null ? fileName : "", "UTF-8");
				NodeManager.HttpResult result = callRemoteApiTracked(ctx, nodeId, "POST", remotePath, body);
				writeRemoteResult(ctx, result);
				return;
			}
			
			if (fileName == null || fileName.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的fileName参数"));
				return;
			}
			if (!fileName.matches("[a-zA-Z0-9._\\-]+")) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("文件名不合法"));
				return;
			}
			File dir = new File("benchmarks");
			File target = new File(dir, fileName);
			if (!target.exists() || !target.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("文件不存在"));
				return;
			}
			Files.delete(target.toPath());
			Map<String, Object> data = new HashMap<>();
			data.put("fileName", fileName);
			data.put("deleted", true);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("删除基准测试结果失败: " + e.getMessage()));
		}
	}
	
	
	/**
	 * 加载指定模型指定slot的缓存
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelMetrics(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

		try {
			String query = request.uri();
			String modelId = null;
			Map<String, String> params = ParamTool.getQueryParam(query);
			modelId = params.get("modelId");
			
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (!manager.getLoadedProcesses().containsKey(modelId)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("模型未加载: " + modelId));
				return;
			}
			Integer port = manager.getModelPort(modelId);
			if (port == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到模型端口: " + modelId));
				return;
			}
			String targetUrl = String.format("http://localhost:%d/metrics", port.intValue());
			URL url = URI.create(targetUrl).toURL();
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			int responseCode = connection.getResponseCode();
			String responseBody;
			if (responseCode >= 200 && responseCode < 300) {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				Object parsed = JsonUtil.fromJson(responseBody, Object.class);
				Map<String, Object> data = new HashMap<>();
				data.put("modelId", modelId);
				data.put("metrics", parsed);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			} else {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取metrics失败: " + responseBody));
			}
			connection.disconnect();
		} catch (Exception e) {
			logger.info("获取metrics时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取metrics失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 处理props请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelProps(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

		try {
			String query = request.uri();
			String modelId = null;
			Map<String, String> params = ParamTool.getQueryParam(query);
			modelId = params.get("modelId");
			
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (!manager.getLoadedProcesses().containsKey(modelId)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("模型未加载: " + modelId));
				return;
			}
			Integer port = manager.getModelPort(modelId);
			if (port == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到模型端口: " + modelId));
				return;
			}
			String targetUrl = String.format("http://localhost:%d/props", port.intValue());
			URL url = URI.create(targetUrl).toURL();
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			int responseCode = connection.getResponseCode();
			String responseBody;
			if (responseCode >= 200 && responseCode < 300) {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				Object parsed = JsonUtil.fromJson(responseBody, Object.class);
				Map<String, Object> data = new HashMap<>();
				data.put("modelId", modelId);
				data.put("props", parsed);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			} else {
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取props失败: " + responseBody));
			}
			connection.disconnect();
		} catch (Exception e) {
			logger.info("获取props时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取props失败: " + e.getMessage()));
		}
	}
	
	
	/**
	 * 	断开连接。
	 */
	@Override
	public void inactive(ChannelHandlerContext ctx) {
		try {
			this.benchmarkService.channelInactive(ctx);
		} catch (Exception e) {
			e.printStackTrace();
		}
		HttpURLConnection remoteConn = this.remoteConnections.remove(ctx);
		if (remoteConn != null) {
			try { remoteConn.disconnect(); } catch (Exception ignore) {}
		}
	}
	
	/**
	 * 	调用远程 API，同时追踪 HTTP 连接以便客户端中断时断开。
	 */
	private NodeManager.HttpResult callRemoteApiTracked(ChannelHandlerContext ctx, String nodeId, String method, String path, JsonObject body) {
		NodeManager manager = NodeManager.getInstance();
		LlamaHubNode node = manager.getNode(nodeId);
		if (node == null || node.getBaseUrl() == null) {
			return new NodeManager.HttpResult(404, "Node not found: " + nodeId);
		}
		HttpURLConnection connection = null;
		try {
			String targetUrl = node.getBaseUrl() + "/" + path.replaceFirst("^/", "");
			URL url = URI.create(targetUrl).toURL();
			connection = (HttpURLConnection) url.openConnection();
			if (connection instanceof javax.net.ssl.HttpsURLConnection) {
				NodeManager.trustAllCerts((javax.net.ssl.HttpsURLConnection) connection);
			}
			connection.setRequestMethod(method);
			connection.setConnectTimeout(2000);
			connection.setReadTimeout(2000);
			if (node.getApiKey() != null && !node.getApiKey().isBlank()) {
				connection.setRequestProperty("Authorization", "Bearer " + node.getApiKey());
			}
			if (ctx != null) {
				this.remoteConnections.put(ctx, connection);
			}
			if (body != null && (method.equals("POST") || method.equals("PUT"))) {
				connection.setDoOutput(true);
				connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
				try (java.io.OutputStream os = connection.getOutputStream()) {
					String jsonStr = JsonUtil.toJson(body);
					os.write(jsonStr.getBytes(StandardCharsets.UTF_8));
				}
			}
			int responseCode = connection.getResponseCode();
			String responseBody = NodeManager.readStream(responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream());
			return new NodeManager.HttpResult(responseCode, responseBody);
		} catch (java.io.IOException e) {
			logger.warn("远程API调用失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			return new NodeManager.HttpResult(502, "Connection failed: " + e.getMessage());
		} catch (Exception e) {
			logger.warn("远程API调用失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			return new NodeManager.HttpResult(502, "Connection failed: " + e.getMessage());
		} finally {
			if (ctx != null) this.remoteConnections.remove(ctx);
			if (connection != null) connection.disconnect();
		}
	}
	
	/**
	 * 	
	 * @param args
	 * @return
	 */
	private List<String> sanitizeBenchmarkCmdArgs(List<String> args) {
		if (args == null || args.isEmpty()) return new ArrayList<>();
		List<String> input = args;
		String first = input.get(0);
		if (first != null) {
			String f = first.trim().toLowerCase();
			if (f.endsWith("llama-bench") || f.endsWith("llama-bench.exe")) {
				input = input.subList(1, input.size());
			}
		}
		
		List<String> out = new ArrayList<>(Math.max(0, input.size()));
		for (int i = 0; i < input.size(); i++) {
			String a = input.get(i);
			if (a == null) continue;
			if ("-m".equals(a) || "--model".equals(a)) {
				i++;
				continue;
			}
			out.add(a);
		}
		return out;
	}

	/**
	 * 在远程节点上查找已加载的模型，返回 nodeId
	 */
	private String findNodeByLoadedModel(String modelId) {
		NodeManager nodeManager = NodeManager.getInstance();
		for (LlamaHubNode node : nodeManager.listEnabledNodes()) {
			try {
				NodeManager.HttpResult result = nodeManager.fetchRemoteLoadedModels(node.getNodeId());
				if (!result.isSuccess()) continue;
				JsonObject root = JsonUtil.fromJson(result.getBody(), JsonObject.class);
				if (root == null || !root.has("models")) continue;
				com.google.gson.JsonArray models = root.getAsJsonArray("models");
				if (models == null) continue;
				for (com.google.gson.JsonElement el : models) {
					if (!el.isJsonObject()) continue;
					String id = JsonUtil.getJsonString(el.getAsJsonObject(), "id");
					if (modelId.equals(id)) {
						return node.getNodeId();
					}
				}
			} catch (Exception e) {
				logger.warn("[模型操作] 检查远程节点已加载模型异常: nodeId={}, error={}", node.getNodeId(), e.getMessage());
			}
		}
		return null;
	}

	/**
	 * 在远程节点上查找模型（未加载），返回 nodeId
	 */
	private String findNodeByModel(String modelId) {
		NodeManager nodeManager = NodeManager.getInstance();
		for (LlamaHubNode node : nodeManager.listEnabledNodes()) {
			try {
				NodeManager.HttpResult result = nodeManager.fetchRemoteModels(node.getNodeId());
				if (!result.isSuccess()) continue;
				JsonObject root = JsonUtil.fromJson(result.getBody(), JsonObject.class);
				if (root == null || !root.has("models")) continue;
				com.google.gson.JsonArray models = root.getAsJsonArray("models");
				if (models == null) continue;
				for (com.google.gson.JsonElement el : models) {
					if (!el.isJsonObject()) continue;
					String id = JsonUtil.getJsonString(el.getAsJsonObject(), "id");
					if (modelId.equals(id)) {
						return node.getNodeId();
					}
				}
			} catch (Exception e) {
				logger.warn("[模型操作] 检查远程节点模型异常: nodeId={}, error={}", node.getNodeId(), e.getMessage());
			}
		}
		return null;
	}

	/**
	 * 代理 GET 请求到远程节点（移除 nodeId 避免回环）
	 */
	private void proxyGetRemote(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path) {
		if (nodeId == null || nodeId.isBlank() || "local".equals(nodeId)) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("无效的远程节点: " + nodeId));
			return;
		}
		try {
			String uri = request.uri();
			int qIdx = uri.indexOf('?');
			String fullPath;
			if (qIdx >= 0) {
				String query = uri.substring(qIdx + 1);
				String[] pairs = query.split("&");
				StringBuilder cleanQuery = new StringBuilder();
				for (String pair : pairs) {
					if (pair.startsWith("nodeId=")) continue;
					if (cleanQuery.length() > 0) cleanQuery.append('&');
					cleanQuery.append(pair);
				}
				fullPath = cleanQuery.length() > 0 ? path + "?" + cleanQuery.toString() : path;
			} else {
				fullPath = path;
			}
			NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(nodeId, "GET", fullPath, null);
			writeRemoteResult(ctx, result);
		} catch (Exception e) {
			logger.warn("[模型操作] 远程代理 GET 失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("调用远程节点失败: " + e.getMessage()));
		}
	}

	/**
	 * 将远程 HTTP 结果写入 Netty channel
	 */
	private void writeRemoteResult(ChannelHandlerContext ctx, NodeManager.HttpResult result) {
		if (result.isSuccess()) {
			NodeManager.writeHttpResultToChannel(ctx, result, "[模型操作]");
		} else {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("远程节点调用失败: code=" + result.getStatusCode()));
		}
	}
}
