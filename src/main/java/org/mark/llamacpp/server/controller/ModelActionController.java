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
import java.util.concurrent.TimeUnit;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.BenchmarkService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.StopModelRequest;
import org.mark.llamacpp.server.tools.ChatTemplateFileTool;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
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
			logger.info("强制刷新模型列表时发生错误", e);
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("success", false);
			errorResponse.put("error", "强制刷新模型列表失败: " + e.getMessage());
			LlamaServer.sendJsonResponse(ctx, errorResponse);
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
					metadata.put("quantization", primaryModel.getQuantizationType());
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
			logger.info("获取模型列表时发生错误", e);
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("success", false);
			errorResponse.put("error", "获取模型列表失败: " + e.getMessage());
			LlamaServer.sendJsonResponse(ctx, errorResponse);
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
			StopModelRequest stopRequest = JsonUtil.fromJson(content, StopModelRequest.class);
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
			logger.info("停止模型时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("停止模型失败: " + e.getMessage()));
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
			logger.info("获取已加载模型时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取已加载模型失败: " + e.getMessage()));
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

			JsonElement root = JsonUtil.fromJson(content, JsonElement.class);
			if (root == null || !root.isJsonObject()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体必须为JSON对象"));
				return;
			}

			JsonObject obj = root.getAsJsonObject();
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
			//
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
		} catch (Exception e) {
			logger.info("加载模型时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("加载模型失败: " + e.getMessage()));
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
			Map<String, Object> data = this.benchmarkService.handleBenchmark(ctx, json);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (IllegalArgumentException | IllegalStateException e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		} catch (Exception e) {
			logger.info("执行模型基准测试V2时发生错误", e);
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
			String modelId = null;
			Map<String, String> params = ParamTool.getQueryParam(query);
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

	private void handleModelBenchmarkV2Get(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		
		try {
			String query = request.uri();
			Map<String, String> params = ParamTool.getQueryParam(query);
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
			for (String line : lines) {
				if (line == null) continue;
				String trimmed = line.trim();
				if (trimmed.isEmpty()) continue;
				Object obj = JsonUtil.fromJson(trimmed, Object.class);
				if (obj != null) {
					records.add(obj);
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
			
			Map<String, String> params = ParamTool.getQueryParam(query);
			
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
			
			Map<String, String> params = ParamTool.getQueryParam(query);
			
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
}
