package org.mark.llamacpp.server.channel;

import java.io.File;
import java.nio.file.Paths;
import java.net.URL;
import java.net.URLDecoder;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.controller.BaseController;
import org.mark.llamacpp.server.controller.EasyChatController;
import org.mark.llamacpp.server.controller.HuggingFaceController;
import org.mark.llamacpp.server.controller.LlamacppController;
import org.mark.llamacpp.server.controller.ModelActionController;
import org.mark.llamacpp.server.controller.ModelInfoController;
import org.mark.llamacpp.server.controller.ModelPathController;
import org.mark.llamacpp.server.controller.ParamController;
import org.mark.llamacpp.server.controller.SystemController;
import org.mark.llamacpp.server.controller.ToolController;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

/**
 * 基本路由处理器。 实现本项目用到的API端点。
 */
public class BasicRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(BasicRouterHandler.class);

	private static final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
	
	private static final List<BaseController> pipeline = new LinkedList<>();
	
	
	static {
		pipeline.add(new EasyChatController());
		pipeline.add(new HuggingFaceController());
		pipeline.add(new LlamacppController());
		pipeline.add(new ModelActionController());
		pipeline.add(new ModelInfoController());
		pipeline.add(new ModelPathController());
		pipeline.add(new ParamController());
		pipeline.add(new ToolController());
		pipeline.add(new SystemController());
	}
	
	

	public BasicRouterHandler() {

	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		FullHttpRequest retained = request.retainedDuplicate();
		async.execute(() -> {
			try {
				this.handleRequest(ctx, retained);
			} finally {
				ReferenceCountUtil.release(retained);
			}
		});
	}
	
	
	/**
	 * 	真正处理请求的地方
	 * @param ctx
	 * @param request
	 */
	private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (!request.decoderResult().isSuccess()) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求解析失败");
			return;
		}
		String uri = request.uri();
		
		// 这里是日志专区
		// 1.
		if(LlamaServer.logRequestUrl) {
			logger.info("DEBUG - 收到请求：{}", uri);	
		}
		// 2.
		if(LlamaServer.logRequestHeader) {
			logger.info("DEBUG - 请求头：{}", request.headers());
		}
		// 3.
		if(LlamaServer.logRequestBody) {
			logger.info("DEBUG - 请求体：{}", request.content().toString(CharsetUtil.UTF_8));
		}
		
		// 傻逼浏览器不知道为什么一直在他妈的访问/.well-known/appspecific/com.chrome.devtools.json
		if ("/.well-known/appspecific/com.chrome.devtools.json".equals(uri)) {
			ctx.close();
			return;
		}
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		try {
			// 处理模型API请求
			if (this.isApiRequest(uri)) {
				boolean handled = false;
				for (BaseController c : pipeline) {
					handled = c.handleRequest(uri, ctx, request);
					if (handled) {
						break;
					}
				}
				if (!handled) {
					ctx.fireChannelRead(request.retain());
				}
				return;
			}
			// 断言一下请求方式
			this.assertRequestMethod(request.method() != HttpMethod.GET, "仅支持GET请求");
			// 解码URI
			String path = URLDecoder.decode(uri, "UTF-8");
			if(path.indexOf('?') > 0) {
				path = path.substring(0, path.indexOf('?'));
			}
			boolean isRootRequest = path.equals("/");

			if (isRootRequest) {
				path = isMobileRequest(request) ? "/index-mobile.html" : "/index.html";
			}
			// 
			URL url = LlamaServer.class.getResource("/web" + path);

			if (url == null) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "文件不存在: " + path);
				return;
			}
			// 对于非API请求，只允许访问静态文件，不允许目录浏览
			// 首先尝试从resources目录获取文件
			File file = Paths.get(url.toURI()).toFile();
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
			logger.info("处理静态文件请求时发生错误", e);
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
		}
	}

	private boolean isMobileRequest(FullHttpRequest request) {
		if (request == null) {
			return false;
		}
		String chMobile = request.headers().get("Sec-CH-UA-Mobile");
		if (chMobile != null && chMobile.indexOf("?1") >= 0) {
			return true;
		}
		String userAgent = request.headers().get("User-Agent");
		if (userAgent == null || userAgent.isBlank()) {
			return false;
		}
		String ua = userAgent.toLowerCase();
		return ua.contains("mobi")
				|| ua.contains("android")
				|| ua.contains("iphone")
				|| ua.contains("ipad")
				|| ua.contains("ipod")
				|| ua.contains("windows phone")
				|| ua.contains("webos")
				|| ua.contains("blackberry")
				|| ua.contains("opera mini")
				|| ua.contains("opera mobi");
	}
	
	
	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		for(BaseController controller : pipeline) {
			controller.inactive(ctx);
		}
		// 事件通知
		super.channelInactive(ctx);
	}
	
	
	/**
	 * 	简单的断言。
	 * @param check
	 * @param message
	 * @throws RequestMethodException
	 */
	private void assertRequestMethod(boolean check, String message) throws RequestMethodException {
		if (check)
			throw new RequestMethodException(message);
	}
	
	/**
	 * 是否为API请求。
	 * <p>整合了路由层定义的所有 OpenAI 风格端点及系统内部 API。</p>
	 * @param uri 请求路径
	 * @return true 如果是 API 请求，否则 false
	 */
	private boolean isApiRequest(String uri) {
		if (uri == null) {
			return false;
		}
		// 1. 现有通用系统 API
		if (uri.startsWith("/api/") || 
				uri.startsWith("/session") || 
				uri.startsWith("/tokenize") || 
				uri.startsWith("/apply-template") || 
				uri.startsWith("/infill")) {
			return true;
		}
		// 2. OpenAI 标准协议路径 (/v1/... 覆盖所有 v1 前缀的变体)
		if (uri.startsWith("/v1")) {
			return true;
		}
		// 3. 显式补充非 /v1 前缀的具体端点 (源自路由逻辑中的完整路径)
		// 注意：这里写死具体的根路径，以确保与路由层的处理完全一致
		if (uri.startsWith("/models") || 
				uri.startsWith("/chat/completion") || 
				uri.startsWith("/completions") || 
				uri.startsWith("/embeddings") ||
				uri.startsWith("/rerank") || 
				uri.startsWith("/responses")) {
			if(!uri.endsWith(".html"))
				return true;
		}
		return false;
	}
}
