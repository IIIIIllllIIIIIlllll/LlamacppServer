package org.mark.llamacpp.server.channel;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.service.ChatStreamSession;
import org.mark.llamacpp.server.service.OpenAIService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

public class OpenAIChatStreamingHandler extends ChannelInboundHandlerAdapter {

	private static final Logger logger = LoggerFactory.getLogger(OpenAIChatStreamingHandler.class);

	private final OpenAIService openAIService = new OpenAIService();
	private ChatStreamSession currentSession;
	private boolean intercepting;

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (!(msg instanceof HttpObject)) {
			ctx.fireChannelRead(msg);
			return;
		}

		HttpObject httpObject = (HttpObject) msg;
		if (!intercepting && httpObject instanceof HttpRequest request) {
			if (!LlamaServer.isChatStreamingEnabled() || !isChatUri(request.uri())) {
				ctx.fireChannelRead(msg);
				return;
			}
			intercepting = true;
			if (request.method() == HttpMethod.OPTIONS) {
				sendCorsPreflight(ctx);
				ReferenceCountUtil.release(msg);
				resetSession();
				return;
			}
			if (request.uri().startsWith("/v1") && !validateApiKey(request)) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.UNAUTHORIZED, "invalid api key");
				ReferenceCountUtil.release(msg);
				resetSession();
				return;
			}
			currentSession = new ChatStreamSession(ctx, openAIService, request.method(), copyHeaders(request));
			currentSession.start();
		}

		if (!intercepting || currentSession == null) {
			ctx.fireChannelRead(msg);
			return;
		}

		try {
			if (httpObject instanceof HttpContent content) {
				currentSession.offer(content.content());
				if (httpObject instanceof LastHttpContent) {
					currentSession.complete();
					resetSession();
				}
			}
		} catch (IOException e) {
			logger.info("接收聊天流式请求体失败", e);
			currentSession.cancel();
			resetSession();
			openAIService.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		} finally {
			ReferenceCountUtil.release(msg);
		}
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		if (currentSession != null) {
			currentSession.cancel();
		}
		openAIService.channelInactive(ctx);
		resetSession();
		super.channelInactive(ctx);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		logger.info("处理聊天流式请求时发生异常", cause);
		if (currentSession != null) {
			currentSession.cancel();
		}
		resetSession();
		ctx.close();
	}

	private boolean isChatUri(String uri) {
		if (uri == null) {
			return false;
		}
		return uri.startsWith("/v1/chat/completions")
				|| uri.startsWith("/v1/chat/completion")
				|| uri.startsWith("/chat/completion");
	}

	private Map<String, String> copyHeaders(HttpRequest request) {
		Map<String, String> headers = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : request.headers()) {
			headers.put(entry.getKey(), entry.getValue());
		}
		return headers;
	}

	private boolean validateApiKey(HttpRequest request) {
		if (!LlamaServer.isApiKeyValidationEnabled()) {
			return true;
		}
		String expected = LlamaServer.getApiKey();
		if (expected == null || expected.isBlank()) {
			return false;
		}
		String auth = request.headers().get(HttpHeaderNames.AUTHORIZATION);
		if (auth == null) {
			return false;
		}
		auth = auth.replace("Bearer ", "");
		return expected.equals(auth);
	}

	private void sendCorsPreflight(ChannelHandlerContext ctx) {
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

	private void resetSession() {
		intercepting = false;
		currentSession = null;
	}
}
