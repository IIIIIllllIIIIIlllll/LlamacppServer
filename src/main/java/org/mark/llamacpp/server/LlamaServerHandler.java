package org.mark.llamacpp.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.CharsetUtil;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class LlamaServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(LlamaServerHandler.class);

	private static final Gson gson = new Gson();
	
	// 存储当前通道正在处理的模型链接，用于在连接关闭时停止对应的模型进程
	private static final Map<ChannelHandlerContext, HttpURLConnection> channelConnectionMap = new HashMap<>();
	
	
	private static final Executor worker = Executors.newSingleThreadExecutor();

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
				handleOpenAIModelsRequest(ctx, request);
				return;
			}
			// 聊天补全
            if (uri.equals("/v1/chat/completions")) {
                handleOpenAIChatCompletionsRequest(ctx, request);
                return;
            }
            // 文本补全
            if (uri.equals("/v1/completions")) {
                handleOpenAICompletionsRequest(ctx, request);
                return;
            }
            if (uri.equals("/v1/embeddings")) {
                handleOpenAIEmbeddingsRequest(ctx, request);
                return;
            }

			
			
            this.sendJsonResponse(ctx, ApiResponse.error("未知的API端点"));
        } catch (Exception e) {
            logger.error("处理API请求时发生错误", e);
            this.sendJsonResponse(ctx, ApiResponse.error("服务器内部错误"));
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
	 * 处理 OpenAI 模型列表请求
	 */
	private void handleOpenAIModelsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			// 只支持GET请求
			if (request.method() != HttpMethod.GET) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only GET method is supported", "method");
				return;
			}

			// 获取LlamaServerManager实例
			LlamaServerManager manager = LlamaServerManager.getInstance();
			
			// 获取已加载的进程信息
			Map<String, LlamaCppProcess> loadedProcesses = manager.getLoadedProcesses();
			
			// 获取所有模型信息
			List<GGUFModel> allModels = manager.listModel();
			
			// 构建OpenAI格式的模型列表
			List<Map<String, Object>> openAIModels = new ArrayList<>();
			
			for (Map.Entry<String, LlamaCppProcess> entry : loadedProcesses.entrySet()) {
				String modelId = entry.getKey();
				// 查找对应的模型信息
				GGUFModel modelInfo = null;
				for (GGUFModel model : allModels) {
					if (model.getModelId().equals(modelId)) {
						modelInfo = model;
						break;
					}
				}
				
				// 构建OpenAI格式的模型信息
				Map<String, Object> modelData = new HashMap<>();
				modelData.put("id", modelId);
				modelData.put("object", "model");
				modelData.put("created", System.currentTimeMillis() / 1000);
				modelData.put("owned_by", "llamacpp-server");
				
				// 添加模型详细信息（如果可用）
				if (modelInfo != null) {
					// 添加模型名称
					String modelName = "未知模型";
					if (modelInfo.getPrimaryModel() != null) {
						modelName = modelInfo.getPrimaryModel().getStringValue("general.name");
						if (modelName == null || modelName.trim().isEmpty()) {
							modelName = "未命名模型";
						}
					}
					modelData.put("name", modelName);
					
					// 添加模型路径
					modelData.put("path", modelInfo.getPath());
					
					// 添加模型大小
					modelData.put("size", modelInfo.getSize());
					
					// 添加模型架构信息
					if (modelInfo.getPrimaryModel() != null) {
						String architecture = modelInfo.getPrimaryModel().getStringValue("general.architecture");
						if (architecture != null) {
							modelData.put("architecture", architecture);
						}
						
						// 添加上下文长度
						Integer contextLength = modelInfo.getPrimaryModel().getIntValue(architecture + ".context_length");
						if (contextLength != null) {
							modelData.put("context_length", contextLength);
						}
					}
					
					// 添加多模态信息
					if (modelInfo.getMmproj() != null) {
						modelData.put("multimodal", true);
					}
				}
				
				// 添加模型根权限信息
				List<String> permissions = new ArrayList<>();
				permissions.add("model");
				modelData.put("permission", permissions);
				
				// 添加模型基础信息
				Map<String, Object> root = new HashMap<>();
				root.put("id", modelId);
				root.put("object", "model");
				root.put("created", System.currentTimeMillis() / 1000);
				root.put("owned_by", "llamacpp-server");
				modelData.put("root", root);
				
				// 添加模型父信息
				Map<String, Object> parent = new HashMap<>();
				parent.put("id", modelId);
				parent.put("object", "model");
				modelData.put("parent", parent);
				
				openAIModels.add(modelData);
			}
			
			// 构建OpenAI格式的响应
			Map<String, Object> response = new HashMap<>();
			response.put("object", "list");
			response.put("data", openAIModels);
			sendOpenAIJsonResponse(ctx, response);
		} catch (Exception e) {
			logger.error("处理OpenAI模型列表请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	/**
	 * 处理 OpenAI 聊天补全请求
	 */
	private void handleOpenAIChatCompletionsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
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
			JsonObject requestJson = gson.fromJson(content, JsonObject.class);
			
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
			
			// 获取模型端口
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + modelName, null);
				return;
			}
			// 转发请求到对应的llama.cpp进程
			forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, "/v1/chat/completions", isStream);
		} catch (Exception e) {
			logger.error("处理OpenAI聊天补全请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	/**
	 * 处理 OpenAI 文本补全请求
	 */
	private void handleOpenAICompletionsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
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
			JsonObject requestJson = gson.fromJson(content, JsonObject.class);

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

			// 获取模型端口
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Model port not found: " + modelName, null);
				return;
			}
			// 转发请求到对应的llama.cpp进程
			forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, "/v1/completions", isStream);
		} catch (Exception e) {
			logger.error("处理OpenAI文本补全请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}

	/**
	 * 处理 OpenAI 嵌入请求
	 */
	private void handleOpenAIEmbeddingsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
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
			JsonObject requestJson = gson.fromJson(content, JsonObject.class);
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
			forwardRequestToLlamaCpp(ctx, request, modelName, modelPort, "/v1/embeddings", false);
		} catch (Exception e) {
			logger.error("处理OpenAI嵌入请求时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		}
	}
	
	/**
	 * 转发请求到对应的llama.cpp进程
	 */
	private void forwardRequestToLlamaCpp(ChannelHandlerContext ctx, FullHttpRequest request, String modelName, int port, String endpoint, boolean isStream) {
		// 在异步执行前先读取请求体，避免ByteBuf引用计数问题
		String requestBody;
		HttpMethod method = request.method();
		// 复制请求头，避免在异步任务中访问已释放的请求对象
		Map<String, String> headers = new HashMap<>();
		for (Map.Entry<String, String> entry : request.headers()) {
			headers.put(entry.getKey(), entry.getValue());
		}
		
		try {
			// 使用retain()增加引用计数，确保在异步任务中可以安全访问
			request.content().retain();
			requestBody = request.content().toString(CharsetUtil.UTF_8);
			logger.info("转发请求到llama.cpp进程: {} {} 端口: {} 请求体长度: {}", method.name(), endpoint, port, requestBody.length());
		} catch (Exception e) {
			logger.error("读取请求体时发生错误", e);
			this.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, "Failed to read request body: " + e.getMessage(), null);
			return;
		}
		
		// 在Netty的事件循环中执行，避免线程切换问题
		//ctx.executor().submit(() -> {
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
				synchronized (channelConnectionMap) {
					channelConnectionMap.put(ctx, connection);
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
				// 释放之前保留的引用计数
				request.content().release();
				// 关闭连接
				if (connection != null) {
					connection.disconnect();
				}
				// 清理 
				synchronized (channelConnectionMap) {
					channelConnectionMap.remove(ctx);
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
		
		// 创建响应
		FullHttpResponse response = new DefaultFullHttpResponse(
			HttpVersion.HTTP_1_1,
			HttpResponseStatus.valueOf(responseCode)
		);
		
		// 设置响应头
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseBody.getBytes(StandardCharsets.UTF_8).length);
		
		// 设置响应体
		response.content().writeBytes(responseBody.getBytes(StandardCharsets.UTF_8));
		
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
			while ((line = br.readLine()) != null) {
				// 检查客户端连接是否仍然活跃
				if (!ctx.channel().isActive()) {
					logger.info("检测到客户端连接已断开，停止流式响应处理");
					if (connection != null) {
						connection.disconnect();
					}
					break;
				}
				
				logger.debug("收到流式数据行: {}", line);
				
				// 处理SSE格式的数据行
				if (line.startsWith("data: ")) {
					String data = line.substring(6); // 去掉 "data: " 前缀
					
					// 检查是否为结束标记
					if (data.equals("[DONE]")) {
						logger.info("收到流式响应结束标记");
						break;
					}
					
					// 创建数据块
					ByteBuf content = ctx.alloc().buffer();
					content.writeBytes(line.getBytes(StandardCharsets.UTF_8));
					content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
					
					// 创建HTTP内容块
					HttpContent httpContent = new DefaultHttpContent(content);
					
					// 发送数据块，并添加监听器检查写入是否成功
					ChannelFuture future = ctx.writeAndFlush(httpContent);
					
					// 检查写入是否失败，如果失败可能是客户端断开连接
					future.addListener((ChannelFutureListener) channelFuture -> {
						if (!channelFuture.isSuccess()) {
							logger.warn("写入流式数据失败，可能是客户端断开连接: {}", channelFuture.cause().getMessage());
							ctx.close();
						}
					});
					
					chunkCount++;
					
					// 每发送10个数据块记录一次日志
					if (chunkCount % 10 == 0) {
						logger.info("已发送 {} 个流式数据块", chunkCount);
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
			logger.error("处理流式响应时发生错误", e);
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
	 * 发送OpenAI格式的JSON响应
	 */
	private void sendOpenAIJsonResponse(ChannelHandlerContext ctx, Object data) {
		String json = gson.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);

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
	 * 发送OpenAI格式的JSON响应并清理资源
	 */
	private void sendOpenAIJsonResponseWithCleanup(ChannelHandlerContext ctx, Object data) {
		String json = gson.toJson(data);
		byte[] content = json.getBytes(StandardCharsets.UTF_8);

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
	
//	/**
//	 * 发送OpenAI格式的错误响应
//	 */
//	private void sendOpenAIErrorResponse(ChannelHandlerContext ctx, int code, String type, String message) {
//		Map<String, Object> error = new HashMap<>();
//		error.put("message", message);
//		error.put("type", type);
//		error.put("code", code);
//		
//		Map<String, Object> response = new HashMap<>();
//		response.put("error", error);
//		
//		sendOpenAIJsonResponseWithCleanup(ctx, response);
//	}
	
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
		sendOpenAIJsonResponseWithCleanup(ctx, response);
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
		// 关闭正在进行的链接
		synchronized (channelConnectionMap) {
			HttpURLConnection conn = channelConnectionMap.remove(ctx);
			if (conn != null) {
				try {
					conn.disconnect();	
				} catch (Exception e) {
						e.printStackTrace();
					}
			}
		}
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
