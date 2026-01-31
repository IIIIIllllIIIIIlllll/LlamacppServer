package org.mark.llamacpp.ollama.channel;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.ollama.OllamaChatService;
import org.mark.llamacpp.ollama.OllamaEmbedService;
import org.mark.llamacpp.ollama.OllamaShowService;
import org.mark.llamacpp.ollama.OllamaTagsService;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;



/**
 * 	模拟 Ollama 的 API 服务，将请求转发到 llama.cpp OpenAI 接口并转换响应格式。
 */
public class OllamaRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(OllamaRouterHandler.class);
	
	private static final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
	
	/**
	 * 	
	 */
	private OllamaTagsService ollamaTagsService = new OllamaTagsService();
	
	/**
	 * 	
	 */
	private OllamaShowService ollamaShowService = new OllamaShowService();
	
	/**
	 * 	
	 */
	private OllamaChatService ollamaChatService = new OllamaChatService();
	
	private OllamaEmbedService ollamaEmbedService = new OllamaEmbedService();
	
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
	
	
	public OllamaRouterHandler() {
		
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param request
	 */
	private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (!request.decoderResult().isSuccess()) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求解析失败");
			return;
		}
		String uri = request.uri();
		logger.info("ollama - 收到请求: {} {}", request.method().name(), uri);
		logger.info("ollama - 请求头：{}", request.headers());
		// 傻逼浏览器不知道为什么一直在他妈的访问/.well-known/appspecific/com.chrome.devtools.json
		if ("/.well-known/appspecific/com.chrome.devtools.json".equals(uri)) {
			ctx.close();
			return;
		}
		// 如果是首页
		if(uri.startsWith("/")) {
			String responseContent = "Ollama is running";
			// 构建响应内容
			byte[] contentBytes = responseContent.getBytes(StandardCharsets.UTF_8);
			FullHttpResponse response = new DefaultFullHttpResponse(
		            HttpVersion.HTTP_1_1, 
		            HttpResponseStatus.OK,
		            Unpooled.copiedBuffer(contentBytes)
		        );
			// 设置响应头
			response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
	        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentBytes.length);
	        // 发送响应
	        ctx.writeAndFlush(response);
			return;
		}
		//
		try {
			this.handleRequest(uri, ctx, request);
		} catch (RequestMethodException e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error(e.getMessage()));
		}
	}
	
	/**
	 * 	正经处理请求的地方。
	 * @param uri
	 * @param ctx
	 * @param request
	 * @return
	 * @throws RequestMethodException
	 */
	private boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			if (uri.startsWith("/api/tags") || uri.startsWith("/api/chat") || uri.startsWith("/api/show") || uri.startsWith("/api/embed")) {
				LlamaServer.sendCorsResponse(ctx);
				return true;
			}
		}
		// 1、第一个，一般用来获取全部可用的模型。
		if (uri.startsWith("/api/tags")) {
			this.ollamaTagsService.handleModelList(ctx, request);
			return true;
		}
		// 查阅指定模型的详细信息。
		if (uri.startsWith("/api/show")) {
			this.ollamaShowService.handleShow(ctx, request);
			return true;
		}
		// 聊天补全。
		if (uri.startsWith("/api/chat")) {
			this.ollamaChatService.handleChat(ctx, request);
			return true;
		}
		// 文本嵌入
		if (uri.startsWith("/api/embed")) {
			this.ollamaEmbedService.handleEmbed(ctx, request);
			return true;
		}
		// 列出正在运行的模型。
		if (uri.startsWith("/api/ps")) {
			this.ollamaTagsService.handleLoadedModel(ctx, request);
			return true;
		}
		
		// 这些端点不能使用
		// /api/copy /api/delete /api/pull /api/push /api/generate
		this.sendOllamaNotFound(ctx);
		return true;
	}

	private void sendOllamaNotFound(ChannelHandlerContext ctx) {
		String json = "{\"error\":\"Not Found\"}";
		byte[] content = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		//
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "*");
		//
		response.headers().set(HttpHeaderNames.CONNECTION, "alive");
		response.headers().set(HttpHeaderNames.DATE, ParamTool.getDate());
		response.headers().set(HttpHeaderNames.ETAG, ParamTool.buildEtag(content));
		response.headers().set("X-Powered-By", "Express");
		
		response.content().writeBytes(content);
		ctx.writeAndFlush(response).addListener(f -> ctx.close());
	}
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		logger.info("处理请求时发生异常", cause);
		ctx.close();
	}
}
