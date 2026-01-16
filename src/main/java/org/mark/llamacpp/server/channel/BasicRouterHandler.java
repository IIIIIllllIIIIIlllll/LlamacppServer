package org.mark.llamacpp.server.channel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.URL;
import java.net.URLDecoder;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFMetaDataReader;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.crawler.HuggingFaceModelCrawler;
import org.mark.llamacpp.server.ConfigManager;
import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.LlamaCppConfig;
import org.mark.llamacpp.server.struct.LlamaCppDataStruct;
import org.mark.llamacpp.server.struct.ModelPathConfig;
import org.mark.llamacpp.server.struct.ModelPathDataStruct;
import org.mark.llamacpp.server.struct.StopModelRequest;
import org.mark.llamacpp.server.tools.CommandLineRunner;
import org.mark.llamacpp.server.tools.ChatTemplateFileTool;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.VramEstimator;
import org.mark.llamacpp.server.tools.struct.VramEstimation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;

/**
 * 基本路由处理器。 实现本项目用到的API端点。
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
		//logger.info("收到请求: {} {}", request.method().name(), uri);
		// 傻逼浏览器不知道为什么一直在他妈的访问/.well-known/appspecific/com.chrome.devtools.json
		if ("/.well-known/appspecific/com.chrome.devtools.json".equals(uri)) {
			ctx.close();
			return;
		}

		// 处理模型API请求
		if (uri.startsWith("/api/") || uri.startsWith("/v1") || uri.startsWith("/session")) {
			try {
				this.handleApiRequest(ctx, request, uri);
			} catch (Exception e) {
				// 错误响应
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
				e.printStackTrace();
			}
			return;
		}

		try {
			// 断言一下请求方式
			this.assertRequestMethod(request.method() != HttpMethod.GET, "仅支持GET请求");
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
			// 
			if(path.indexOf('?') > 0) {
				path = path.substring(0, path.indexOf('?'));
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
		} catch (RequestMethodException e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		} catch (Exception e) {
			logger.error("处理静态文件请求时发生错误", e);
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
		}
	}

	/**
	 * 处理API请求。
	 * 
	 * @param ctx
	 * @param request
	 * @param uri
	 * @throws
	 */
	private void handleApiRequest(ChannelHandlerContext ctx, FullHttpRequest request, String uri) throws RequestMethodException {
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
		// 列出可用的参数API
		if (uri.startsWith("/api/models/param/server/list")) {
			this.handleParamListRequest(ctx, request);
			return;
		}
		// 列出可用的参数API
		if (uri.startsWith("/api/models/param/benchmark/list")) {
			this.handleParamBenchmarkListRequest(ctx, request);
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
		// 获取偏好模型的API
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
		// 查询指定模型启动参数的API
		if (uri.startsWith("/api/models/config/get")) {
			this.handleModelConfigRequest(ctx, request);
			return;
		}
		// 获取指定模型详情的API
		if (uri.startsWith("/api/models/details")) {
			this.handleModelDetailsRequest(ctx, request);
			return;
		}
		// 用于更新启动参数的API
		if (uri.startsWith("/api/models/config/set")) {
			this.handleModelConfigSetRequest(ctx, request);
			return;
		}
		// 查询对应模型的/solts的API
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
		if (uri.startsWith("/api/models/metrics")) {
			this.handleModelMetrics(ctx, request);
			return;
		}
		// 对应URL-GET：/props
		if (uri.startsWith("/api/models/props")) {
			this.handleModelProps(ctx, request);
			return;
		}
		// 列出可用的设备，基于当前选择的llamacpp
		if (uri.startsWith("/api/model/device/list")) {
			this.handleDeviceListRequest(ctx, request);
			return;
		}
		
		// 
		if (uri.startsWith("/api/model/template/get")) {
			this.handleModelTemplateGetRequest(ctx, request);
			return;
		}
		
		
		if (uri.startsWith("/api/model/template/set")) {
			this.handleModelTemplateSetRequest(ctx, request);
			return;
		}

		if (uri.startsWith("/api/model/template/default")) {
			this.handleModelTemplateDefaultRequest(ctx, request);
			return;
		}
		
		// 停止服务API
		if (uri.startsWith("/api/shutdown")) {
			this.handleShutdownRequest(ctx, request);
			return;
		}
		if (uri.startsWith("/api/hf/search")) {
			this.handleHfSearchRequest(ctx, request);
			return;
		}
		if (uri.startsWith("/api/hf/gguf")) {
			this.handleHfGgufRequest(ctx, request);
			return;
		}
		// ==============================================================
		
		
		if (uri.startsWith("/api/model/path/add")) {
			this.handleModelPathAdd(ctx, request);
			return;
		}

		if (uri.startsWith("/api/model/path/remove")) {
			this.handleModelPathRemove(ctx, request);
			return;
		}

		if (uri.startsWith("/api/model/path/update")) {
			this.handleModelPathUpdate(ctx, request);
			return;
		}

		if (uri.startsWith("/api/model/path/list")) {
			this.handleModelPathList(ctx, request);
			return;
		}


		// ==============================================================
		// 添加一个llamacpp
		if (uri.startsWith("/api/llamacpp/add")) {
			this.handleLlamaCppAdd(ctx, request);
			return;
		}
		// 移除
		if (uri.startsWith("/api/llamacpp/remove")) {
			this.handleLlamaCppRemove(ctx, request);
			return;
		}
		// 列出全部
		if (uri.startsWith("/api/llamacpp/list")) {
			this.handleLlamaCppList(ctx, request);
			return;
		}
		// 执行测试
		if (uri.startsWith("/api/llamacpp/test")) {
			this.handleLlamaCppTest(ctx, request);
			return;
		}
		// ==============================================================
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
		// ==============================================================
		// 计算参数API
		if (uri.startsWith("/api/models/fit/params")) {
			// TODO
			
		}
		ctx.fireChannelRead(request.retain());
	}
	
	
	/**
	 * 	处理HF搜索请求。
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleHfSearchRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = this.getQueryParam(request.uri());
			String query = params.get("query");
			if (query == null || query.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的query参数"));
				return;
			}
			int limit = parseIntOrDefault(params.get("limit"), 30);
			int timeoutSeconds = parseIntOrDefault(params.get("timeoutSeconds"), 20);
			int startPage = parseIntOrDefault(params.get("startPage"), 0);
			int maxPages = parseIntOrDefault(params.get("maxPages"), 0);
			String base = firstNonBlank(params.get("base"), params.get("baseUrl"), params.get("host"));

			var result = HuggingFaceModelCrawler.searchModels(query.trim(), limit, timeoutSeconds, startPage, maxPages, base);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(result));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求被中断: " + e.getMessage()));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("搜索失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	处理HF模型信息请求
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleHfGgufRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = this.getQueryParam(request.uri());
			String input = firstNonBlank(params.get("model"), params.get("repoId"), params.get("modelUrl"), params.get("url"),
					params.get("input"));
			if (input == null || input.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的model参数"));
				return;
			}
			int timeoutSeconds = parseIntOrDefault(params.get("timeoutSeconds"), 20);
			String base = firstNonBlank(params.get("base"), params.get("baseUrl"), params.get("host"));
			var result = HuggingFaceModelCrawler.crawlGGUFFiles(input.trim(), timeoutSeconds, base);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(result));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求被中断: " + e.getMessage()));
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("解析GGUF失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	配合{@link #handleHfGgufRequest(ChannelHandlerContext, FullHttpRequest)}
	 * @param value
	 * @param fallback
	 * @return
	 */
	private static int parseIntOrDefault(String value, int fallback) {
		if (value == null)
			return fallback;
		String s = value.trim();
		if (s.isEmpty())
			return fallback;
		try {
			return Integer.parseInt(s);
		} catch (Exception e) {
			return fallback;
		}
	}
	
	/**
	 * 	配合{@link #handleHfGgufRequest(ChannelHandlerContext, FullHttpRequest)}
	 * @param values
	 * @return
	 */
	private static String firstNonBlank(String... values) {
		if (values == null)
			return null;
		for (String v : values) {
			if (v == null)
				continue;
			String s = v.trim();
			if (!s.isEmpty())
				return s;
		}
		return null;
	}

	/**
	 * 获取指定模型的slots信息
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelSlotsGet(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

		try {
			String query = request.uri();
			String modelId = null;
			Map<String, String> params = this.getQueryParam(query);
			modelId = params.get("modelId");
			
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			// 调别的实现然后响应
			ApiResponse response = LlamaServerManager.getInstance().handleModelSlotsGet(modelId);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("获取模型slots信息时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型slots信息失败: " + e.getMessage()));
		}
	}

	/**
	 * 保存指定模型指定slot的缓存
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelSlotsSave(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
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
			ApiResponse response = LlamaServerManager.getInstance().handleModelSlotsSave(modelId, slotId.intValue(),
					fileName);
			// 响应消息。
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("保存模型slots缓存时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存模型slots缓存失败: " + e.getMessage()));
		}
	}

	/**
	 * 加载指定模型指定slot的缓存
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelSlotsLoad(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
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
			ApiResponse response = LlamaServerManager.getInstance().handleModelSlotsLoad(modelId, slotId.intValue(),
					fileName);
			// 响应消息。
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("加载模型slots缓存时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("加载模型slots缓存失败: " + e.getMessage()));
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
			Map<String, String> params = this.getQueryParam(query);
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
				Object parsed = gson.fromJson(responseBody, Object.class);
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
			logger.error("获取metrics时发生错误", e);
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
			Map<String, String> params = this.getQueryParam(query);
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
				Object parsed = gson.fromJson(responseBody, Object.class);
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
			logger.error("获取props时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取props失败: " + e.getMessage()));
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
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

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
				modelData.put("name",
						modelInfo != null ? (modelInfo.getPrimaryModel() != null
								? modelInfo.getPrimaryModel().getStringValue("general.name")
								: "未知模型") : "未知模型");
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
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

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
	 * 处理参数列表请求
	 * 返回 server-params.json 文件的全部内容
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleParamListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

		try {
			// 从 resources 目录读取 server-params.json 文件
			InputStream inputStream = getClass().getClassLoader().getResourceAsStream("server-params.json");
			if (inputStream == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("参数配置文件不存在: server-params.json"));
				return;
			}
			
			// 读取文件内容
			String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			inputStream.close();
			
			// 解析为JSON对象并验证格式
			Object parsed = gson.fromJson(content, Object.class);
			
			// 构建响应
			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("params", parsed);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("获取参数列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取参数列表失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	获取benchmark列表。
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleParamBenchmarkListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");

		try {
			// 从 resources 目录读取 benchmark-params.json 文件
			InputStream inputStream = getClass().getClassLoader().getResourceAsStream("benchmark-params.json");
			if (inputStream == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("参数配置文件不存在: benchmark-params.json"));
				return;
			}

			String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
			inputStream.close();
			Object parsed = gson.fromJson(content, Object.class);

			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("params", parsed);
			LlamaServer.sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("获取基准测试参数列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取基准测试参数列表失败: " + e.getMessage()));
		}
	}

	/**
	 * 估算模型显存需求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleVramEstimateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		
		try {
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
			Integer ctxSize = json.has("param_ctx-size") ? json.get("param_ctx-size").getAsInt()
					: (json.has("ctxSize") ? json.get("ctxSize").getAsInt() : null);
			String cacheTypeKStr = json.has("param_cache-type-k") ? json.get("param_cache-type-k").getAsString() : null;
			String cacheTypeVStr = json.has("param_cache-type-v") ? json.get("param_cache-type-v").getAsString() : null;
			Object flashAttnObj = json.has("param_flash-attn") ? json.get("param_flash-attn")
					: (json.has("flashAttention") ? json.get("flashAttention") : null);

			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			if (ctxSize == null || ctxSize == 0) {
				ctxSize = 2048;
			}
			if (cacheTypeKStr == null || cacheTypeKStr.trim().isEmpty()) {
				cacheTypeKStr = "f16";
			}
			if (cacheTypeVStr == null || cacheTypeVStr.trim().isEmpty()) {
				cacheTypeVStr = cacheTypeKStr;
			}
			boolean flashAttention = true;
			if (flashAttnObj != null) {
				String raw = null;
				try {
					if (flashAttnObj instanceof JsonElement) {
						JsonElement el = (JsonElement) flashAttnObj;
						if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
							flashAttention = el.getAsBoolean();
						} else {
							raw = el.getAsString();
						}
					}
				} catch (Exception ignore) {
					raw = null;
				}
				if (raw != null) {
					String v = raw.trim().toLowerCase(Locale.ROOT);
					flashAttention = !(v.equals("off") || v.equals("0") || v.equals("false"));
				}
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

			VramEstimator.KvCacheType kvK = VramEstimator.KvCacheType.from(cacheTypeKStr);
			VramEstimator.KvCacheType kvV = VramEstimator.KvCacheType.from(cacheTypeVStr);
			
			VramEstimator.Estimate est = VramEstimator.estimate(
					new File(model.getPrimaryModel().getFilePath()),
					ctxSize.intValue(), 
					kvK, 
					kvV, 
					flashAttention);
			
			VramEstimation result = new VramEstimation(
					est.totalBytes(), 
					est.modelWeightsBytes(),
					est.kvCacheBytes(), 
					est.runtimeOverheadBytes());
			// 整合一下
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("param_ctx-size", ctxSize);
			data.put("param_cache-type-k", kvK.id());
			data.put("param_cache-type-v", kvV.id());
			data.put("param_flash-attn", flashAttention);
			data.put("bytes", result);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("估算显存时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("估算显存失败: " + e.getMessage()));
		}
	}

	/**
	 * 修改别名。
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleSetModelAliasRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
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

	/**
	 * 偏好模型的请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelFavouriteRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");

		try {
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
	 * 处理强制刷新模型列表请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleRefreshModelListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持POST请求");
		try {
			// 获取LlamaServerManager实例并强制刷新模型列表
			LlamaServerManager manager = LlamaServerManager.getInstance();
			manager.listModel(true); // 传入true强制刷新
			// 刷新后回应给前端
			// 构建响应
			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			// response.put("models", modelList);
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
	 * 处理加载模型的请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleLoadModelRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			// 读取请求体
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			JsonElement root = gson.fromJson(content, JsonElement.class);
			if (root == null || !root.isJsonObject()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体必须为JSON对象"));
				return;
			}

			JsonObject obj = root.getAsJsonObject();
			String cmd = JsonUtil.getJsonString(obj, "cmd", "");
			if (cmd == null || cmd.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的cmd参数"));
				return;
			}
			cmd = cmd.trim();
			boolean enableVision = parseJsonBoolean(obj, "enableVision", true);
			if (!enableVision) {
				cmd = sanitizeCmdDisableVision(cmd);
			}

			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			String modelNameCmd = JsonUtil.getJsonString(obj, "modelName", null);
			String llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPathSelect", null);
			if (llamaBinPathSelect == null || llamaBinPathSelect.trim().isEmpty()) {
				llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPath", null);
			}
			List<String> device = JsonUtil.getJsonStringList(obj.get("device"));
			Integer mg = JsonUtil.getJsonInt(obj, "mg", null);

			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			if (llamaBinPathSelect == null || llamaBinPathSelect.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未提供llamaBinPath"));
				return;
			}
			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (manager.getLoadedProcesses().containsKey(modelId)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("模型已经加载"));
				return;
			}
			if (manager.isLoading(modelId)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("该模型正在加载中"));
				return;
			}
			if (manager.findModelById(modelId) == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到ID为 " + modelId + " 的模型"));
				return;
			}

			try {
				ConfigManager cfgManager = ConfigManager.getInstance();
				Map<String, Map<String, Object>> all = cfgManager.loadAllLaunchConfigs();
				Map<String, Object> prev = all.get(modelId);
				Map<String, Object> merged = prev != null ? new HashMap<>(prev) : new HashMap<>();
				merged.put("llamaBinPath", llamaBinPathSelect);
				merged.put("mg", mg);
				merged.put("cmd", cmd);
				merged.put("device", device);
				merged.put("enableVision", enableVision);
				normalizeEnableVisionInConfigMap(merged);
				cfgManager.saveLaunchConfig(modelId, merged);
			} catch (Exception ignore) {
			}
			//
			String chatTemplateFilePath = ChatTemplateFileTool.getChatTemplateCacheFilePathIfExists(modelId);

			boolean started = manager.loadModelAsyncFromCmd(modelId, llamaBinPathSelect, device, mg, cmd, chatTemplateFilePath);
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
			data.put("enableVision", enableVision);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("加载模型时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("加载模型失败: " + e.getMessage()));
		}
	}

	/**
	 * 处理停止模型请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleStopModelRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
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
	 * 处理获取模型启动配置请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelConfigRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");		
		try {
			String query = request.uri();
			String modelId = null;
			Map<String, String> params = this.getQueryParam(query);
			modelId = params.get("modelId");

			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			ConfigManager configManager = ConfigManager.getInstance();
			// 取出指定模型的启动参数
			Map<String, Map<String, Object>> allConfigs = configManager.loadAllLaunchConfigs();
			Map<String, Object> launchConfig = allConfigs.get(modelId);
			if (launchConfig == null) {
				launchConfig = new HashMap<>();
			}
			// 
			Map<String, Object> data = new HashMap<>();
			data.put(modelId, launchConfig);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("获取模型启动配置时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型启动配置失败: " + e.getMessage()));
		}
	}

	private void handleModelTemplateGetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = this.getQueryParam(request.uri());
			String modelId = params.get("modelId");
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			String chatTemplate = ChatTemplateFileTool.readChatTemplateFromCacheFile(modelId);
			String filePath = ChatTemplateFileTool.getChatTemplateCacheFilePathIfExists(modelId);
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("exists", filePath != null && !filePath.isEmpty());
			if (filePath != null && !filePath.isEmpty()) {
				data.put("filePath", filePath);
			}
			data.put("chatTemplate", chatTemplate);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("获取模型聊天模板时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型聊天模板失败: " + e.getMessage()));
		}
	}

	private void handleModelTemplateSetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject obj = gson.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}
			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			if (modelId == null || modelId.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}
			String chatTemplate = JsonUtil.getJsonString(obj, "chatTemplate", null);
			if (chatTemplate == null) chatTemplate = JsonUtil.getJsonString(obj, "template", null);
			if (chatTemplate == null) chatTemplate = JsonUtil.getJsonString(obj, "content", null);
			if (chatTemplate == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的chatTemplate参数"));
				return;
			}

			boolean deleted = false;
			String filePath = null;
			if (chatTemplate.trim().isEmpty()) {
				deleted = ChatTemplateFileTool.deleteChatTemplateCacheFile(modelId);
			} else {
				filePath = ChatTemplateFileTool.writeChatTemplateToCacheFile(modelId, chatTemplate);
			}

			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("deleted", deleted);
			if (filePath != null && !filePath.isEmpty()) {
				data.put("filePath", filePath);
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("设置模型聊天模板时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("设置模型聊天模板失败: " + e.getMessage()));
		}
	}

	private void handleModelTemplateDefaultRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = this.getQueryParam(request.uri());
			String modelId = params.get("modelId");
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

			boolean exists = false;
			String chatTemplate = "";
			GGUFMetaData primary = model.getPrimaryModel();
			if (primary != null) {
				Map<String, Object> m = GGUFMetaDataReader.read(new File(primary.getFilePath()));
				if (m != null) {
					Object tpl = m.get("tokenizer.chat_template");
					if (tpl != null) {
						exists = true;
						chatTemplate = String.valueOf(tpl);
					}
				}
			}

			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("exists", exists);
			data.put("chatTemplate", chatTemplate);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("获取模型默认聊天模板时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型默认聊天模板失败: " + e.getMessage()));
		}
	}

	/**
	 * 设置模型的启动参数
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelConfigSetRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			ConfigManager configManager = ConfigManager.getInstance();
			JsonElement root = gson.fromJson(content, JsonElement.class);
			if (root == null || !root.isJsonObject()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体必须为JSON对象"));
				return;
			}
			JsonObject obj = root.getAsJsonObject();
			Type mapType = new TypeToken<Map<String, Object>>() {}.getType();

			Map<String, Object> savedData = new HashMap<>();

			if (obj.has("modelId")) {
				String modelId = obj.get("modelId").getAsString();
				if (modelId == null || modelId.trim().isEmpty()) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
					return;
				}
				JsonElement cfgEl = obj.has("config") ? obj.get("config") : obj;
				Map<String, Object> cfgMap = gson.fromJson(cfgEl, mapType);
				if (cfgMap == null) cfgMap = new HashMap<>();
				cfgMap.remove("modelId");
				cfgMap.remove("config");
				if (cfgMap.containsKey("chatTemplate")) {
					Object v = cfgMap.get("chatTemplate");
					String s = v == null ? "" : String.valueOf(v);
					if (s.trim().isEmpty()) {
						ChatTemplateFileTool.deleteChatTemplateCacheFile(modelId);
					} else {
						ChatTemplateFileTool.writeChatTemplateToCacheFile(modelId, s);
					}
					cfgMap.remove("chatTemplate");
				}
				normalizeEnableVisionInConfigMap(cfgMap);
				boolean saved = configManager.saveLaunchConfig(modelId, cfgMap);
				if (!saved) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存模型启动配置失败"));
					return;
				}
				savedData.put(modelId, cfgMap);
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(savedData));
				return;
			}

			for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
				String modelId = e.getKey();
				if (modelId == null || modelId.trim().isEmpty()) continue;
				JsonElement cfgEl = e.getValue();
				if (cfgEl == null || cfgEl.isJsonNull()) continue;
				if (!cfgEl.isJsonObject()) continue;
				Map<String, Object> cfgMap = gson.fromJson(cfgEl, mapType);
				if (cfgMap == null) cfgMap = new HashMap<>();
				if (cfgMap.containsKey("chatTemplate")) {
					Object v = cfgMap.get("chatTemplate");
					String s = v == null ? "" : String.valueOf(v);
					if (s.trim().isEmpty()) {
						ChatTemplateFileTool.deleteChatTemplateCacheFile(modelId);
					} else {
						ChatTemplateFileTool.writeChatTemplateToCacheFile(modelId, s);
					}
					cfgMap.remove("chatTemplate");
				}
				normalizeEnableVisionInConfigMap(cfgMap);
				boolean saved = configManager.saveLaunchConfig(modelId, cfgMap);
				if (!saved) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存模型启动配置失败"));
					return;
				}
				savedData.put(modelId, cfgMap);
			}

			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(savedData));
		} catch (Exception e) {
			logger.error("设置模型启动配置时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("设置模型启动配置失败: " + e.getMessage()));
		}
	}

	/**
	 * 处理器模型详情的请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleModelDetailsRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		
		try {
			String query = request.uri();
			String modelId = null;
			Map<String, String> params = this.getQueryParam(query);
			modelId = params.get("modelId");
			
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
					//m.remove("tokenizer.chat_template");
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
	 * 处理停止服务请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleShutdownRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		
		try {
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
	 * 移除一个llamcpp目录
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleLlamaCppRemove(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			LlamaCppDataStruct reqData = gson.fromJson(content, LlamaCppDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}
			String normalized = reqData.getPath().trim();

			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<LlamaCppDataStruct> items = cfg.getItems();
			int before = items == null ? 0 : items.size();
			boolean changed = false;
			if (items != null) {
				changed = items.removeIf(i -> normalized.equals(i == null || i.getPath() == null ? "" : i.getPath().trim()));
			}
			LlamaServer.writeLlamaCppConfig(configFile, cfg);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "移除llama.cpp路径成功");
			data.put("removed", normalized);
			data.put("count", items == null ? 0 : items.size());
			data.put("changed", changed || before != (items == null ? 0 : items.size()));
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("移除llama.cpp路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("移除llama.cpp路径失败: " + e.getMessage()));
		}
	}

	/**
	 * 返回全部的llamacpp目录
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleLlamaCppList(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<LlamaCppDataStruct> items = cfg.getItems();
			// 扫描一遍，加入新的。
			List<LlamaCppDataStruct> list = LlamaServer.scanLlamaCpp();
			if(list != null && list.size() > 0)
				items.addAll(list);
			
			Map<String, Object> data = new HashMap<>();
			data.put("items", items);
			data.put("count", items == null ? 0 : items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("获取llama.cpp路径列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取llama.cpp路径列表失败: " + e.getMessage()));
		}
	}

	private void handleLlamaCppTest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			LlamaCppDataStruct reqData = gson.fromJson(content, LlamaCppDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}

			String llamaBinPath = reqData.getPath().trim();
			String exeName = "llama-cli";
			File exeFile = new File(llamaBinPath, exeName);
			if (!exeFile.exists() || !exeFile.isFile()) {
				String osName = System.getProperty("os.name");
				String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
				if (os.contains("win")) {
					File exeFileWin = new File(llamaBinPath, exeName + ".exe");
					if (exeFileWin.exists() && exeFileWin.isFile()) {
						exeFile = exeFileWin;
					}
				}
			}
			if (!exeFile.exists() || !exeFile.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("llama-cli可执行文件不存在: " + exeFile.getAbsolutePath()));
				return;
			}

			String cmdVersion = quoteIfNeeded(exeFile.getAbsolutePath()) + " --version";
			CommandLineRunner.CommandResult versionResult = CommandLineRunner.execute(
					new String[] { exeFile.getAbsolutePath(), "--version" }, 30);

			String cmdListDevices = quoteIfNeeded(exeFile.getAbsolutePath()) + " --list-devices";
			CommandLineRunner.CommandResult listDevicesResult = CommandLineRunner.execute(
					new String[] { exeFile.getAbsolutePath(), "--list-devices" }, 30);

			Map<String, Object> data = new HashMap<>();

			Map<String, Object> version = new HashMap<>();
			version.put("command", cmdVersion);
			version.put("exitCode", versionResult.getExitCode());
			version.put("output", versionResult.getOutput());
			version.put("error", versionResult.getError());

			Map<String, Object> devices = new HashMap<>();
			devices.put("command", cmdListDevices);
			devices.put("exitCode", listDevicesResult.getExitCode());
			devices.put("output", listDevicesResult.getOutput());
			devices.put("error", listDevicesResult.getError());

			data.put("version", version);
			data.put("listDevices", devices);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("执行llama.cpp测试命令时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("执行llama.cpp测试失败: " + e.getMessage()));
		}
	}

	/**
	 * 添加模型路径
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelPathAdd(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			ModelPathDataStruct reqData = gson.fromJson(content, ModelPathDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			Path configFile = LlamaServer.getModelPathConfigPath();
			ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
			cfg = this.ensureModelPathConfigInitialized(cfg, manager.getModelPaths(), configFile);
			List<ModelPathDataStruct> items = cfg.getItems();
			if (items == null) {
				items = new ArrayList<>();
				cfg.setItems(items);
			}
			String normalized = reqData.getPath().trim();
			boolean exists = false;
			for (ModelPathDataStruct i : items) {
				if (i != null && i.getPath() != null && this.isSamePath(normalized, i.getPath().trim())) {
					exists = true;
					break;
				}
			}
			if (exists) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("路径已存在"));
				return;
			}
			ModelPathDataStruct item = new ModelPathDataStruct();
			item.setPath(normalized);
			String name = reqData.getName();
			if (name == null || name.trim().isEmpty()) {
				try {
					name = java.nio.file.Paths.get(normalized).getFileName().toString();
				} catch (Exception ex) {
					name = normalized;
				}
			}
			item.setName(name);
			item.setDescription(reqData.getDescription());
			items.add(item);
			LlamaServer.writeModelPathConfig(configFile, cfg);
			this.syncModelPathsToRuntime(manager, cfg, true);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "添加模型路径成功");
			data.put("added", item);
			data.put("count", items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("添加模型路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("添加模型路径失败: " + e.getMessage()));
		}
	}

	/**
	 * 移除模型路径
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelPathRemove(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			ModelPathDataStruct reqData = gson.fromJson(content, ModelPathDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}
			String normalized = reqData.getPath().trim();

			LlamaServerManager manager = LlamaServerManager.getInstance();
			Path configFile = LlamaServer.getModelPathConfigPath();
			ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
			cfg = this.ensureModelPathConfigInitialized(cfg, manager.getModelPaths(), configFile);
			List<ModelPathDataStruct> items = cfg.getItems();
			int before = items == null ? 0 : items.size();
			boolean changed = false;
			if (items != null) {
				changed = items.removeIf(i -> this.isSamePath(normalized, i == null || i.getPath() == null ? "" : i.getPath().trim()));
			}

			LlamaServer.writeModelPathConfig(configFile, cfg);
			this.syncModelPathsToRuntime(manager, cfg, true);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "移除模型路径成功");
			data.put("removed", normalized);
			data.put("count", items == null ? 0 : items.size());
			data.put("changed", changed || before != (items == null ? 0 : items.size()));
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("移除模型路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("移除模型路径失败: " + e.getMessage()));
		}
	}

	/**
	 * 更新模型路径（原地修改）
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelPathUpdate(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject obj = gson.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}
			String originalPath = obj.has("originalPath") ? obj.get("originalPath").getAsString() : null;
			String newPath = obj.has("path") ? obj.get("path").getAsString() : null;
			String name = obj.has("name") ? obj.get("name").getAsString() : null;
			String description = obj.has("description") ? obj.get("description").getAsString() : null;

			if (originalPath == null || originalPath.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("originalPath不能为空"));
				return;
			}
			if (newPath == null || newPath.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}

			String originalNormalized = originalPath.trim();
			String newNormalized = newPath.trim();

			LlamaServerManager manager = LlamaServerManager.getInstance();
			Path configFile = LlamaServer.getModelPathConfigPath();
			ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
			cfg = this.ensureModelPathConfigInitialized(cfg, manager.getModelPaths(), configFile);

			List<ModelPathDataStruct> items = cfg.getItems();
			if (items == null || items.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到可更新的路径配置"));
				return;
			}

			ModelPathDataStruct target = null;
			for (ModelPathDataStruct i : items) {
				if (i == null || i.getPath() == null) continue;
				if (this.isSamePath(originalNormalized, i.getPath().trim())) {
					target = i;
					break;
				}
			}
			if (target == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到要更新的路径: " + originalNormalized));
				return;
			}

			boolean pathChanged = !this.isSamePath(originalNormalized, newNormalized);
			if (pathChanged) {
				for (ModelPathDataStruct i : items) {
					if (i == null || i.getPath() == null) continue;
					if (i == target) continue;
					if (this.isSamePath(newNormalized, i.getPath().trim())) {
						LlamaServer.sendJsonResponse(ctx, ApiResponse.error("路径已存在"));
						return;
					}
				}
			}

			target.setPath(newNormalized);
			if (name == null || name.trim().isEmpty()) {
				try {
					name = java.nio.file.Paths.get(newNormalized).getFileName().toString();
				} catch (Exception ex) {
					name = newNormalized;
				}
			}
			target.setName(name);
			target.setDescription(description);

			LlamaServer.writeModelPathConfig(configFile, cfg);
			this.syncModelPathsToRuntime(manager, cfg, pathChanged);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "更新模型路径成功");
			data.put("updated", target);
			data.put("count", items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("更新模型路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("更新模型路径失败: " + e.getMessage()));
		}
	}

	/**
	 * 返回全部的模型路径
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelPathList(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			LlamaServerManager manager = LlamaServerManager.getInstance();
			Path configFile = LlamaServer.getModelPathConfigPath();
			ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
			cfg = this.ensureModelPathConfigInitialized(cfg, manager.getModelPaths(), configFile);
			List<ModelPathDataStruct> items = cfg.getItems();
			Map<String, Object> data = new HashMap<>();
			data.put("items", items);
			data.put("count", items == null ? 0 : items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("获取模型路径列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型路径列表失败: " + e.getMessage()));
		}
	}

	private boolean isSamePath(String a, String b) {
		String aa = this.normalizePathForCompare(a);
		String bb = this.normalizePathForCompare(b);
		if (aa.isEmpty() || bb.isEmpty()) return false;
		String os = System.getProperty("os.name");
		if (os != null && os.toLowerCase(Locale.ROOT).contains("win")) return aa.equalsIgnoreCase(bb);
		return aa.equals(bb);
	}

	private String normalizePathForCompare(String p) {
		if (p == null) return "";
		String s = p.trim();
		if (s.isEmpty()) return "";
		String os = System.getProperty("os.name");
		boolean win = os != null && os.toLowerCase(Locale.ROOT).contains("win");
		if (win) {
			s = s.replace('/', '\\');
		}

		while (s.length() > 1) {
			char last = s.charAt(s.length() - 1);
			if (last != '\\' && last != '/') break;
			if (win && s.length() == 3 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':' && (last == '\\' || last == '/')) {
				break;
			}
			if (win && "\\\\".equals(s)) {
				break;
			}
			s = s.substring(0, s.length() - 1);
		}

		try {
			s = java.nio.file.Paths.get(s).normalize().toString();
		} catch (Exception e) {
		}
		return s;
	}

	private ModelPathConfig ensureModelPathConfigInitialized(ModelPathConfig cfg, List<ModelPathDataStruct> legacyPaths, Path configFile)
			throws Exception {
		if (cfg == null) {
			cfg = new ModelPathConfig();
		}
		List<ModelPathDataStruct> items = cfg.getItems();
		boolean empty = items == null || items.isEmpty();
		if (!empty) {
			return cfg;
		}
		if (legacyPaths == null || legacyPaths.isEmpty()) {
			return cfg;
		}
		List<ModelPathDataStruct> migrated = new ArrayList<>();
		for (ModelPathDataStruct p : legacyPaths) {
			if (p == null || p.getPath().trim().isEmpty()) {
				continue;
			}
			String normalized = p.getPath().trim();
			boolean exists = false;
			for (ModelPathDataStruct i : migrated) {
				if (i != null && i.getPath() != null && this.isSamePath(normalized, i.getPath().trim())) {
					exists = true;
					break;
				}
			}
			if (exists) {
				continue;
			}
			ModelPathDataStruct item = new ModelPathDataStruct();
			item.setPath(normalized);
			try {
				item.setName(java.nio.file.Paths.get(normalized).getFileName().toString());
			} catch (Exception ex) {
				item.setName(normalized);
			}
			migrated.add(item);
		}
		cfg.setItems(migrated);
		LlamaServer.writeModelPathConfig(configFile, cfg);
		return cfg;
	}

	private void syncModelPathsToRuntime(LlamaServerManager manager, ModelPathConfig cfg, boolean refreshModelList) {
		if (manager == null || cfg == null) {
			return;
		}
		List<ModelPathDataStruct> items = cfg.getItems();
		List<ModelPathDataStruct> paths = new ArrayList<>();
		if (items != null) {
			for (ModelPathDataStruct i : items) {
				if (i == null || i.getPath() == null || i.getPath().trim().isEmpty()) {
					continue;
				}
				String p = i.getPath().trim();
				boolean exists = false;
				for (ModelPathDataStruct e : paths) {
					if (this.isSamePath(p, e.getPath().trim())) {
						exists = true;
						break;
					}
				}
				if (!exists) {
					paths.add(i);
				}
			}
		}
		manager.setModelPaths(paths);
		if (refreshModelList) {
			try {
				manager.listModel(true);
			} catch (Exception e) {
				logger.warn("刷新模型列表失败: {}", e.getMessage());
			}
		}
	}

	/**
	 * 添加llamacpp目录
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleLlamaCppAdd(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			LlamaCppDataStruct reqData = gson.fromJson(content, LlamaCppDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}

			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<LlamaCppDataStruct> items = cfg.getItems();
			if (items == null) {
				items = new ArrayList<>();
				cfg.setItems(items);
			}
			String normalized = reqData.getPath().trim();
			boolean exists = false;
			for (LlamaCppDataStruct i : items) {
				if (i != null && i.getPath() != null && normalized.equals(i.getPath().trim())) {
					exists = true;
					break;
				}
			}
			if (exists) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("路径已存在"));
				return;
			}
			LlamaCppDataStruct item = new LlamaCppDataStruct();
			item.setPath(normalized);
			String name = reqData.getName();
			if (name == null || name.trim().isEmpty()) {
				try {
					name = java.nio.file.Paths.get(normalized).getFileName().toString();
				} catch (Exception ex) {
					name = normalized;
				}
			}
			item.setName(name);
			item.setDescription(reqData.getDescription());
			items.add(item);
			LlamaServer.writeLlamaCppConfig(configFile, cfg);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "添加llama.cpp路径成功");
			data.put("added", item);
			data.put("count", items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("添加llama.cpp路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("添加llama.cpp路径失败: " + e.getMessage()));
		}
	}

	/**
	 * 处理控制台的请求。
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleSysConsoleRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Path logPath = LlamaServer.getConsoleLogPath();
			File file = logPath.toFile();
			if (!file.exists()) {
				LlamaServer.sendTextResponse(ctx, "");
				return;
			}
			long max = 1L * 256 * 1024;
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
			List<String> cmdArgs = sanitizeBenchmarkCmdArgs(splitCmdArgs(cmd));
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
					if (currentLdPath != null && !currentLdPath.isEmpty()) {
						env.put("LD_LIBRARY_PATH", libPath + ":" + currentLdPath);
					} else {
						env.put("LD_LIBRARY_PATH", libPath);
					}
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
					logger.warn("保存基准测试结果到文件失败", ex);
				}
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("执行模型基准测试时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("执行模型基准测试失败: " + e.getMessage()));
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
			String modelId = null;
			Map<String, String> params = this.getQueryParam(query);
			modelId = params.get("modelId");

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
			String fileName = null;
			
			Map<String, String> params = this.getQueryParam(query);
			
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
			String fileName = null;
			
			Map<String, String> params = this.getQueryParam(query);
			
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
	 * 处理设备列表请求 执行 llama-bench --list-devices 命令获取可用设备列表
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleDeviceListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		
		try {
			// 从URL参数中提取 llamaBinPath
			String query = request.uri();
			String llamaBinPath = null;
			
			Map<String, String> params = this.getQueryParam(query);
			llamaBinPath = params.get("llamaBinPath");

			// 验证必需的参数
			if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的llamaBinPath参数"));
				return;
			}

			List<String> devices = LlamaServerManager.getInstance().handleListDevices(llamaBinPath);

			String executableName = "llama-bench";
			// 拼接完整命令路径
			String command = llamaBinPath.trim();
			command += File.separator;

			command += executableName + " --list-devices";

			// 执行命令
			CommandLineRunner.CommandResult result = CommandLineRunner.execute(command, 30);
			// 构建响应数据
			Map<String, Object> data = new HashMap<>();
			data.put("command", command);
			data.put("exitCode", result.getExitCode());
			data.put("output", result.getOutput());
			data.put("error", result.getError());
			data.put("devices", devices);

			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("获取设备列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取设备列表失败: " + e.getMessage()));
		}
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		//logger.info("客户端连接关闭：{}", ctx);
		// 事件通知
		ctx.fireChannelInactive();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		logger.error("处理请求时发生异常", cause);
		ctx.close();

		ctx.fireExceptionCaught(cause);
	}
	
	/**
	 * 	取出URL中的参数。
	 * @param url
	 * @return
	 */
	private Map<String, String> getQueryParam(String url) {
		if (url == null || url.isEmpty()) {
			return new HashMap<>();
		}
		try {
			// 解析 URL
			URI uri = new URI(url);
			String query = uri.getQuery(); // 获取 ? 后面的部分
			if (query == null || query.isEmpty()) {
				return new HashMap<>();
			}
			Map<String, String> params = new HashMap<>();
			for (String pair : query.split("&")) {
				int idx = pair.indexOf("=");
				if (idx > 0) {
					// 有 "="，拆分为 key 和 value
					String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
					String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
					params.put(key, value);
				} else if (idx == 0) {
					// 以 "=" 开头，如 "=value"（罕见），忽略 key
					String value = URLDecoder.decode(pair.substring(1), StandardCharsets.UTF_8);
					params.put("", value); // 可选：可跳过或记录为无名参数
				} else {
					// 没有 "="，只有 key，如 "a"
					String key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
					params.put(key, ""); // 值设为空字符串
				}
			}
			return params;
		} catch (Exception e) {
			// URL 格式错误、编码失败等，返回空 Map（可根据需求改为抛异常）
			return new HashMap<>();
		}
	}
	
	private static List<String> splitCmdArgs(String cmd) {
		String s = cmd == null ? "" : cmd;
		List<String> tokens = new ArrayList<>();
		StringBuilder buf = new StringBuilder();
		boolean inQuotes = false;
		boolean escape = false;
		
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (escape) {
				buf.append(ch);
				escape = false;
				continue;
			}
			if (ch == '\\') {
				escape = true;
				continue;
			}
			if (ch == '"') {
				inQuotes = !inQuotes;
				continue;
			}
			if (!inQuotes && Character.isWhitespace(ch)) {
				if (buf.length() > 0) {
					tokens.add(buf.toString());
					buf.setLength(0);
				}
				continue;
			}
			buf.append(ch);
		}
		if (buf.length() > 0) tokens.add(buf.toString());
		return tokens;
	}

	private static boolean parseJsonBoolean(JsonObject obj, String key, boolean fallback) {
		if (obj == null || key == null || key.isEmpty() || !obj.has(key) || obj.get(key) == null || obj.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return obj.get(key).getAsBoolean();
		} catch (Exception e) {
			try {
				String s = obj.get(key).getAsString();
				if (s == null) return fallback;
				String t = s.trim().toLowerCase();
				if (t.isEmpty()) return fallback;
				if ("true".equals(t) || "1".equals(t) || "yes".equals(t) || "on".equals(t)) return true;
				if ("false".equals(t) || "0".equals(t) || "no".equals(t) || "off".equals(t)) return false;
				return fallback;
			} catch (Exception e2) {
				return fallback;
			}
		}
	}

	private static String sanitizeCmdDisableVision(String cmd) {
		List<String> tokens = splitCmdArgs(cmd);
		List<String> out = new ArrayList<>();
		boolean hasNoMmproj = false;
		for (int i = 0; i < tokens.size(); i++) {
			String t = tokens.get(i);
			if (t == null || t.isEmpty()) continue;

			if ("--no-mmproj".equals(t)) {
				hasNoMmproj = true;
				out.add(t);
				continue;
			}

			if ("--mmproj".equals(t) || "-mm".equals(t) || "--mmproj-url".equals(t) || "-mmu".equals(t)) {
				i++;
				continue;
			}
			if (t.startsWith("--mmproj=") || t.startsWith("--mmproj-url=")) {
				continue;
			}
			out.add(t);
		}
		if (!hasNoMmproj) {
			out.add("--no-mmproj");
		}
		return joinCmdArgs(out).trim();
	}

	private static String joinCmdArgs(List<String> args) {
		if (args == null || args.isEmpty()) return "";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < args.size(); i++) {
			String a = args.get(i);
			if (a == null) continue;
			String t = a.trim();
			if (t.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(quoteIfNeeded(t));
		}
		return sb.toString();
	}

	private static String quoteIfNeeded(String s) {
		if (s == null) return "";
		boolean needs = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isWhitespace(c) || c == '"') {
				needs = true;
				break;
			}
		}
		if (!needs) return s;
		return "\"" + s.replace("\"", "\\\"") + "\"";
	}

	private static void normalizeEnableVisionInConfigMap(Map<String, Object> cfgMap) {
		if (cfgMap == null) return;
		Object v = cfgMap.get("enableVision");
		if (v == null) {
			cfgMap.put("enableVision", true);
			return;
		}
		if (v instanceof Boolean) return;
		String s = String.valueOf(v);
		if (s == null) {
			cfgMap.put("enableVision", true);
			return;
		}
		String t = s.trim().toLowerCase();
		if (t.isEmpty()) {
			cfgMap.put("enableVision", true);
			return;
		}
		if ("true".equals(t) || "1".equals(t) || "yes".equals(t) || "on".equals(t)) {
			cfgMap.put("enableVision", true);
			return;
		}
		if ("false".equals(t) || "0".equals(t) || "no".equals(t) || "off".equals(t)) {
			cfgMap.put("enableVision", false);
			return;
		}
		cfgMap.put("enableVision", true);
	}
	
	private static List<String> sanitizeBenchmarkCmdArgs(List<String> args) {
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
	 * 简单的断言。
	 * 
	 * @param check
	 * @param message
	 * @throws RequestMethodException 
	 */
	private void assertRequestMethod(boolean check, String message) throws RequestMethodException {
		if (check)
			throw new RequestMethodException(message);
	}
}
