package org.mark.llamacpp.lmstudio.channel;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.lmstudio.LMStudioService;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.service.OpenAIService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.ReferenceCountUtil;



/**
 * 	模拟LM Studio的API服务。
 */
public class LMStudioRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(LMStudioRouterHandler.class);
	
	private static final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
	
	private LMStudioService lmStudioService = new LMStudioService();
	
	private OpenAIService openAIService = new OpenAIService();
	
	private static String stripQuery(String uri) {
		int q = uri.indexOf('?');
		if (q >= 0) {
			return uri.substring(0, q);
		}
		return uri;
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
	
	
	public LMStudioRouterHandler() {
		
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
		logger.info("收到请求: {} {}", request.method().name(), uri);
		// 傻逼浏览器不知道为什么一直在他妈的访问/.well-known/appspecific/com.chrome.devtools.json
		if ("/.well-known/appspecific/com.chrome.devtools.json".equals(uri)) {
			ctx.close();
			return;
		}
		//
		try {
			boolean handled = this.handleRequest(uri, ctx, request);
			if(!handled) {
				ctx.fireChannelRead(request.retain());
			}
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
		// 模型列表
		String path = stripQuery(uri);
		if ("/api/v0/models".equals(path) || "/api/v0/models/".equals(path)) {
			this.lmStudioService.handleModelList(ctx, request);
			return true;
		}
		String modelsPrefix = "/api/v0/models/";
		if (path.startsWith(modelsPrefix)) {
			String remainder = path.substring(modelsPrefix.length());
			int slash = remainder.indexOf('/');
			String firstSegment = slash >= 0 ? remainder.substring(0, slash) : remainder;
			if (!firstSegment.isEmpty()) {
				String modelIdFilter = URLDecoder.decode(firstSegment, StandardCharsets.UTF_8);
				this.lmStudioService.handleModelList(ctx, request, modelIdFilter);
				return true;
			}
			this.lmStudioService.handleModelList(ctx, request);
			return true;
		}
		
		// 聊天补全
		if (uri.startsWith("/api/v0/chat/completions")) {
			this.lmStudioService.handleOpenAIChatCompletionsRequest(ctx, request);
			
			return true;
		}
		
		// 文本补全
		if (uri.startsWith("/api/v0/completions")) {
			this.lmStudioService.handleOpenAICompletionsRequest(ctx, request);
			return true;
		}
		
		// 文本嵌入
		if (uri.startsWith("/api/v0/embeddings")) {
			this.lmStudioService.handleOpenAIEmbeddingsRequest(ctx, request);
			return true;
		}
		
		// 兼容OpenAI
		
		// OpenAI API 端点
		// 获取模型列表
		if (uri.startsWith("/v1/models") || uri.startsWith("/models")) {
			this.openAIService.handleOpenAIModelsRequest(ctx, request);
			return true;
		}
		// 聊天补全
		if (uri.startsWith("/v1/chat/completions") || uri.startsWith("/v1/chat/completion") || uri.startsWith("/chat/completion")) {
			this.openAIService.handleOpenAIChatCompletionsRequest(ctx, request);
			return true;
		}
		// 文本补全
		if (uri.startsWith("/v1/completions") || uri.startsWith("/completions")) {
			this.openAIService.handleOpenAICompletionsRequest(ctx, request);
			return true;
		}
		if (uri.startsWith("/v1/embeddings") || uri.startsWith("/embeddings")) {
			this.openAIService.handleOpenAIEmbeddingsRequest(ctx, request);
			return true;
		}
		
		return false;
	}
	
	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		logger.info("LMS 客户端连接关闭：{}", ctx);
		// 事件通知
		this.lmStudioService.channelInactive(ctx);
		super.channelInactive(ctx);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		logger.info("处理请求时发生异常", cause);
		ctx.close();
	}
}
