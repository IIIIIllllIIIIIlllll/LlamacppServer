package org.mark.llamacpp.server;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.lmstudio.LMStudio;
import org.mark.llamacpp.ollama.Ollama;
import org.mark.llamacpp.server.channel.AnthropicRouterHandler;
import org.mark.llamacpp.server.channel.BasicRouterHandler;
import org.mark.llamacpp.server.channel.CompletionRouterHandler;
import org.mark.llamacpp.server.channel.FileDownloadRouterHandler;
import org.mark.llamacpp.server.channel.OpenAIChatStreamingHandler;
import org.mark.llamacpp.server.channel.OpenAIRouterHandler;
import org.mark.llamacpp.server.io.ConsoleBroadcastOutputStream;
import org.mark.llamacpp.server.mcp.McpClientService;
import org.mark.llamacpp.server.service.ModelSamplingService;
import org.mark.llamacpp.server.struct.LlamaCppConfig;
import org.mark.llamacpp.server.struct.LlamaCppDataStruct;
import org.mark.llamacpp.server.struct.ModelPathConfig;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.mark.llamacpp.server.websocket.WebSocketManager;
import org.mark.llamacpp.server.websocket.WebSocketServerHandler;
import org.mark.llamacpp.win.WindowsTray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.CharsetUtil;


/**
 * 	程序的入口。
 */
public class LlamaServer {
	
	public static void main(String[] args) {
		// 这里重定向输出流
		try {
			Files.createDirectories(CONSOLE_LOG_PATH.getParent());
			ConsoleBroadcastOutputStream out = new ConsoleBroadcastOutputStream(
					new FileOutputStream(CONSOLE_LOG_PATH.toFile(), true), StandardCharsets.UTF_8);
			PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8.name());
			System.setOut(ps);
			System.setErr(ps);
		} catch (Exception e) {
			e.printStackTrace();
		}
		// 执行一次，创建缓存目录。
		LlamaServer.getCachePath();

		// 加载application.json配置文件
		logger.info("正在加载application.json配置...");
		loadApplicationConfig();

		// 初始化配置管理器并加载配置
		logger.info("正在初始化配置管理器...");
		ConfigManager configManager = ConfigManager.getInstance();

		// 预加载启动配置到内存中
		logger.info("正在加载启动配置...");
		configManager.loadAllLaunchConfigs();

		// 初始化LlamaServerManager并预加载模型列表
		logger.info("正在初始化模型管理器...");
		LlamaServerManager serverManager = LlamaServerManager.getInstance();

		// 预加载模型列表，这会同时保存模型信息到配置文件
		logger.info("正在扫描模型目录...");
		serverManager.listModel();
		
		ModelSamplingService.getInstance();

		try {
			McpClientService.getInstance().initializeFromRegistry();
		} catch (Exception e) {
			logger.info("MCP初始化失败: {}", e.getMessage());
		}

		logger.info("系统初始化完成，启动Web服务器...");

		Thread t1 = new Thread(() -> {
			LlamaServer.bindOpenAI(webPort);
		});
		t1.start();

		Thread t2 = new Thread(() -> {
			LlamaServer.bindAnthropic(anthropicPort);
		});
		t2.start();
		
		if (lmstudioCompatEnabled) {
			try {
				LMStudio.getInstance().start(lmstudioCompatPort);
			} catch (Exception e) {
				logger.info("启动LMStudio兼容服务失败: {}", e.getMessage());
			}
		}
		
		if (ollamaCompatEnabled) {
			try {
				Ollama.getInstance().start(ollamaCompatPort);
			} catch (Exception e) {
				logger.info("启动Ollama兼容服务失败: {}", e.getMessage());
			}
		}

