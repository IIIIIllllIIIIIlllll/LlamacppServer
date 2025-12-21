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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.server.channel.AnthropicRouterHandler;
import org.mark.llamacpp.server.channel.BasicRouterHandler;
import org.mark.llamacpp.server.channel.CompletionRouterHandler;
import org.mark.llamacpp.server.channel.DownloadRouterHandler;
import org.mark.llamacpp.server.channel.OpenAIRouterHandler;
import org.mark.llamacpp.server.io.ConsoleBroadcastOutputStream;
import org.mark.llamacpp.server.struct.LlamaCppConfig;
import org.mark.llamacpp.server.websocket.WebSocketManager;
import org.mark.llamacpp.server.websocket.WebSocketServerHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
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
    
	private static final Logger logger = LoggerFactory.getLogger(LlamaServer.class);
    
    private static final int DEFAULT_WEB_PORT = 8080;
    
    private static final int DEFAULT_ANTHROPIC_PORT = 8070;
    
    private static final Path CONSOLE_LOG_PATH = Paths.get("logs", "console.log");
    private static final String WEBSOCKET_PATH = "/ws";
    
    
    public static final String SLOTS_SAVE_KEYWORD = "~SLOTSAVE";
    
    
    public static final String SLOTS_LOAD_KEYWORD = "~SLOTLOAD";
    
    
    public static final String HELP_KEYWORD = "~HELP";
    
    
    
    
    private static final Gson GSON = new Gson();
    
    public static final PrintStream out = System.out;
    
    public static final PrintStream err = System.err;
    
    
    public static void main(String[] args) {
        try {
            Files.createDirectories(CONSOLE_LOG_PATH.getParent());
            ConsoleBroadcastOutputStream out = new ConsoleBroadcastOutputStream(new FileOutputStream(CONSOLE_LOG_PATH.toFile(), true), StandardCharsets.UTF_8);
            PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8.name());
            System.setOut(ps);
            System.setErr(ps);
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 执行一次，创建缓存目录。
        LlamaServer.getCachePath();
        
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
        
        logger.info("系统初始化完成，启动Web服务器...");
        
        Thread t1 = new Thread(() -> {
        		LlamaServer.bindOpenAI(DEFAULT_WEB_PORT);
        });
        t1.start();
        
        Thread t2 = new Thread(() -> {
    			LlamaServer.bindAnthropic(DEFAULT_ANTHROPIC_PORT);
        });
        t2.start();
    }
    
    
    private static void bindAnthropic(int port) {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(Integer.MAX_VALUE)) // 最大！
                                    .addLast(new ChunkedWriteHandler())
                                    .addLast(new BasicRouterHandler())
                                    .addLast(new CompletionRouterHandler())
                                    .addLast(new AnthropicRouterHandler())
                                    .addLast(new DownloadRouterHandler());
                        }
                        
                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                        		logger.warn("Failed to initialize a channel. Closing: " + ctx.channel(), cause);
                            ctx.close();
                        }
                    });
            
            ChannelFuture future = bootstrap.bind(port).sync();
            logger.info("LlammServer启动成功，端口: {}", port);
            logger.info("访问地址: http://localhost:{}", port);
            
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            logger.error("服务器被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("服务器启动失败", e);
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            
            logger.info("服务器已关闭");
        }
    }
    
    
    private static void bindOpenAI(int port) {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(Integer.MAX_VALUE)) // 最大！
                                    .addLast(new ChunkedWriteHandler())
                                    .addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true, Integer.MAX_VALUE))
                                    .addLast(new WebSocketServerHandler())
                                    
                                    .addLast(new BasicRouterHandler())
                                    .addLast(new CompletionRouterHandler())
                                    .addLast(new DownloadRouterHandler())
                                    .addLast(new OpenAIRouterHandler());
                        }
                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                        		logger.warn("Failed to initialize a channel. Closing: " + ctx.channel(), cause);
                            ctx.close();
                        }
                    });
            
            ChannelFuture future = bootstrap.bind(port).sync();
            logger.info("LlammServer启动成功，端口: {}", port);
            logger.info("访问地址: http://localhost:{}", port);
            
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            logger.error("服务器被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("服务器启动失败", e);
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
    
    /**
     * 发送模型停止事件
     */
    public static void sendModelStopEvent(String modelId, boolean success, String message) {
        WebSocketManager.getInstance().sendModelStopEvent(modelId, success, message);
    }
    
    public static void sendConsoleLineEvent(String modelId, String line) {
        WebSocketManager.getInstance().sendConsoleLineEvent(modelId, line);
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
			logger.error("保存设置到文件失败", e);
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
			if (read != null && read.getPaths() != null) {
				cfg.setPaths(read.getPaths());
			}
		}
		return cfg;
	}
	

	public synchronized static void writeLlamaCppConfig(Path configFile, LlamaCppConfig cfg) throws IOException {
		String json = GSON.toJson(cfg);
		Files.write(configFile, json.getBytes(StandardCharsets.UTF_8));
		logger.info("llama.cpp配置已保存到文件: {}", configFile.toString());
	}
	
	//================================================================================================
	
	/**
	 * 	发送JSON响应。
	 * @param ctx
	 * @param data
	 */
	public static void sendJsonResponse(ChannelHandlerContext ctx, Object data) {
		String json = GSON.toJson(data);
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
	public static void sendFile(ChannelHandlerContext ctx, File file) throws IOException {
		RandomAccessFile raf = new RandomAccessFile(file, "r");
		long fileLength = raf.length();

		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, LlamaServer.getContentType(file.getName()));

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
		response.content().writeBytes(content);
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
}
