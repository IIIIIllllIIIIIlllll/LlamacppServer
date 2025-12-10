package org.mark.llamacpp.server;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.CharsetUtil;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.api.OpenAIServerHandler;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.LoadModelRequest;
import org.mark.llamacpp.server.struct.ModelLaunchOptions;
import org.mark.llamacpp.server.struct.StopModelRequest;
import org.mark.llamacpp.server.tools.VramEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;


/**
 * 	服务端的主要实现。
 */
public class LlamaServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(LlamaServerHandler.class);

	private static final Gson gson = new Gson();
	
	/**
	 * 	OpenAI接口的实现。
	 */
	private OpenAIServerHandler openAIServerHandler = new OpenAIServerHandler();
	
	
	public LlamaServerHandler() {

	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		if (!request.decoderResult().isSuccess()) {
			sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求解析失败");
			return;
		}

		String uri = request.uri();
		logger.info("收到请求: {} {}", request.method().name(), uri);
		// 傻逼浏览器不知道为什么一直在他妈的访问/.well-known/appspecific/com.chrome.devtools.json
		if ("/.well-known/appspecific/com.chrome.devtools.json".equals(uri)) {
			ctx.close();
			return;
		}

		// 处理API请求
		if (uri.startsWith("/api/") || uri.startsWith("/v1")) {
			handleApiRequest(ctx, request, uri);
			return;
		}

		if (request.method() != HttpMethod.GET) {
			sendErrorResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "仅支持GET请求");
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
		URL url = LlamaServerHandler.class.getResource(path);
		if (url == null) {
			sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "文件不存在: " + path);
			return;
		}
		// 对于非API请求，只允许访问静态文件，不允许目录浏览
		// 首先尝试从resources目录获取文件
		File file = new File(url.getFile().replace("%20", " "));
		if (!file.exists()) {
			sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "文件不存在: " + path);
			return;
		}
		if (file.isDirectory()) {
			// 不允许直接访问目录，必须通过API
			sendErrorResponse(ctx, HttpResponseStatus.FORBIDDEN, "不允许直接访问目录，请使用API获取文件列表");
		} else {
			sendFile(ctx, file);
		}
	}

	/**
	 * 处理API请求
	 */
    private void handleApiRequest(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
        try {
            // 已加载模型API
            if (uri.startsWith("/api/models/loaded")) {
                handleLoadedModelsRequest(ctx, request);
                return;
            }
            // 模型列表API
            if (uri.startsWith("/api/models/list")) {
                handleModelListRequest(ctx, request);
                return;
            }
            // 显存估算API
            if (uri.startsWith("/api/models/vram/estimate")) {
                handleVramEstimateRequest(ctx, request);
                return;
            }
            // 设置模型别名API
            if (uri.startsWith("/api/model/alias/set")) {
                handleSetModelAliasRequest(ctx, request);
                return;
            }
			// 强制刷新模型列表API
			if (uri.startsWith("/api/models/refresh")) {
				handleRefreshModelListRequest(ctx, request);
				return;
			}
			// 加载模型API
			if (uri.startsWith("/api/models/load")) {
				handleLoadModelRequest(ctx, request);
				return;
			}
			// 停止模型API
			if (uri.startsWith("/api/models/stop")) {
				handleStopModelRequest(ctx, request);
				return;
			}
			// 获取模型启动配置API
			if (uri.startsWith("/api/models/config")) {
				handleModelConfigRequest(ctx, request);
				return;
			}
			// 停止服务API
			if (uri.startsWith("/api/shutdown")) {
				handleShutdownRequest(ctx, request);
				return;
			}
			if (uri.startsWith("/api/setting")) {
				handleSettingRequest(ctx, request);
				return;
			}
			if (uri.startsWith("/api/llamacpp/add")) {
				handleLlamaCppAdd(ctx, request);
				return;
			}
			if (uri.startsWith("/api/llamacpp/remove")) {
				handleLlamaCppRemove(ctx, request);
				return;
			}
            if (uri.startsWith("/api/llamacpp/list")) {
                handleLlamaCppList(ctx, request);
                return;
            }
            if (uri.startsWith("/api/sys/console")) {
                handleSysConsoleRequest(ctx, request);
                return;
            }
			
			// OpenAI API 端点
			// 获取模型列表
			if (uri.equals("/v1/models")) {
				this.openAIServerHandler.handleOpenAIModelsRequest(ctx, request);
				return;
			}
			// 聊天补全
			if (uri.equals("/v1/chat/completions")) {
				this.openAIServerHandler.handleOpenAIChatCompletionsRequest(ctx, request);
				return;
			}
			// 文本补全
			if (uri.equals("/v1/completions")) {
				this.openAIServerHandler.handleOpenAICompletionsRequest(ctx, request);
				return;
			}
			if (uri.equals("/v1/embeddings")) {
				this.openAIServerHandler.handleOpenAIEmbeddingsRequest(ctx, request);
				return;
			}
            this.sendJsonResponse(ctx, ApiResponse.error("404 Not Found"));
        } catch (Exception e) {
            logger.error("处理API请求时发生错误", e);
            this.sendJsonResponse(ctx, ApiResponse.error("服务器内部错误"));
        }
    }

    /**
     * 估算模型显存需求
     */
    private void handleVramEstimateRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            if (request.method() != HttpMethod.POST) {
                sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
                return;
            }

            String content = request.content().toString(CharsetUtil.UTF_8);
            if (content == null || content.trim().isEmpty()) {
                sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
                return;
            }

            JsonObject json = gson.fromJson(content, JsonObject.class);
            if (json == null) {
                sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
                return;
            }

            String modelId = json.has("modelId") ? json.get("modelId").getAsString() : null;
            Integer ctxSize = json.has("ctxSize") ? json.get("ctxSize").getAsInt() : null;
            Integer ubatchSize = json.has("ubatchSize") ? (json.get("ubatchSize").isJsonNull() ? null : json.get("ubatchSize").getAsInt()) : null;
            boolean flashAttention = json.has("flashAttention") && json.get("flashAttention").getAsBoolean();

            if (modelId == null || modelId.trim().isEmpty()) {
                sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
                return;
            }
            if (ctxSize == null || ctxSize <= 0) {
                ctxSize = 2048;
            }

            LlamaServerManager manager = LlamaServerManager.getInstance();
            // 确保模型列表已加载
            manager.listModel();
            GGUFModel model = manager.findModelById(modelId);
            if (model == null) {
                sendJsonResponse(ctx, ApiResponse.error("未找到指定模型: " + modelId));
                return;
            }

            VramEstimator.Result r = VramEstimator.estimate(model, ctxSize, ubatchSize, flashAttention);

            Map<String, Object> data = new HashMap<>();
            data.put("modelId", modelId);
            data.put("ctxSize", ctxSize);
            data.put("ubatchSize", ubatchSize);
            data.put("flashAttention", flashAttention);

            Map<String, Object> bytes = new HashMap<>();
            bytes.put("weights", r.getWeightsBytes());
            bytes.put("kv", r.getKvBytes());
            bytes.put("compute", r.getComputeBytes());
            bytes.put("total", r.getTotalBytes());

            Map<String, Object> mib = new HashMap<>();
            mib.put("weights", r.getWeightsMiB());
            mib.put("kv", r.getKvMiB());
            mib.put("compute", r.getComputeMiB());
            mib.put("total", r.getTotalMiB());

            data.put("bytes", bytes);
            data.put("mib", mib);

            sendJsonResponse(ctx, ApiResponse.success(data));
        } catch (Exception e) {
            logger.error("估算显存时发生错误", e);
            sendJsonResponse(ctx, ApiResponse.error("估算显存失败: " + e.getMessage()));
        }
    }

    private void handleSysConsoleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            if (request.method() != HttpMethod.GET) {
                sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
                return;
            }
            Path logPath = LlamaServer.getConsoleLogPath();
            File file = logPath.toFile();
            if (!file.exists()) {
                sendTextResponse(ctx, "");
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
                    sendTextResponse(ctx, "");
                    return;
                }
                String text = new String(buf, 0, read, StandardCharsets.UTF_8);
                sendTextResponse(ctx, text);
            }
        } catch (Exception e) {
            sendJsonResponse(ctx, ApiResponse.error("读取控制台日志失败: " + e.getMessage()));
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
			sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("获取已加载模型时发生错误", e);
			sendJsonResponse(ctx, ApiResponse.error("获取已加载模型失败: " + e.getMessage()));
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
					modelName = model.getName(); //primaryModel.getStringValue("general.name");
					if (modelName == null || modelName.trim().isEmpty()) {
						modelName = "未命名模型";
					}
					// 使用模型名称作为ID的一部分
					modelId = model.getModelId();
				}

				modelInfo.put("id", modelId);
                modelInfo.put("name", modelName);
                modelInfo.put("alias", model.getAlias());

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
				if(manager.isLoading(modelId)) {
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
			sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("获取模型列表时发生错误", e);
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("success", false);
			errorResponse.put("error", "获取模型列表失败: " + e.getMessage());
			sendJsonResponse(ctx, errorResponse);
		}
	}

	/**
	 * 处理强制刷新模型列表请求
	 */
    private void handleRefreshModelListRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 获取LlamaServerManager实例并强制刷新模型列表
			LlamaServerManager manager = LlamaServerManager.getInstance();
			List<GGUFModel> models = manager.listModel(true); // 传入true强制刷新

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
					modelName = primaryModel.getStringValue("general.name");
					if (modelName == null || modelName.trim().isEmpty()) {
						modelName = "未命名模型";
					}
					// 使用模型名称作为ID的一部分
					modelId = model.getModelId();
				}

				modelInfo.put("id", modelId);
                modelInfo.put("name", modelName);
                modelInfo.put("alias", model.getAlias());

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
			response.put("refreshed", true); // 标识这是刷新后的数据
			sendJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("强制刷新模型列表时发生错误", e);
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("success", false);
			errorResponse.put("error", "强制刷新模型列表失败: " + e.getMessage());
			sendJsonResponse(ctx, errorResponse);
		}
	}

	/**
	 * 处理加载模型请求
	 */
	private void handleLoadModelRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 只支持POST请求
			if (request.method() != HttpMethod.POST) {
				sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}

			// 读取请求体
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			// 解析JSON请求体为LoadModelRequest对象
			LoadModelRequest loadRequest = gson.fromJson(content, LoadModelRequest.class);
			
			logger.info("收到加载模型请求: {}", loadRequest);
			
			// 验证必需的参数
			if (loadRequest.getModelId() == null || loadRequest.getModelId().trim().isEmpty()) {
				sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
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
				sendJsonResponse(ctx, ApiResponse.success(data));
			} else {
				sendJsonResponse(ctx, ApiResponse.error("模型加载任务提交失败，可能模型已加载或不存在"));
			}
		} catch (Exception e) {
			logger.error("加载模型时发生错误", e);
			sendJsonResponse(ctx, ApiResponse.error("加载模型失败: " + e.getMessage()));
		}
	}

	/**
	 * 处理停止模型请求
	 */
	private void handleStopModelRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 只支持POST请求
			if (request.method() != HttpMethod.POST) {
				sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}

			// 读取请求体
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			// 解析JSON请求体
			StopModelRequest stopRequest = gson.fromJson(content, StopModelRequest.class);
			String modelId = stopRequest.getModelId();
			
			if (modelId == null || modelId.trim().isEmpty()) {
				sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}

			// 调用LlamaServerManager停止模型
			LlamaServerManager manager = LlamaServerManager.getInstance();
			boolean success = manager.stopModel(modelId);

			if (success) {
				Map<String, Object> data = new HashMap<>();
				data.put("message", "模型停止成功");
				sendJsonResponse(ctx, ApiResponse.success(data));
				// 发送WebSocket事件
				LlamaServer.sendModelStopEvent(modelId, true, "模型停止成功");
			} else {
				sendJsonResponse(ctx, ApiResponse.error("模型停止失败或模型未加载"));
				// 发送WebSocket事件
				LlamaServer.sendModelStopEvent(modelId, false, "模型停止失败或模型未加载");
			}
		} catch (Exception e) {
			logger.error("停止模型时发生错误", e);
			sendJsonResponse(ctx, ApiResponse.error("停止模型失败: " + e.getMessage()));
		}
	}

	/**
	 * 处理获取模型启动配置请求
	 */
	private void handleModelConfigRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 只支持GET请求
			if (request.method() != HttpMethod.GET) {
				sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
				return;
			}

			// 从URL中获取模型ID参数
			String query = request.uri();
			String modelId = null;
			
			// 解析URL参数，例如: /api/models/config?modelId=model-name
			if (query.contains("?modelId=")) {
				modelId = query.substring(query.indexOf("?modelId=") + 9);
				// 如果还有其他参数，只取modelId部分
				if (modelId.contains("&")) {
					modelId = modelId.substring(0, modelId.indexOf("&"));
				}
				// URL解码
				modelId = URLDecoder.decode(modelId, "UTF-8");
			}
			
			if (modelId == null || modelId.trim().isEmpty()) {
				sendJsonResponse(ctx, ApiResponse.error("缺少必需的modelId参数"));
				return;
			}

			// 获取配置管理器实例并获取模型启动配置
			ConfigManager configManager = ConfigManager.getInstance();
			Map<String, Object> launchConfig = configManager.getLaunchConfig(modelId);
			
			// 构建响应数据
			Map<String, Object> data = new HashMap<>();
			data.put("modelId", modelId);
			data.put("config", launchConfig);
			
			sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("获取模型启动配置时发生错误", e);
			sendJsonResponse(ctx, ApiResponse.error("获取模型启动配置失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 处理停止服务请求
	 */
	private void handleShutdownRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 只支持POST请求
			if (request.method() != HttpMethod.POST) {
				sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}

			logger.info("收到停止服务请求");
			
			// 先发送响应，然后再执行关闭操作
			Map<String, Object> data = new HashMap<>();
			data.put("message", "服务正在停止，所有模型进程将被终止");
			
			// 发送响应
			sendJsonResponse(ctx, ApiResponse.success(data));
			
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
			sendJsonResponse(ctx, ApiResponse.error("停止服务失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 处理设置请求
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
                sendJsonResponse(ctx, response);
            } else if (request.method() == HttpMethod.POST) {
                // POST请求：保存设置
                String content = request.content().toString(CharsetUtil.UTF_8);
                if (content == null || content.trim().isEmpty()) {
                    sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
                    return;
                }
                
                // 解析JSON请求体
                JsonObject settingsJson = gson.fromJson(content, JsonObject.class);
                
                List<String> modelPaths = new ArrayList<>();
                
                if (settingsJson.has("modelPaths") && settingsJson.get("modelPaths").isJsonArray()) {
                    settingsJson.get("modelPaths").getAsJsonArray().forEach(e -> {
                        String p = e.getAsString();
                        if (p != null && !p.trim().isEmpty()) modelPaths.add(p.trim());
                    });
                } else if (settingsJson.has("modelPath")) {
                    String p = settingsJson.get("modelPath").getAsString();
                    if (p != null && !p.trim().isEmpty()) modelPaths.add(p.trim());
                }
                
                // 验证必需的参数
                if (modelPaths.isEmpty()) {
                    sendJsonResponse(ctx, ApiResponse.error("缺少必需的模型路径参数"));
                    return;
                }
                
                // 更新设置
                manager.setModelPaths(modelPaths);
                
                // 保存设置到JSON文件
                saveSettingsToFile(modelPaths);
                
                Map<String, Object> data = new HashMap<>();
                data.put("message", "设置保存成功");
                
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("data", data);
                sendJsonResponse(ctx, response);
            } else {
                sendJsonResponse(ctx, ApiResponse.error("不支持的请求方法"));
            }
		} catch (Exception e) {
			logger.error("处理设置请求时发生错误", e);
			sendJsonResponse(ctx, ApiResponse.error("处理设置请求失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 保存设置到JSON文件
	 */
    private void saveSettingsToFile(List<String> modelPaths) {
        try {
            // 创建设置对象
            Map<String, Object> settings = new HashMap<>();
            settings.put("modelPaths", modelPaths);
            // 兼容旧字段，保留第一个路径
            if (modelPaths != null && !modelPaths.isEmpty()) {
                settings.put("modelPath", modelPaths.get(0));
            }
            
            // 转换为JSON字符串
            String json = gson.toJson(settings);
            
            // 获取当前工作目录
			String currentDir = System.getProperty("user.dir");
			Path configDir = Paths.get(currentDir, "config");
			
			// 确保config目录存在
			if (!Files.exists(configDir)) {
				Files.createDirectories(configDir);
			}
			
			Path settingsPath = configDir.resolve("settings.json");
			
			// 写入文件
			Files.write(settingsPath, json.getBytes(StandardCharsets.UTF_8));
			
			logger.info("设置已保存到文件: {}", settingsPath.toString());
		} catch (IOException e) {
			logger.error("保存设置到文件失败", e);
			throw new RuntimeException("保存设置到文件失败: " + e.getMessage(), e);
		}
	}

	private void handleLlamaCppAdd(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject json = gson.fromJson(content, JsonObject.class);
			if (json == null || !json.has("path")) {
				sendJsonResponse(ctx, ApiResponse.error("缺少必需的path参数"));
				return;
			}
			String pathStr = json.get("path").getAsString();
			if (pathStr == null || pathStr.trim().isEmpty()) {
				sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}

			Path configFile = getLlamaCppConfigPath();
			LlamaCppConfig cfg = readLlamaCppConfig(configFile);
			List<String> paths = cfg.paths;
			String normalized = pathStr.trim();
			if (paths.contains(normalized)) {
				sendJsonResponse(ctx, ApiResponse.error("路径已存在"));
				return;
			}
			paths.add(normalized);
			writeLlamaCppConfig(configFile, cfg);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "添加llama.cpp路径成功");
			data.put("added", normalized);
			data.put("count", paths.size());
			sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("添加llama.cpp路径时发生错误", e);
			sendJsonResponse(ctx, ApiResponse.error("添加llama.cpp路径失败: " + e.getMessage()));
		}
	}

	private void handleLlamaCppRemove(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
				return;
			}
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject json = gson.fromJson(content, JsonObject.class);
			if (json == null || !json.has("path")) {
				sendJsonResponse(ctx, ApiResponse.error("缺少必需的path参数"));
				return;
			}
			String pathStr = json.get("path").getAsString();
			if (pathStr == null || pathStr.trim().isEmpty()) {
				sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}

			Path configFile = getLlamaCppConfigPath();
			LlamaCppConfig cfg = readLlamaCppConfig(configFile);
			List<String> paths = cfg.paths;
			int before = paths == null ? 0 : paths.size();
			if (paths != null) {
				paths.removeIf(p -> pathStr.trim().equals(p));
			}
			writeLlamaCppConfig(configFile, cfg);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "移除llama.cpp路径成功");
			data.put("removed", pathStr.trim());
			data.put("count", paths == null ? 0 : paths.size());
			data.put("changed", before != (paths == null ? 0 : paths.size()));
			sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("移除llama.cpp路径时发生错误", e);
			sendJsonResponse(ctx, ApiResponse.error("移除llama.cpp路径失败: " + e.getMessage()));
		}
	}

	private void handleLlamaCppList(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.GET) {
				sendJsonResponse(ctx, ApiResponse.error("只支持GET请求"));
				return;
			}
			Path configFile = getLlamaCppConfigPath();
			LlamaCppConfig cfg = readLlamaCppConfig(configFile);
			List<String> paths = cfg.paths;
			Map<String, Object> data = new HashMap<>();
			data.put("paths", paths);
			data.put("count", paths == null ? 0 : paths.size());
			sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.error("获取llama.cpp路径列表时发生错误", e);
			sendJsonResponse(ctx, ApiResponse.error("获取llama.cpp路径列表失败: " + e.getMessage()));
		}
	}

	private Path getLlamaCppConfigPath() throws IOException {
		String currentDir = System.getProperty("user.dir");
		Path configDir = Paths.get(currentDir, "config");
		if (!Files.exists(configDir)) {
			Files.createDirectories(configDir);
		}
		return configDir.resolve("llamacpp.json");
	}

	private static class LlamaCppConfig {
		List<String> paths = new ArrayList<>();
	}

	private LlamaCppConfig readLlamaCppConfig(Path configFile) throws IOException {
		LlamaCppConfig cfg = new LlamaCppConfig();
		if (Files.exists(configFile)) {
			String json = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
			LlamaCppConfig read = gson.fromJson(json, LlamaCppConfig.class);
			if (read != null && read.paths != null) {
				cfg.paths = read.paths;
			}
		}
		return cfg;
	}

	private void writeLlamaCppConfig(Path configFile, LlamaCppConfig cfg) throws IOException {
		String json = gson.toJson(cfg);
		Files.write(configFile, json.getBytes(StandardCharsets.UTF_8));
		logger.info("llama.cpp配置已保存到文件: {}", configFile.toString());
	}
	
	/**
		* 发送JSON响应
		*/
	private void sendJsonResponse(ChannelHandlerContext ctx, Object data) {
		String json = gson.toJson(data);
		byte[] content = json.getBytes(CharsetUtil.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

	/**
	 * 发送文件内容（原有方法，保留用于非API下载）
	 */
	private void sendFile(ChannelHandlerContext ctx, File file) throws IOException {
		RandomAccessFile raf = new RandomAccessFile(file, "r");
		long fileLength = raf.length();

		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, getContentType(file.getName()));

		// 设置缓存头
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "max-age=3600");

		ctx.write(response);

		// 使用ChunkedFile传输文件内容
		ctx.write(new ChunkedFile(raf, 0, fileLength, 8192), ctx.newProgressivePromise());

		ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

		// 传输完成后关闭连接
		lastContentFuture.addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

	/**
	 * 发送错误响应
	 */
    private void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);

        byte[] content = message.getBytes(CharsetUtil.UTF_8);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
        response.content().writeBytes(content);

        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                ctx.close();
            }
        });
    }

    private void sendTextResponse(ChannelHandlerContext ctx, String text) {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
        response.content().writeBytes(content);
        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                ctx.close();
            }
        });
    }

	/**
	 * 根据文件扩展名获取Content-Type
	 */
	private String getContentType(String fileName) {
		String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
		switch (extension) {
		case "html":
		case "htm":
			return "text/html; charset=UTF-8";
		case "css":
			return "text/css";
		case "js":
			return "application/javascript";
		case "json":
			return "application/json";
		case "xml":
			return "application/xml";
		case "pdf":
			return "application/pdf";
		case "jpg":
		case "jpeg":
			return "image/jpeg";
		case "png":
			return "image/png";
		case "gif":
			return "image/gif";
		case "txt":
			return "text/plain; charset=UTF-8";
		default:
			return "application/octet-stream";
		}
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		logger.info("客户端连接关闭：{}", ctx);
		// 事件通知
		this.openAIServerHandler.channelInactive(ctx);
		super.channelInactive(ctx);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		logger.error("处理请求时发生异常", cause);
		ctx.close();
	}
	

    private void handleSetModelAliasRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            if (request.method() != HttpMethod.POST) {
                sendJsonResponse(ctx, ApiResponse.error("只支持POST请求"));
                return;
            }
            String content = request.content().toString(CharsetUtil.UTF_8);
            if (content == null || content.trim().isEmpty()) {
                sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
                return;
            }
            JsonObject json = gson.fromJson(content, JsonObject.class);
            if (json == null || !json.has("modelId") || !json.has("alias")) {
                sendJsonResponse(ctx, ApiResponse.error("缺少必需的参数: modelId 或 alias"));
                return;
            }
            String modelId = json.get("modelId").getAsString();
            String alias = json.get("alias").getAsString();
            if (modelId == null || modelId.trim().isEmpty()) {
                sendJsonResponse(ctx, ApiResponse.error("modelId不能为空"));
                return;
            }
            if (alias == null) alias = "";
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
            sendJsonResponse(ctx, ApiResponse.success(data));
        } catch (Exception e) {
            logger.error("设置模型别名时发生错误", e);
            sendJsonResponse(ctx, ApiResponse.error("设置模型别名失败: " + e.getMessage()));
        }
    }
}