		// 尝试创建系统托盘
		createWindowsSystemTray();
	}
	
	/**
	 * 	
	 * @return
	 */
	public static String getTag() {
		return "{tag}";
	}
	
	/**
	 * 	
	 * @return
	 */
	public static String getVersion() {
		return "{version}";
	}
	
	/**
	 * 	
	 * @return
	 */
	public static String getCreatedTime() {
		return "{createdTime}";
	}
	
	
	private static final Logger logger = LoggerFactory.getLogger(LlamaServer.class);
	
	/**
	 * 	默认端口：OpenAI + 程序主要业务
	 */
	private static final int DEFAULT_WEB_PORT = 8080;

	private static final int MAX_HTTP_CONTENT_LENGTH = 16 * 1024 * 1024;
	
	/**
	 * 	默认端口：Anthropic API
	 */
	private static final int DEFAULT_ANTHROPIC_PORT = 8070;

	/**
	 * 默认下载目录
	 */
	private static final String DEFAULT_DOWNLOAD_DIRECTORY = Paths.get(System.getProperty("user.dir"), "downloads").toString();
	
	/**
	 * 	默认模型目录
	 */
	private static final String DEFAULT_MODELS_DIRECTORY = Paths.get(System.getProperty("user.dir"), "models").toString();
	
	/**
	 * 	默认llama.cpp目录
	 */
	private static final String DEFAULT_LLAMACPP_DIRECTORY = Paths.get(System.getProperty("user.dir"), "llamacpp").toString();
	
	
	/**
	 * 	日志的路径
	 */
	private static final Path CONSOLE_LOG_PATH = Paths.get("logs", "console.log");
	
	/**
	 * 	WebSocket地址
	 */
	private static final String WEBSOCKET_PATH = "/ws";
	
	//##############################################################################################################################

	private static int webPort = DEFAULT_WEB_PORT;
	
	private static int anthropicPort = DEFAULT_ANTHROPIC_PORT;
	
	private static String downloadDirectory = DEFAULT_DOWNLOAD_DIRECTORY;

	private static final Object APPLICATION_CONFIG_LOCK = new Object();
	
	private static volatile boolean apiKeyValidationEnabled = false;
	
	private static volatile String apiKey = "";
	
	private static volatile boolean ollamaCompatEnabled = false;
	
	private static volatile int ollamaCompatPort = 11434;
	
	private static volatile boolean lmstudioCompatEnabled = false;
	
	private static volatile int lmstudioCompatPort = 1234;

	private static volatile boolean chatStreamingEnabled = true;

	//##############################################################################################################################
	
	public static final String SLOTS_SAVE_KEYWORD = "~SLOTSAVE";

	public static final String SLOTS_LOAD_KEYWORD = "~SLOTLOAD";

	public static final String HELP_KEYWORD = "~HELP";
    
    //##############################################################################################################################
    
    
    private static final Gson GSON = new Gson();
    
    public static final PrintStream out = System.out;
    
    public static final PrintStream err = System.err;
    
    //##############################################################################################################################
    
    /**
     * 	日志相关：打印请求的URL
     */
    public static boolean logRequestUrl = true;
    
    /**
     * 	日志相关：打印请求的请求头。调试用
     */
    public static boolean logRequestHeader = true;
    
    /**
     * 	日志相关：打印请求的请求体。调试用
     */
    public static boolean logRequestBody = false;
    
    
    // 一些默认的目录，必须创建
    static {
    	// 默认的模型目录
    	try {
    		String currentDir = System.getProperty("user.dir");
        	Path configDir = Paths.get(currentDir, "models");
    		if (!Files.exists(configDir)) {
    			Files.createDirectories(configDir);
    		}	
    	}catch (Exception e) {
    		e.printStackTrace();
		}
    	// 
    	try {
    		String currentDir = System.getProperty("user.dir");
        	Path configDir = Paths.get(currentDir, "llamacpp");
    		if (!Files.exists(configDir)) {
    			Files.createDirectories(configDir);
    		}	
    	}catch (Exception e) {
    		e.printStackTrace();
		}
    }
    
    
    /**
     * 读取application.json配置文件
     */
	private static void loadApplicationConfig() {
		JsonObject root = readApplicationConfig(true);
		if (root == null) {
			return;
		}
		if (root.has("server")) {
			JsonObject server = root.getAsJsonObject("server");
			if (server.has("webPort")) {
				webPort = server.get("webPort").getAsInt();
			}
			if (server.has("anthropicPort")) {
				anthropicPort = server.get("anthropicPort").getAsInt();
			}
		}

		if (root.has("download")) {
			JsonObject download = root.getAsJsonObject("download");
			if (download.has("directory")) {
				downloadDirectory = download.get("directory").getAsString();
			}
		}

		if (root.has("security")) {
			JsonObject security = root.getAsJsonObject("security");
			if (security.has("apiKeyEnabled")) {
				apiKeyValidationEnabled = security.get("apiKeyEnabled").getAsBoolean();
			}
			if (security.has("apiKey")) {
				apiKey = security.get("apiKey").getAsString();
			}
		}
		
		if (root.has("compat")) {
			JsonObject compat = root.getAsJsonObject("compat");
			if (compat != null) {
				if (compat.has("ollama")) {
					JsonObject ollama = compat.getAsJsonObject("ollama");
					if (ollama != null) {
						if (ollama.has("enabled")) {
							ollamaCompatEnabled = ollama.get("enabled").getAsBoolean();
						}
						if (ollama.has("port")) {
							ollamaCompatPort = ollama.get("port").getAsInt();
						}
					}
				}
				if (compat.has("lmstudio")) {
					JsonObject lmstudio = compat.getAsJsonObject("lmstudio");
					if (lmstudio != null) {
						if (lmstudio.has("enabled")) {
							lmstudioCompatEnabled = lmstudio.get("enabled").getAsBoolean();
						}
						if (lmstudio.has("port")) {
							lmstudioCompatPort = lmstudio.get("port").getAsInt();
						}
					}
				}
				if (compat.has("chatStreaming")) {
					JsonObject chatStreaming = compat.getAsJsonObject("chatStreaming");
					if (chatStreaming != null && chatStreaming.has("enabled")) {
						chatStreamingEnabled = chatStreaming.get("enabled").getAsBoolean();
					}
				}
			}
		}
		
		if (root.has("logging")) {
			JsonObject logging = root.getAsJsonObject("logging");
			if (logging != null) {
				if (logging.has("logRequestUrl")) {
					logRequestUrl = logging.get("logRequestUrl").getAsBoolean();
				}
				if (logging.has("logRequestHeader")) {
					logRequestHeader = logging.get("logRequestHeader").getAsBoolean();
				}
				if (logging.has("logRequestBody")) {
					logRequestBody = logging.get("logRequestBody").getAsBoolean();
				}
			}
		}
	}
    
    /**
     * 保存配置到application.json文件
     */
	public static void saveApplicationConfig() {
		synchronized (APPLICATION_CONFIG_LOCK) {
			try {
				JsonObject root = new JsonObject();
	
				JsonObject server = new JsonObject();
				server.addProperty("webPort", webPort);
				server.addProperty("anthropicPort", anthropicPort);
				root.add("server", server);
	
				JsonObject download = new JsonObject();
				download.addProperty("directory", downloadDirectory);
				root.add("download", download);
				
				JsonObject security = new JsonObject();
				security.addProperty("apiKeyEnabled", apiKeyValidationEnabled);
				security.addProperty("apiKey", apiKey == null ? "" : apiKey);
				root.add("security", security);
				
				JsonObject compat = new JsonObject();
				JsonObject ollama = new JsonObject();
				ollama.addProperty("enabled", ollamaCompatEnabled);
				ollama.addProperty("port", ollamaCompatPort);
				compat.add("ollama", ollama);
				
				JsonObject lmstudio = new JsonObject();
				lmstudio.addProperty("enabled", lmstudioCompatEnabled);
				lmstudio.addProperty("port", lmstudioCompatPort);
				compat.add("lmstudio", lmstudio);

				JsonObject chatStreaming = new JsonObject();
				chatStreaming.addProperty("enabled", chatStreamingEnabled);
				compat.add("chatStreaming", chatStreaming);
				
				root.add("compat", compat);
				
				JsonObject logging = new JsonObject();
				logging.addProperty("logRequestUrl", logRequestUrl);
				logging.addProperty("logRequestHeader", logRequestHeader);
				logging.addProperty("logRequestBody", logRequestBody);
				root.add("logging", logging);
	
				String json = GSON.toJson(root);
	
				Path configPath = Paths.get("config/application.json");
				
				// 确保config目录存在
				if (!Files.exists(configPath.getParent())) {
					Files.createDirectories(configPath.getParent());
				}
				
				Files.write(configPath, json.getBytes(StandardCharsets.UTF_8));
	
				logger.info("配置已保存到文件: {}", configPath.toString());
			} catch (IOException e) {
				logger.info("保存配置文件失败", e);
				throw new RuntimeException("保存配置文件失败: " + e.getMessage(), e);
			}
		}
	}

	public static JsonObject readApplicationConfig() {
		return readApplicationConfig(false);
	}

	private static JsonObject readApplicationConfig(boolean createIfMissing) {
		synchronized (APPLICATION_CONFIG_LOCK) {
			try {
				Path configPath = Paths.get("config/application.json");
				if (!Files.exists(configPath)) {
					if (createIfMissing) {
						logger.info("配置文件不存在，使用默认配置");
						LlamaServer.saveApplicationConfig();
					}
					return new JsonObject();
				}
				String json = new String(Files.readAllBytes(configPath), StandardCharsets.UTF_8);
				JsonObject root = GSON.fromJson(json, com.google.gson.JsonObject.class);
				return root == null ? new JsonObject() : root;
			} catch (Exception e) {
				logger.info("读取配置文件失败: {}", e.getMessage());
				return new JsonObject();
			}
		}
	}
    
    // ==================== 端口配置的get/set方法 ====================
    
    public static int getWebPort() {
        return webPort;
    }
    
    public static void setWebPort(int webPort) {
        LlamaServer.webPort = webPort;
    }
    
    public static int getAnthropicPort() {
        return anthropicPort;
    }
    
    public static void setAnthropicPort(int anthropicPort) {
        LlamaServer.anthropicPort = anthropicPort;
    }
    
    // ==================== 下载目录配置的get/set方法 ====================
    
    public static String getDownloadDirectory() {
        return downloadDirectory;
    }
    
    public static void setDownloadDirectory(String downloadDirectory) {
        LlamaServer.downloadDirectory = downloadDirectory;
    }
    
    public static boolean isApiKeyValidationEnabled() {
    	return apiKeyValidationEnabled;
    }
    
    public static String getApiKey() {
    	return apiKey;
    }
    
    public static void setApiKeyValidationEnabled(boolean enabled) {
    	synchronized (APPLICATION_CONFIG_LOCK) {
			apiKeyValidationEnabled = enabled;
			saveApplicationConfig();
		}
    }
    
    public static void setApiKey(String apiKeyValue) {
    	synchronized (APPLICATION_CONFIG_LOCK) {
			apiKey = apiKeyValue == null ? "" : apiKeyValue;
			saveApplicationConfig();
		}
    }
    
    public static void updateApiKeyConfig(boolean enabled, String apiKeyValue) {
    	synchronized (APPLICATION_CONFIG_LOCK) {
			apiKeyValidationEnabled = enabled;
			apiKey = apiKeyValue == null ? "" : apiKeyValue;
			saveApplicationConfig();
		}
    }
    
    public static boolean isOllamaCompatEnabled() {
    	return ollamaCompatEnabled;
    }
    
    public static int getOllamaCompatPort() {
    	return ollamaCompatPort;
    }
    
    public static boolean isLmstudioCompatEnabled() {
    	return lmstudioCompatEnabled;
    }
    
    public static int getLmstudioCompatPort() {
    	return lmstudioCompatPort;
    }

    public static boolean isChatStreamingEnabled() {
    	return chatStreamingEnabled;
    }
    
    public static boolean isLogRequestUrlEnabled() {
    	return logRequestUrl;
    }
    
    public static boolean isLogRequestHeaderEnabled() {
    	return logRequestHeader;
    }
    
    public static boolean isLogRequestBodyEnabled() {
    	return logRequestBody;
    }
    
    public static void updateOllamaCompatConfig(boolean enabled, int port) {
    	synchronized (APPLICATION_CONFIG_LOCK) {
    		ollamaCompatEnabled = enabled;
    		if (port > 0 && port <= 65535) {
    			ollamaCompatPort = port;
    		}
    		saveApplicationConfig();
    	}
    }
    
    public static void updateLmstudioCompatConfig(boolean enabled, int port) {
    	synchronized (APPLICATION_CONFIG_LOCK) {
    		lmstudioCompatEnabled = enabled;
    		if (port > 0 && port <= 65535) {
    			lmstudioCompatPort = port;
    		}
    		saveApplicationConfig();
    	}
    }
    
    public static void updateRequestLogConfig(Boolean urlEnabled, Boolean headerEnabled, Boolean bodyEnabled) {
    	synchronized (APPLICATION_CONFIG_LOCK) {
    		if (urlEnabled != null) {
    			logRequestUrl = urlEnabled.booleanValue();
    		}
    		if (headerEnabled != null) {
    			logRequestHeader = headerEnabled.booleanValue();
    		}
    		if (bodyEnabled != null) {
    			logRequestBody = bodyEnabled.booleanValue();
    		}
    		saveApplicationConfig();
    	}
    }
    
    // ==================== 默认路径的get方法 ====================
    
    public static String getDefaultLlamaCppPath() {
    	return DEFAULT_LLAMACPP_DIRECTORY;
    }
    
    
    public static String getDefaultModelsPath() {
    	return DEFAULT_MODELS_DIRECTORY;
    }
    
    
    private static void bindAnthropic(int port) {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH))
                                    .addLast(new ChunkedWriteHandler())
                                    .addLast(new BasicRouterHandler())
                                    .addLast(new CompletionRouterHandler())
                                    .addLast(new AnthropicRouterHandler())
                                    .addLast(new FileDownloadRouterHandler());
                        }
                        
                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                        		logger.info("Failed to initialize a channel. Closing: " + ctx.channel(), cause);
                            ctx.close();
                        }
                    });
            
            ChannelFuture future = bootstrap.bind(port).sync();
            logger.info("LlammServer启动成功，端口: {}", port);
            logger.info("访问地址: http://localhost:{}", port);
            
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            logger.info("服务器被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.info("服务器启动失败", e);
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            
            logger.info("服务器已关闭");
        }
    }
    
    
    private static void bindOpenAI(int port) {
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new OpenAIChatStreamingHandler())
                                    .addLast(new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH))
                                    .addLast(new ChunkedWriteHandler())
                                    .addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true, Integer.MAX_VALUE))
                                    .addLast(new WebSocketServerHandler())
                                    
                                    .addLast(new BasicRouterHandler())
                                    .addLast(new CompletionRouterHandler())
                                    .addLast(new FileDownloadRouterHandler())
                                    .addLast(new OpenAIRouterHandler());
                        }
                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                        		logger.info("Failed to initialize a channel. Closing: " + ctx.channel(), cause);
                            ctx.close();
                        }
                    });
            
            ChannelFuture future = bootstrap.bind(port).sync();
            logger.info("LlammServer启动成功，端口: {}", port);
            logger.info("访问地址: http://localhost:{}", port);
            
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            logger.info("服务器被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.info("服务器启动失败", e);
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            
            logger.info("服务器已关闭");
        }
    }
    
    /**
     * 	获取缓存目录的路径。
     * @return
     */
	public static Path getCachePath() {
		try {
			Path currentDir = Paths.get("").toAbsolutePath();
			Path cachePath = currentDir.resolve("cache");

			if (!Files.exists(cachePath)) {
				Files.createDirectories(cachePath);
			}
			return cachePath;
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException("Failed to create cache directory", e);
		}
	}
    
    

    public static Path getConsoleLogPath() {
        return CONSOLE_LOG_PATH;
    }
    
    /**
     * 广播WebSocket消息
     */
    public static void broadcastWebSocketMessage(String message) {
        WebSocketManager.getInstance().broadcast(message);
    }
    
    /**
     * 获取当前WebSocket连接数
     */
    public static int getWebSocketConnectionCount() {
        return WebSocketManager.getInstance().getConnectionCount();
    }
    
    /**
     * 发送模型加载事件
     */
    public static void sendModelLoadEvent(String modelId, boolean success, String message) {
        WebSocketManager.getInstance().sendModelLoadEvent(modelId, success, message);
    }

    public static void sendModelLoadEvent(String modelId, boolean success, String message, Integer port) {
        WebSocketManager.getInstance().sendModelLoadEvent(modelId, success, message, port);
    }

    public static void sendModelLoadStartEvent(String modelId, Integer port, String message) {
        WebSocketManager.getInstance().sendModelLoadStartEvent(modelId, port, message);
    }
    
    /**
     * 发送模型停止事件
     */
    public static void sendModelStopEvent(String modelId, boolean success, String message) {
        WebSocketManager.getInstance().sendModelStopEvent(modelId, success, message);
    }
    
    public static void sendConsoleLineEvent(String modelId, String line) {
        WebSocketManager.getInstance().sendConsoleLineEvent(modelId, line);
    }
    
    public static void sendModelSlotsEvent(String modelId, com.google.gson.JsonArray slots) {
        WebSocketManager.getInstance().sendModelSlotsEvent(modelId, slots);
    }
    
    //================================================================================================
    
    
	/**
	 * 保存设置到JSON文件
	 */
    public synchronized static void saveSettingsToFile(List<String> modelPaths) {
        try {
            // 创建设置对象
            Map<String, Object> settings = new HashMap<>();
            settings.put("modelPaths", modelPaths);
            // 兼容旧字段，保留第一个路径
            if (modelPaths != null && !modelPaths.isEmpty()) {
                settings.put("modelPath", modelPaths.get(0));
            }
            
            // 转换为JSON字符串
            String json = GSON.toJson(settings);
            
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
			logger.info("保存设置到文件失败", e);
			throw new RuntimeException("保存设置到文件失败: " + e.getMessage(), e);
		}
	}
    
    
	public synchronized static Path getLlamaCppConfigPath() throws IOException {
		String currentDir = System.getProperty("user.dir");
		Path configDir = Paths.get(currentDir, "config");
		if (!Files.exists(configDir)) {
			Files.createDirectories(configDir);
		}
		return configDir.resolve("llamacpp.json");
	}
	
	
	public synchronized static LlamaCppConfig readLlamaCppConfig(Path configFile) throws IOException {
		LlamaCppConfig cfg = new LlamaCppConfig();
		if (Files.exists(configFile)) {
			String json = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
			LlamaCppConfig read = GSON.fromJson(json, LlamaCppConfig.class);
			if (read != null && read.getItems() != null) {
				cfg.setItems(read.getItems());
			}
		}
		return cfg;
	}
	

	public synchronized static void writeLlamaCppConfig(Path configFile, LlamaCppConfig cfg) throws IOException {
		String json = GSON.toJson(cfg);
		Files.write(configFile, json.getBytes(StandardCharsets.UTF_8));
		logger.info("llama.cpp配置已保存到文件: {}", configFile.toString());
	}

	public synchronized static Path getModelPathConfigPath() throws IOException {
		String currentDir = System.getProperty("user.dir");
		Path configDir = Paths.get(currentDir, "config");
		if (!Files.exists(configDir)) {
			Files.createDirectories(configDir);
		}
		return configDir.resolve("modelpaths.json");
	}

	public synchronized static ModelPathConfig readModelPathConfig(Path configFile) throws IOException {
		ModelPathConfig cfg = new ModelPathConfig();
		if (Files.exists(configFile)) {
			String json = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
			ModelPathConfig read = GSON.fromJson(json, ModelPathConfig.class);
			if (read != null && read.getItems() != null) {
				cfg.setItems(read.getItems());
			}
		}
		return cfg;
	}

	public synchronized static void writeModelPathConfig(Path configFile, ModelPathConfig cfg) throws IOException {
		String json = GSON.toJson(cfg);
		Files.write(configFile, json.getBytes(StandardCharsets.UTF_8));
		logger.info("模型路径配置已保存到文件: {}", configFile.toString());
	}

	//================================================================================================
	
	/**
	 * 	扫描默认目录下是否存在llamacpp。
	 * @return
	 */
	public static List<LlamaCppDataStruct> scanLlamaCpp() {
		List<LlamaCppDataStruct> result = new ArrayList<>();
		String root = DEFAULT_LLAMACPP_DIRECTORY;
		// 检查根目录是否存在且为目录
		Path rootPath = Paths.get(root);
		if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
			return result; // 目录不存在或不是目录，直接返回空列表
		}
		try {
			// 遍历根目录下的所有子目录
			Files.list(rootPath).filter(Files::isDirectory) // 只处理子文件夹
					.forEach(subDir -> {
						// 检查子目录中是否包含 llama-server 或 llama-server.exe
						Path serverPathLinux = subDir.resolve("llama-server");
						Path serverPathWin = subDir.resolve("llama-server.exe");
						// 检查 Linux/macOS 版本
						if (Files.exists(serverPathLinux) && Files.isExecutable(serverPathLinux)) {
							result.add(new LlamaCppDataStruct(subDir.getFileName().toString(), subDir.toString(), "https://github.com/ggml-org/llama.cpp"));
							return; // 找到一个即可，跳过Windows检查
						}
						// 检查 Windows 版本
						if (Files.exists(serverPathWin) && Files.isExecutable(serverPathWin)) {
							result.add(new LlamaCppDataStruct(subDir.getFileName().toString(), subDir.toString(), "https://github.com/ggml-org/llama.cpp"));
						}
					});
		} catch (Exception e) {
			e.printStackTrace();
			// 可选：记录日志，如 log.warn("Failed to scan llamaCpp directory: " + root, e);
			// 为保持健壮性，即使出错也不中断，返回已找到的结果
		}
		return result;
	}
	
	
	//================================================================================================
	
	private static void setCorsHeaders(HttpHeaders headers) {
		headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		headers.set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "86400");
	}
	
	/**
	 * 	发送JSON响应。
	 * @param ctx
	 * @param data
	 */
	public static void sendJsonResponse(ChannelHandlerContext ctx, Object data) {
		sendJsonResponseInternal(ctx, HttpResponseStatus.OK, data);
	}

	private static void sendJsonResponseInternal(ChannelHandlerContext ctx, HttpResponseStatus status, Object data) {
		String json = GSON.toJson(data);
		byte[] content = json.getBytes(CharsetUtil.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		setCorsHeaders(response.headers());
		response.content().writeBytes(content);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

	public static void sendExpressJsonResponse(ChannelHandlerContext ctx, HttpResponseStatus status, Object data, boolean allowAllMethods) {
		String json = JsonUtil.toJson(data);
		byte[] content = json.getBytes(CharsetUtil.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status == null ? HttpResponseStatus.OK : status);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		if (allowAllMethods) {
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "*");
		}
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

	public static void sendExpressRawJsonResponse(ChannelHandlerContext ctx, HttpResponseStatus status, byte[] content, boolean allowAllMethods) {
		byte[] bytes = content == null ? new byte[0] : content;

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status == null ? HttpResponseStatus.OK : status);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		if (allowAllMethods) {
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "*");
		}
		response.headers().set(HttpHeaderNames.CONNECTION, "alive");
		response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
		response.headers().set(HttpHeaderNames.ETAG, ParamTool.buildEtag(bytes));
		response.headers().set("X-Powered-By", "Express");
		response.content().writeBytes(bytes);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

	public static void sendJsonErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("status", "error");
		payload.put("message", message == null ? "" : message);
		sendJsonResponseInternal(ctx, status == null ? HttpResponseStatus.INTERNAL_SERVER_ERROR : status, payload);
	}
	
	
	
	/**
	 * 发送文件内容（原有方法，保留用于非API下载）
	 */
	public static void sendFile(ChannelHandlerContext ctx, File file) throws IOException {
		RandomAccessFile raf = new RandomAccessFile(file, "r");
		long fileLength = raf.length();

		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, LlamaServer.getContentType(file.getName()));
		setCorsHeaders(response.headers());

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
	 * 	
	 * @param ctx
	 * @param status
	 * @param message
	 */
    public static void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);

        byte[] content = message.getBytes(CharsetUtil.UTF_8);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
        setCorsHeaders(response.headers());
        response.content().writeBytes(content);

        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                ctx.close();
            }
        });
    }
	
    /**
     * 	
     * @param ctx
     * @param text
     */
    public static  void sendTextResponse(ChannelHandlerContext ctx, String text) {
		byte[] content = text.getBytes(StandardCharsets.UTF_8);
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		setCorsHeaders(response.headers());
		response.content().writeBytes(content);
		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}
    
    
    public static void sendCorsResponse(ChannelHandlerContext ctx) {
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

		setCorsHeaders(response.headers());
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
    }
	
	/**
	 * 	判断文件类型
	 * @param fileName
	 * @return
	 */
	public static String getContentType(String fileName) {
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
	
	/**
	 * 	创建系统托盘。
	 */
	private static void createWindowsSystemTray() {
		// 判断操作系统是否为Windows，如果不是则直接返回
		String osName = System.getProperty("os.name");
		if (!osName.toLowerCase().startsWith("windows")) {
			return;
		}

		try {
			WindowsTray tray = WindowsTray.getInstance();

			tray.addButton("打开首页", () -> {
				try {
					java.awt.Desktop.getDesktop().browse(new java.net.URI("http://127.0.0.1:" + webPort));
				} catch (Exception e) {
					e.printStackTrace();
				}
			});
			tray.addSeparator();
			tray.addButton("退出程序", () -> {
				LlamaServerManager.getInstance().shutdownAll();
				System.exit(0);
			});

			tray.setDefaultAction(() -> {
				// 双击托盘图标触发，暂时没东西
				try {
					java.awt.Desktop.getDesktop().browse(new java.net.URI("http://127.0.0.1:" + webPort));
				} catch (Exception e) {
					e.printStackTrace();
				}
			});

			tray.start("LlamaCpp Server - 运行中");
			tray.displayInfoMessage("启动成功", "LlamaCpp Server 已在后台运行");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
