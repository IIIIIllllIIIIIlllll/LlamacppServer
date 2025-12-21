package org.mark.llamacpp.server.channel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.URL;
import java.net.URLDecoder;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFMetaDataReader;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.ConfigManager;
import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.LlamaCppConfig;
import org.mark.llamacpp.server.struct.LoadModelRequest;
import org.mark.llamacpp.server.struct.ModelLaunchOptions;
import org.mark.llamacpp.server.struct.StopModelRequest;
import org.mark.llamacpp.server.struct.VramEstimation;
import org.mark.llamacpp.server.tools.VramEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;



/**
 * 	基本路由处理器。
 * 	实现本项目用到的API端点。
 */
public class BasicRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
	
	
	private static final Logger logger = LoggerFactory.getLogger(BasicRouterHandler.class);

	private static final Gson gson = new Gson();

	
	public BasicRouterHandler() {
		
		
	}
	
	
	
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		if (!request.decoderResult().isSuccess()) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求解析失败");
			return;
		}
		
		// 1.
		String uri = request.uri();
		logger.info("收到请求: {} {}", request.method().name(), uri);
		// 傻逼浏览器不知道为什么一直在他妈的访问/.well-known/appspecific/com.chrome.devtools.json
		if ("/.well-known/appspecific/com.chrome.devtools.json".equals(uri)) {
			ctx.close();
			return;
		}
		
		// 处理模型API请求
		if (uri.startsWith("/api/") || uri.startsWith("/v1") || uri.startsWith("/session")) {
			try {
				this.handleApiRequest(ctx, request, uri);
			}catch (Exception e) {
				e.printStackTrace();
			}
			return;
		}
		
		// 
		if (request.method() != HttpMethod.GET) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "仅支持GET请求");
			return;
		}
		
		// 解码URI
		String path = URLDecoder.decode(uri, "UTF-8");
		boolean isRootRequest = path.equals("/");

		if (isRootRequest) {
			// 只有当用户访问根路径时，才返回首页
			path = "/index.html";
		}
		// 处理根路径
		if (path.startsWith("/")) {
			// path = path.substring(1);
		}
		URL url = LlamaServer.class.getResource("/web" + path);
		
		if (url == null) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "文件不存在: " + path);
			return;
		}
		// 对于非API请求，只允许访问静态文件，不允许目录浏览
		// 首先尝试从resources目录获取文件
		File file = new File(url.getFile().replace("%20", " "));
		if (!file.exists()) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "文件不存在: " + path);
			return;
		}
		if (file.isDirectory()) {
			// 不允许直接访问目录，必须通过API
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.FORBIDDEN, "不允许直接访问目录，请使用API获取文件列表");
		} else {
			LlamaServer.sendFile(ctx, file);
		}
	}
	
	
	
	private void handleApiRequest(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
		// 已加载模型API
		if (uri.startsWith("/api/models/loaded")) {
			this.handleLoadedModelsRequest(ctx, request);
			return;
		}
		// 模型列表API
		if (uri.startsWith("/api/models/list")) {
			this.handleModelListRequest(ctx, request);
			return;
		}
		// 显存估算API
		if (uri.startsWith("/api/models/vram/estimate")) {
			this.handleVramEstimateRequest(ctx, request);
			return;
		}
		// 设置模型别名API
		if (uri.startsWith("/api/model/alias/set")) {
			this.handleSetModelAliasRequest(ctx, request);
			return;
		}
		if (uri.startsWith("/api/models/favourite")) {
			this.handleModelFavouriteRequest(ctx, request);
			return;
		}
		// 强制刷新模型列表API
		if (uri.startsWith("/api/models/refresh")) {
			this.handleRefreshModelListRequest(ctx, request);
			return;
		}
		// 加载模型API
		if (uri.startsWith("/api/models/load")) {
			this.handleLoadModelRequest(ctx, request);
			return;
		}
		// 停止模型API
		if (uri.startsWith("/api/models/stop")) {
			this.handleStopModelRequest(ctx, request);
			return;
		}
		if (uri.startsWith("/api/models/config/get")) {
			this.handleModelConfigRequest(ctx, request);
			return;
		}
		if (uri.startsWith("/api/models/details")) {
			this.handleModelDetailsRequest(ctx, request);
			return;
		}
		if (uri.startsWith("/api/models/config/set")) {
			this.handleModelConfigSetRequest(ctx, request);
			return;
		}
		// 查询对应模型的/solts接口
		if (uri.startsWith("/api/models/slots/get")) {
			this.handleModelSlotsGet(ctx, request);
			return;
		}
		// 对应URL-POST：/slots/{solt_id}?action=save
		if (uri.startsWith("/api/models/slots/save")) {
			this.handleModelSlotsSave(ctx, request);
			return;
		}
		// 对应URL-POST：/slots/{slot_id}?action=load
		if (uri.startsWith("/api/models/slots/load")) {
			this.handleModelSlotsLoad(ctx, request);
			return;
		}
		// 对应URL-GET：/metrics
		// 客户端传入modelId作为参数
		if(uri.startsWith("/api/models/metrics")) {
			this.handleModelMetrics(ctx, request);
			return;
		}
		// 对应URL-GET：/props
		if (uri.startsWith("/api/models/props")) {
			this.handleModelProps(ctx, request);
			return;
		}
		
		/*
		// 会话相关
		if (uri.startsWith("/api/chat/sessions/create")) {
			ApiResponse response = this.sessionService.handleChatSessionCreate(ctx, request);
			return;
		}
		if (uri.startsWith("/api/chat/sessions/list")) {
			this.sessionService.handleChatSessionList(ctx, request);
			return;
		}
		*/
		
		// 停止服务API
		if (uri.startsWith("/api/shutdown")) {
			this.handleShutdownRequest(ctx, request);
			return;
		}
		if (uri.startsWith("/api/setting")) {
			this.handleSettingRequest(ctx, request);
			return;
		}
		if (uri.startsWith("/api/llamacpp/add")) {
			this.handleLlamaCppAdd(ctx, request);
			return;
		}
		if (uri.startsWith("/api/llamacpp/remove")) {
			this.handleLlamaCppRemove(ctx, request);
			return;
		}
		if (uri.startsWith("/api/llamacpp/list")) {
			this.handleLlamaCppList(ctx, request);
			return;
		}
		if (uri.startsWith("/api/sys/console")) {
			this.handleSysConsoleRequest(ctx, request);
			return;
		}
		if (uri.equals("/api/models/benchmark")) {
			this.handleModelBenchmark(ctx, request);
			return;
		}
		if (uri.startsWith("/api/models/benchmark/list")) {
			this.handleModelBenchmarkList(ctx, request);
			return;
		}
		if (uri.startsWith("/api/models/benchmark/get")) {
			this.handleModelBenchmarkGet(ctx, request);
			return;
		}
		if (uri.startsWith("/api/models/benchmark/delete")) {
			this.handleModelBenchmarkDelete(ctx, request);
			return;
		}

		ctx.fireChannelRead(request.retain());
	}



	
	/**
	 * 	获取指定模型的slots信息
	 */
	private void handleModelSlotsGet(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.GET) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
				return;
			}
			String query = request.uri();
			String modelId = null;
			String failOnNoSlot = null;
			if (query.contains("?")) {
				String q = query.substring(query.indexOf("?") + 1);
				// modelId
				if (q.contains("modelId=")) {
					String tmp = q.substring(q.indexOf("modelId=") + 8);
					if (tmp.contains("&")) tmp = tmp.substring(0, tmp.indexOf("&"));
					modelId = URLDecoder.decode(tmp, "UTF-8");
				}
				// fail_on_no_slot
				if (q.contains("fail_on_no_slot=")) {
					String tmp = q.substring(q.indexOf("fail_on_no_slot=") + 16);
					if (tmp.contains("&")) tmp = tmp.substring(0, tmp.indexOf("&"));
					failOnNoSlot = URLDecoder.decode(tmp, "UTF-8");
				}
			}
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
			String endpoint = "/slots";
			if (failOnNoSlot != null && !failOnNoSlot.isEmpty()) {
				endpoint += "?fail_on_no_slot=" + failOnNoSlot;
			}
			String targetUrl = String.format("http://localhost:%d%s", port, endpoint);
			URL url = URI.create(targetUrl).toURL();
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			int responseCode = connection.getResponseCode();
			String responseBody;
			if (responseCode >= 200 && responseCode < 300) {
				try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				Object parsed = gson.fromJson(responseBody, Object.class);
				Map<String, Object> data = new HashMap<>();
				data.put("modelId", modelId);
				data.put("slots", parsed);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			} else {
				try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取slots失败: " + responseBody));
			}
			connection.disconnect();
		} catch (Exception e) {
			logger.error("获取slots时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取slots失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	保存指定模型指定slot的缓存
	 */
	private void handleModelSlotsSave(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
			return;
		}
		String content = request.content().toString(CharsetUtil.UTF_8);
		if (content == null || content.trim().isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
			return;
		}
		JsonObject json = gson.fromJson(content, JsonObject.class);
		if (json == null) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
			return;
		}
		String modelId = json.has("modelId") ? json.get("modelId").getAsString() : null;
		Integer slotId = null;
		if (json.has("slotId")) {
			slotId = json.get("slotId").getAsInt();
		}
		String fileName = modelId + "_" + slotId + ".bin";
		ApiResponse response = LlamaServerManager.getInstance().handleModelSlotsSave(modelId, slotId.intValue(), fileName);
		// 响应消息。
		LlamaServer.sendJsonResponse(ctx, response);
	}
	
	/**
	 * 	加载指定模型指定slot的缓存
	 */
	private void handleModelSlotsLoad(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
			return;
		}
		String content = request.content().toString(CharsetUtil.UTF_8);
		if (content == null || content.trim().isEmpty()) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
			return;
		}
		JsonObject json = gson.fromJson(content, JsonObject.class);
		if (json == null) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
			return;
		}
		// 解析请求
		String modelId = json.has("modelId") ? json.get("modelId").getAsString() : null;
		Integer slotId = null;
		if (json.has("slotId")) {
			slotId = json.get("slotId").getAsInt();
		}
		String fileName = modelId + "_" + slotId.intValue() + ".bin";
		ApiResponse response = LlamaServerManager.getInstance().handleModelSlotsLoad(modelId, slotId.intValue(), fileName);
		// 响应消息。
		LlamaServer.sendJsonResponse(ctx, response);
	}
	
	private void handleModelMetrics(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.GET) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
				return;
			}
			String query = request.uri();
			String modelId = null;
			if (query.contains("?")) {
				String q = query.substring(query.indexOf("?") + 1);
				if (q.contains("modelId=")) {
					String tmp = q.substring(q.indexOf("modelId=") + 8);
					if (tmp.contains("&")) tmp = tmp.substring(0, tmp.indexOf("&"));
					modelId = URLDecoder.decode(tmp, "UTF-8");
				}
			}
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
				try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				Object parsed = gson.fromJson(responseBody, Object.class);
				Map<String, Object> data = new HashMap<>();
				data.put("modelId", modelId);
				data.put("metrics", parsed);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			} else {
				try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
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
			logger.error("获取metrics时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取metrics失败: " + e.getMessage()));
		}
	}
	
	private void handleModelProps(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.GET) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
				return;
			}
			String query = request.uri();
			String modelId = null;
			if (query.contains("?")) {
				String q = query.substring(query.indexOf("?") + 1);
				if (q.contains("modelId=")) {
					String tmp = q.substring(q.indexOf("modelId=") + 8);
					if (tmp.contains("&")) tmp = tmp.substring(0, tmp.indexOf("&"));
					modelId = URLDecoder.decode(tmp, "UTF-8");
				}
			}
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
				try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				Object parsed = gson.fromJson(responseBody, Object.class);
				Map<String, Object> data = new HashMap<>();
				data.put("modelId", modelId);
				data.put("props", parsed);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			} else {
				try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
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
			logger.error("获取props时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取props失败: " + e.getMessage()));
		}
	}
	
	
	/**
	 * 处理已加载模型请求
	 */
	private void handleLoadedModelsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 获取LlamaServerManager实例
			LlamaServerManager manager = LlamaServerManager.getInstance();
			
			// 获取已加载的进程信息
			Map<String, LlamaCppProcess> loadedProcesses = manager.getLoadedProcesses();
			
			// 获取所有模型信息
			List<GGUFModel> allModels = manager.listModel();
			
			// 构建已加载模型列表
			List<Map<String, Object>> loadedModels = new ArrayList<>();
			
			for (Map.Entry<String, LlamaCppProcess> entry : loadedProcesses.entrySet()) {
				String modelId = entry.getKey();
				LlamaCppProcess process = entry.getValue();
				
				// 查找对应的模型信息
				GGUFModel modelInfo = null;
				for (GGUFModel model : allModels) {
					if (model.getModelId().equals(modelId)) {
						modelInfo = model;
						break;
					}
				}
				
				// 构建模型信息
				Map<String, Object> modelData = new HashMap<>();
				modelData.put("id", modelId);
				modelData.put("name", modelInfo != null ?
					(modelInfo.getPrimaryModel() != null ?
					 modelInfo.getPrimaryModel().getStringValue("general.name") : "未知模型") : "未知模型");
				modelData.put("status", process.isRunning() ? "running" : "stopped");
				modelData.put("port", manager.getModelPort(modelId));
				modelData.put("pid", process.getPid());
				modelData.put("size", modelInfo != null ? modelInfo.getSize() : 0);
				modelData.put("path", modelInfo != null ? modelInfo.getPath() : "");
				
				loadedModels.add(modelData);
			}
			
			// 构建响应
			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("models", loadedModels);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("获取已加载模型时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取已加载模型失败: " + e.getMessage()));
		}
	}
	
	
	/**
	 * 处理模型列表请求
	 */
	private void handleModelListRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 获取LlamaServerManager实例并获取模型列表
			LlamaServerManager manager = LlamaServerManager.getInstance();
			List<GGUFModel> models = manager.listModel();

			// 转换为前端期望的格式
			List<Map<String, Object>> modelList = new ArrayList<>();
			for (GGUFModel model : models) {
				Map<String, Object> modelInfo = new HashMap<>();

				// 从主模型获取基本信息
				GGUFMetaData primaryModel = model.getPrimaryModel();
				GGUFMetaData mmproj = model.getMmproj();

				// 使用模型名称作为ID，如果没有名称则使用默认值
				String modelName = "未知模型";
				String modelId = "unknown-model-" + System.currentTimeMillis();

				if (primaryModel != null) {
					modelName = model.getName(); // primaryModel.getStringValue("general.name");
					if (modelName == null || modelName.trim().isEmpty()) {
						modelName = "未命名模型";
					}
					// 使用模型名称作为ID的一部分
					modelId = model.getModelId();
				}

				modelInfo.put("id", modelId);
				modelInfo.put("name", modelName);
				modelInfo.put("alias", model.getAlias());
				modelInfo.put("favourite", model.isFavourite());

				// 设置默认路径信息
				modelInfo.put("path", model.getPath());

				// 从主模型元数据中获取模型类型
				String modelType = "未知类型";
				if (primaryModel != null) {
					modelType = primaryModel.getStringValue("general.architecture");
					if (modelType == null)
						modelType = "未知类型";
				}
				modelInfo.put("type", modelType);

				// 设置默认大小为0，因为GGUFMetaData类没有提供获取文件大小的方法
				modelInfo.put("size", model.getSize());

				// 判断是否为多模态模型
				boolean isMultimodal = mmproj != null;
				modelInfo.put("isMultimodal", isMultimodal);

				// 如果是多模态模型，添加多模态投影信息
				if (isMultimodal) {
					Map<String, Object> mmprojInfo = new HashMap<>();
					mmprojInfo.put("fileName", mmproj.getFileName());
					mmprojInfo.put("name", mmproj.getStringValue("general.name"));
					mmprojInfo.put("type", mmproj.getStringValue("general.architecture"));

					modelInfo.put("mmproj", mmprojInfo);
				}
				// 是否处于加载状态
				if (manager.isLoading(modelId)) {
					modelInfo.put("isLoading", true);
				}

				// 添加元数据
				Map<String, Object> metadata = new HashMap<>();
				if (primaryModel != null) {
					String architecture = primaryModel.getStringValue("general.architecture");
					metadata.put("name", primaryModel.getStringValue("general.name"));
					metadata.put("architecture", architecture);
					metadata.put("contextLength", primaryModel.getIntValue(architecture + ".context_length"));
					metadata.put("embeddingLength", primaryModel.getIntValue(architecture + ".embedding_length"));
					metadata.put("fileType", primaryModel.getIntValue("general.file_type"));
				}
				modelInfo.put("metadata", metadata);

				modelList.add(modelInfo);
			}

			// 构建响应
			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("models", modelList);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("获取模型列表时发生错误", e);
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("success", false);
			errorResponse.put("error", "获取模型列表失败: " + e.getMessage());
			LlamaServer.sendJsonResponse(ctx, errorResponse);
		}
	}
	
	/**
	 * 估算模型显存需求
	 */
	private void handleVramEstimateRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}

			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			JsonObject json = gson.fromJson(content, JsonObject.class);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}

			String modelId = json.has("modelId") ? json.get("modelId").getAsString() : null;
			Integer ctxSize = json.has("ctxSize") ? json.get("ctxSize").getAsInt() : null;
			Integer batchSize = json.has("batchSize") ? json.get("batchSize").getAsInt() : null;
			Integer ubatchSize = json.has("ubatchSize") ? json.get("ubatchSize").getAsInt() : null;
			Boolean enableVision = json.has("enableVision") ? json.get("enableVision").getAsBoolean() : null;

			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			if (ctxSize == null || ctxSize == 0) {
				ctxSize = 2048;
			}
			if (batchSize == null || batchSize <= 0) {
				batchSize = 512;
			}
			if (ubatchSize == null || ubatchSize <= 0) {
				ubatchSize = 512;
			}
			if (enableVision == null) {
				enableVision = true;
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			// 确保模型列表已加载
			manager.listModel();
			GGUFModel model = manager.findModelById(modelId);
			if (model == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到指定模型: " + modelId));
				return;
			}

			if (model.getPrimaryModel() == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("模型元数据不完整，无法估算显存"));
				return;
			}

			VramEstimation result = VramEstimator.estimateVram(new File(model.getPrimaryModel().getFilePath()),
					ctxSize.intValue(), 16, batchSize.intValue(), ubatchSize.intValue(), enableVision.booleanValue());
			// 整合一下
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("ctxSize", ctxSize);
			data.put("bytes", result);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("估算显存时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("估算显存失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	修改别名。
	 * @param ctx
	 * @param request
	 */
	private void handleSetModelAliasRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject json = gson.fromJson(content, JsonObject.class);
			if (json == null || !json.has("modelId") || !json.has("alias")) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的参数: modelId 或 alias"));
				return;
			}
			String modelId = json.get("modelId").getAsString();
			String alias = json.get("alias").getAsString();
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("modelId不能为空"));
				return;
			}
			if (alias == null)
				alias = "";
			alias = alias.trim();
			// 更新配置文件
			ConfigManager configManager = ConfigManager.getInstance();
			boolean ok = configManager.saveModelAlias(modelId, alias);
			// 更新内存模型
			LlamaServerManager manager = LlamaServerManager.getInstance();
			GGUFModel model = manager.findModelById(modelId);
			if (model != null) {
				model.setAlias(alias);
			}
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("alias", alias);
			data.put("saved", ok);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("设置模型别名时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("设置模型别名失败: " + e.getMessage()));
		}
	}

	private void handleModelFavouriteRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject json = gson.fromJson(content, JsonObject.class);
			if (json == null || !json.has("modelId")) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的参数: modelId"));
				return;
			}
			String modelId = json.get("modelId").getAsString();
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("modelId不能为空"));
				return;
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			manager.listModel();
			GGUFModel model = manager.findModelById(modelId);
			if (model == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到指定模型: " + modelId));
				return;
			}

			boolean next = !model.isFavourite();
			model.setFavourite(next);
			ConfigManager configManager = ConfigManager.getInstance();
			boolean saved = configManager.saveModelFavourite(modelId, next);

			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("favourite", next);
			data.put("saved", saved);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("设置模型喜好时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("设置模型喜好失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	处理强制刷新模型列表请求
	 * @param ctx
	 * @param request
	 */
	private void handleRefreshModelListRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 获取LlamaServerManager实例并强制刷新模型列表
			LlamaServerManager manager = LlamaServerManager.getInstance();
			manager.listModel(true); // 传入true强制刷新
			// 刷新后回应给前端
			// 构建响应
			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			//response.put("models", modelList);
			response.put("refreshed", true); // 标识这是刷新成功
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("强制刷新模型列表时发生错误", e);
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("success", false);
			errorResponse.put("error", "强制刷新模型列表失败: " + e.getMessage());
			LlamaServer.sendJsonResponse(ctx, errorResponse);
		}
	}
	
	/**
	 * 	处理加载模型的请求
	 * @param ctx
	 * @param request
	 */
	private void handleLoadModelRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 只支持POST请求
			if (request.method() != HttpMethod.POST) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}

			// 读取请求体
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			// 解析JSON请求体为LoadModelRequest对象
			LoadModelRequest loadRequest = gson.fromJson(content, LoadModelRequest.class);

			logger.info("收到加载模型请求: {}", loadRequest);

			// 验证必需的参数
			if (loadRequest.getModelId() == null || loadRequest.getModelId().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}

			// 调用LlamaServerManager异步加载模型
			LlamaServerManager manager = LlamaServerManager.getInstance();
			ModelLaunchOptions options = ModelLaunchOptions.fromLoadRequest(loadRequest);
			boolean taskSubmitted = manager.loadModelAsync(loadRequest.getModelId(), options);

			if (taskSubmitted) {
				Map<String, Object> data = new HashMap<>();
				data.put("message", "模型加载任务已提交，请等待WebSocket通知");
				data.put("async", true);
				data.put("modelId", loadRequest.getModelId());
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("模型加载任务提交失败，可能模型已加载或不存在"));
			}
		} catch (Exception e) {
			logger.error("加载模型时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("加载模型失败: " + e.getMessage()));
		}
	}
	
	
	/**
	 * 	处理停止模型请求
	 * @param ctx
	 * @param request
	 */
	private void handleStopModelRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 只支持POST请求
			if (request.method() != HttpMethod.POST) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}

			// 读取请求体
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			// 解析JSON请求体
			StopModelRequest stopRequest = gson.fromJson(content, StopModelRequest.class);
			String modelId = stopRequest.getModelId();

			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}

			// 调用LlamaServerManager停止模型
			LlamaServerManager manager = LlamaServerManager.getInstance();
			boolean success = manager.stopModel(modelId);

			if (success) {
				Map<String, Object> data = new HashMap<>();
				data.put("message", "模型停止成功");
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
				// 发送WebSocket事件
				LlamaServer.sendModelStopEvent(modelId, true, "模型停止成功");
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("模型停止失败或模型未加载"));
				// 发送WebSocket事件
				LlamaServer.sendModelStopEvent(modelId, false, "模型停止失败或模型未加载");
			}
		} catch (Exception e) {
			logger.error("停止模型时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("停止模型失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	处理获取模型启动配置请求
	 * @param ctx
	 * @param request
	 */
	private void handleModelConfigRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.GET) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
				return;
			}
			String query = request.uri();
			String modelId = null;
			if (query.contains("?modelId=")) {
				modelId = query.substring(query.indexOf("?modelId=") + 9);
				if (modelId.contains("&")) {
					modelId = modelId.substring(0, modelId.indexOf("&"));
				}
				modelId = URLDecoder.decode(modelId, "UTF-8");
			}
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			ConfigManager configManager = ConfigManager.getInstance();
			Map<String, Object> launchConfig = configManager.getLaunchConfig(modelId);
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("config", launchConfig);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("获取模型启动配置时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型启动配置失败: " + e.getMessage()));
		}
	}
	
	private void handleModelConfigSetRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			LoadModelRequest loadRequest = gson.fromJson(content, LoadModelRequest.class);
			if (loadRequest == null || loadRequest.getModelId() == null || loadRequest.getModelId().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			ConfigManager configManager = ConfigManager.getInstance();
			Map<String, Object> currentConfig = configManager.getLaunchConfig(loadRequest.getModelId());
			if (currentConfig == null) {
				currentConfig = new HashMap<>();
			}
			ModelLaunchOptions options = ModelLaunchOptions.fromLoadRequest(loadRequest);
			Map<String, Object> updates = options.toConfigMap();
			for (Map.Entry<String, Object> entry : updates.entrySet()) {
				if (entry.getValue() != null) {
					currentConfig.put(entry.getKey(), entry.getValue());
				}
			}
			boolean saved = configManager.saveLaunchConfig(loadRequest.getModelId(), currentConfig);
			if (!saved) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存模型启动配置失败"));
				return;
			}
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", loadRequest.getModelId());
			data.put("config", currentConfig);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("设置模型启动配置时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("设置模型启动配置失败: " + e.getMessage()));
		}
	}
	
	private void handleModelDetailsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.GET) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
				return;
			}
			String query = request.uri();
			String modelId = null;
			if (query.contains("?modelId=")) {
				modelId = query.substring(query.indexOf("?modelId=") + 9);
				if (modelId.contains("&")) {
					modelId = modelId.substring(0, modelId.indexOf("&"));
				}
				modelId = URLDecoder.decode(modelId, "UTF-8");
			}
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			manager.listModel();
			GGUFModel model = manager.findModelById(modelId);
			if (model == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到指定模型: " + modelId));
				return;
			}
			Map<String, Object> metadata = new HashMap<>();
			GGUFMetaData primary = model.getPrimaryModel();
			if (primary != null) {
				Map<String, Object> m = GGUFMetaDataReader.read(new File(primary.getFilePath()));
				if (m != null) {
					m.remove("tokenizer.ggml.merges");
					m.remove("tokenizer.chat_template");
					m.remove("tokenizer.ggml.token_type");
					metadata.putAll(m);
				}
			}
			GGUFMetaData mmproj = model.getMmproj();
			if (mmproj != null) {
				Map<String, Object> m2 = GGUFMetaDataReader.read(new File(mmproj.getFilePath()));
				if (m2 != null) {
					for (Map.Entry<String, Object> e : m2.entrySet()) {
						metadata.put("mmproj." + e.getKey(), e.getValue());
					}
				}
			}
			boolean isLoaded = manager.getLoadedProcesses().containsKey(modelId);
			String startCmd = isLoaded ? manager.getModelStartCmd(modelId) : null;
			Integer port = manager.getModelPort(modelId);
			Map<String, Object> modelMap = new HashMap<>();
			String alias = model.getAlias();
			modelMap.put("name", alias != null && !alias.isEmpty() ? alias : modelId);
			modelMap.put("path", model.getPath());
			modelMap.put("size", model.getSize());
			modelMap.put("metadata", metadata);
			modelMap.put("isLoaded", isLoaded);
			if (startCmd != null && !startCmd.isEmpty()) {
				modelMap.put("startCmd", startCmd);
			}
			if (port != null) {
				modelMap.put("port", port);
			}
			Map<String, Object> response = new HashMap<>();
			response.put("model", modelMap);
			response.put("success", true);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("获取模型详情时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型详情失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	处理停止服务请求
	 * @param ctx
	 * @param request
	 */
	private void handleShutdownRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 只支持POST请求
			if (request.method() != HttpMethod.POST) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}

			logger.info("收到停止服务请求");

			// 先发送响应，然后再执行关闭操作
			Map<String, Object> data = new HashMap<>();
			data.put("message", "服务正在停止，所有模型进程将被终止");

			// 发送响应
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));

			// 在新线程中执行关闭操作，避免阻塞响应发送
			new Thread(() -> {
				try {
					// 等待一小段时间确保响应已发送
					Thread.sleep(500);

					// 调用LlamaServerManager停止所有进程并退出
					LlamaServerManager manager = LlamaServerManager.getInstance();
					manager.shutdownAll();
				} catch (Exception e) {
					logger.error("停止服务时发生错误", e);
				}
			}).start();

		} catch (Exception e) {
			logger.error("处理停止服务请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("停止服务失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	处理设置请求
	 * @param ctx
	 * @param request
	 */
	private void handleSettingRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 获取LlamaServerManager实例
			LlamaServerManager manager = LlamaServerManager.getInstance();

			if (request.method() == HttpMethod.GET) {
				// GET请求：获取当前设置
				Map<String, Object> data = new HashMap<>();
				data.put("modelPaths", manager.getModelPaths());

				Map<String, Object> response = new HashMap<>();
				response.put("success", true);
				response.put("data", data);
				LlamaServer.sendJsonResponse(ctx, response);
			} else if (request.method() == HttpMethod.POST) {
				// POST请求：保存设置
				String content = request.content().toString(CharsetUtil.UTF_8);
				if (content == null || content.trim().isEmpty()) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
					return;
				}

				// 解析JSON请求体
				JsonObject settingsJson = gson.fromJson(content, JsonObject.class);

				List<String> modelPaths = new ArrayList<>();

				if (settingsJson.has("modelPaths") && settingsJson.get("modelPaths").isJsonArray()) {
					settingsJson.get("modelPaths").getAsJsonArray().forEach(e -> {
						String p = e.getAsString();
						if (p != null && !p.trim().isEmpty())
							modelPaths.add(p.trim());
					});
				} else if (settingsJson.has("modelPath")) {
					String p = settingsJson.get("modelPath").getAsString();
					if (p != null && !p.trim().isEmpty())
						modelPaths.add(p.trim());
				}

				// 验证必需的参数
				if (modelPaths.isEmpty()) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的模型路径参数"));
					return;
				}

				// 更新设置
				manager.setModelPaths(modelPaths);

				// 保存设置到JSON文件
				LlamaServer.saveSettingsToFile(modelPaths);

				Map<String, Object> data = new HashMap<>();
				data.put("message", "设置保存成功");

				Map<String, Object> response = new HashMap<>();
				response.put("success", true);
				response.put("data", data);
				LlamaServer.sendJsonResponse(ctx, response);
			} else {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("不支持的请求方法"));
			}
		} catch (Exception e) {
			logger.error("处理设置请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("处理设置请求失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param request
	 */
	private void handleLlamaCppRemove(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject json = gson.fromJson(content, JsonObject.class);
			if (json == null || !json.has("path")) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的path参数"));
				return;
			}
			String pathStr = json.get("path").getAsString();
			if (pathStr == null || pathStr.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}

			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<String> paths = cfg.getPaths();
			int before = paths == null ? 0 : paths.size();
			if (paths != null) {
				paths.removeIf(p -> pathStr.trim().equals(p));
			}
			LlamaServer.writeLlamaCppConfig(configFile, cfg);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "移除llama.cpp路径成功");
			data.put("removed", pathStr.trim());
			data.put("count", paths == null ? 0 : paths.size());
			data.put("changed", before != (paths == null ? 0 : paths.size()));
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("移除llama.cpp路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("移除llama.cpp路径失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param request
	 */
	private void handleLlamaCppList(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.GET) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
				return;
			}
			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<String> paths = cfg.getPaths();
			Map<String, Object> data = new HashMap<>();
			data.put("paths", paths);
			data.put("count", paths == null ? 0 : paths.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("获取llama.cpp路径列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取llama.cpp路径列表失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param request
	 */
	private void handleLlamaCppAdd(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject json = gson.fromJson(content, JsonObject.class);
			if (json == null || !json.has("path")) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的path参数"));
				return;
			}
			String pathStr = json.get("path").getAsString();
			if (pathStr == null || pathStr.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}

			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<String> paths = cfg.getPaths();
			String normalized = pathStr.trim();
			if (paths.contains(normalized)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("路径已存在"));
				return;
			}
			paths.add(normalized);
			LlamaServer.writeLlamaCppConfig(configFile, cfg);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "添加llama.cpp路径成功");
			data.put("added", normalized);
			data.put("count", paths.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("添加llama.cpp路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("添加llama.cpp路径失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 
	 * @param ctx
	 * @param request
	 */
	private void handleSysConsoleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.GET) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
				return;
			}
			Path logPath = LlamaServer.getConsoleLogPath();
			File file = logPath.toFile();
			if (!file.exists()) {
				LlamaServer.sendTextResponse(ctx, "");
				return;
			}
			long max = 1L * 1024 * 1024;
			try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
				long len = raf.length();
				long start = Math.max(0, len - max);
				raf.seek(start);
				int toRead = (int) Math.min(max, len - start);
				byte[] buf = new byte[toRead];
				int read = raf.read(buf);
				if (read <= 0) {
					LlamaServer.sendTextResponse(ctx, "");
					return;
				}
				String text = new String(buf, 0, read, StandardCharsets.UTF_8);
				LlamaServer.sendTextResponse(ctx, text);
			}
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("读取控制台日志失败: " + e.getMessage()));
		}
	}
	
    
    /**
     * 	
     * @param ctx
     * @param request
     */
	private void handleModelBenchmark(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject json = gson.fromJson(content, JsonObject.class);
			if (json == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}
			String modelId = json.has("modelId") ? json.get("modelId").getAsString() : null;
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			int repetitions = json.has("repetitions") ? json.get("repetitions").getAsInt() : 3;
			String p = null;
			if (json.has("p") && !json.get("p").isJsonNull()) {
				p = json.get("p").getAsString();
			} else if (json.has("nPrompt") && !json.get("nPrompt").isJsonNull()) {
				p = json.get("nPrompt").getAsString();
			}
			if (p != null) {
				p = p.trim();
				if (p.isEmpty()) {
					p = null;
				}
			}
			String n = null;
			if (json.has("n") && !json.get("n").isJsonNull()) {
				n = json.get("n").getAsString();
			} else if (json.has("nGen") && !json.get("nGen").isJsonNull()) {
				n = json.get("nGen").getAsString();
			}
			if (n != null) {
				n = n.trim();
				if (n.isEmpty()) {
					n = null;
				}
			}
			String t = null;
			if (json.has("t") && !json.get("t").isJsonNull()) {
				t = json.get("t").getAsString();
			} else if (json.has("threads") && !json.get("threads").isJsonNull()) {
				t = json.get("threads").getAsString();
			}
			if (t != null) {
				t = t.trim();
				if (t.isEmpty()) {
					t = null;
				}
			}
			String batchSize = null;
			if (json.has("batchSize") && !json.get("batchSize").isJsonNull()) {
				batchSize = json.get("batchSize").getAsString();
				if (batchSize != null) {
					batchSize = batchSize.trim();
					if (batchSize.isEmpty()) {
						batchSize = null;
					}
				}
			}
			String ubatchSize = null;
			if (json.has("ubatchSize") && !json.get("ubatchSize").isJsonNull()) {
				ubatchSize = json.get("ubatchSize").getAsString();
				if (ubatchSize != null) {
					ubatchSize = ubatchSize.trim();
					if (ubatchSize.isEmpty()) {
						ubatchSize = null;
					}
				}
			}
			String pg = null;
			if (json.has("pg") && !json.get("pg").isJsonNull()) {
				pg = json.get("pg").getAsString();
				if (pg != null) {
					pg = pg.trim();
					if (pg.isEmpty()) {
						pg = null;
					}
				}
			}
			String fa = null;
			if (json.has("fa") && !json.get("fa").isJsonNull()) {
				fa = json.get("fa").getAsString();
				if (fa != null) {
					fa = fa.trim();
					if (fa.isEmpty()) {
						fa = null;
					}
				}
			}
			String mmp = null;
			if (json.has("mmp") && !json.get("mmp").isJsonNull()) {
				mmp = json.get("mmp").getAsString();
				if (mmp != null) {
					mmp = mmp.trim();
					if (mmp.isEmpty()) {
						mmp = null;
					}
				}
			}
			String extraParams = null;
			if (json.has("extraParams") && !json.get("extraParams").isJsonNull()) {
				extraParams = json.get("extraParams").getAsString();
				if (extraParams != null) {
					extraParams = extraParams.trim();
					if (extraParams.isEmpty()) {
						extraParams = null;
					}
				}
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
			if (repetitions <= 0) {
				repetitions = 1;
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
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("llama-bench可执行文件不存在: " + benchFile.getAbsolutePath()));
				return;
			}
			List<String> command = new ArrayList<>();
			command.add(benchFile.getAbsolutePath());
			command.add("-m");
			command.add(modelPath);
			command.add("-r");
			command.add(String.valueOf(repetitions));
			if (p != null) {
				command.add("-p");
				command.add(p);
			}
			if (n != null) {
				command.add("-n");
				command.add(n);
			}
			if (batchSize != null) {
				command.add("-b");
				command.add(batchSize);
			}
			if (ubatchSize != null) {
				command.add("-ub");
				command.add(ubatchSize);
			}
			if (t != null) {
				command.add("-t");
				command.add(t);
			}
			if (pg != null) {
				command.add("-pg");
				command.add(pg);
			}
			if (fa != null) {
				command.add("-fa");
				command.add(fa);
			}
			if (mmp != null) {
				command.add("-mmp");
				command.add(mmp);
			}
			if (extraParams != null && !extraParams.isEmpty()) {
				String[] parts = extraParams.split("\\s+");
				for (String part : parts) {
					if (!part.isEmpty()) {
						command.add(part);
					}
				}
			}
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
					if (currentLdPath != null && !currentLdPath.isEmpty()) {
						env.put("LD_LIBRARY_PATH", libPath + ":" + currentLdPath);
					} else {
						env.put("LD_LIBRARY_PATH", libPath);
					}
				}
			}
			Process process = pb.start();
			StringBuilder output = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
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
						fileContent.append("command: ").append(commandStr).append(System.lineSeparator()).append(System.lineSeparator());
						fileContent.append(text);
						fos.write(fileContent.toString().getBytes(StandardCharsets.UTF_8));
					}
					data.put("savedPath", outFile.getAbsolutePath());
				} catch (Exception ex) {
					logger.warn("保存基准测试结果到文件失败", ex);
				}
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("执行模型基准测试时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("执行模型基准测试失败: " + e.getMessage()));
		}
	}
	
	private void handleModelBenchmarkList(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.GET) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
				return;
			}
			String query = request.uri();
			String modelId = null;
			if (query.contains("?modelId=")) {
				modelId = query.substring(query.indexOf("?modelId=") + 9);
				if (modelId.contains("&")) {
					modelId = modelId.substring(0, modelId.indexOf("&"));
				}
				modelId = URLDecoder.decode(modelId, "UTF-8");
			}
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
							info.put("modified", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(f.lastModified())));
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
	
	private void handleModelBenchmarkGet(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.GET) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
				return;
			}
			String query = request.uri();
			String fileName = null;
			if (query.contains("?fileName=")) {
				fileName = query.substring(query.indexOf("?fileName=") + 10);
				if (fileName.contains("&")) {
					fileName = fileName.substring(0, fileName.indexOf("&"));
				}
				fileName = URLDecoder.decode(fileName, "UTF-8");
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

	private void handleModelBenchmarkDelete(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}
			String query = request.uri();
			String fileName = null;
			if (query.contains("?fileName=")) {
				fileName = query.substring(query.indexOf("?fileName=") + 10);
				if (fileName.contains("&")) {
					fileName = fileName.substring(0, fileName.indexOf("&"));
				}
				fileName = URLDecoder.decode(fileName, "UTF-8");
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
    
    
	
	
	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		logger.info("客户端连接关闭：{}", ctx);
		// 事件通知
		ctx.fireChannelInactive();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		logger.error("处理请求时发生异常", cause);
		ctx.close();
		
		ctx.fireExceptionCaught(cause);
	}

}
