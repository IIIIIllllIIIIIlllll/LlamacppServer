package org.mark.llamacpp.server.channel;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import org.mark.llamacpp.server.api.OpenAIService;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * 	服务端的主要实现。
 */
public class OpenAIRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(OpenAIRouterHandler.class);

	private static final Gson gson = new Gson();
	
	/**
	 * 	OpenAI接口的实现。
	 */
	private OpenAIService openAIServerHandler = new OpenAIService();
	
	public OpenAIRouterHandler() {

	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		String uri = request.uri();
		this.handleApiRequest(ctx, request, uri);
		return;
	}

	/**
	 * 处理API请求
	 */
    private void handleApiRequest(ChannelHandlerContext ctx, FullHttpRequest request, String uri) {
    		try {
			// OpenAI API 端点
			// 获取模型列表
			if (uri.startsWith("/v1/models")) {
				this.openAIServerHandler.handleOpenAIModelsRequest(ctx, request);
				return;
			}
			// 聊天补全
			if (uri.startsWith("/v1/chat/completions") || uri.startsWith("/v1/chat/completion")) {
				this.openAIServerHandler.handleOpenAIChatCompletionsRequest(ctx, request);
				return;
			}
			// 文本补全
			if (uri.startsWith("/v1/completions")) {
				this.openAIServerHandler.handleOpenAICompletionsRequest(ctx, request);
				return;
			}
			if (uri.startsWith("/v1/embeddings")) {
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
	 * 	
	 * @param ctx
	 * @param data
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
}
