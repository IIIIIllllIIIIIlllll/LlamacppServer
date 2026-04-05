package org.mark.test.mcp.channel;

import org.mark.test.mcp.struct.McpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;

public class StreamableHttpTransportHandler {

	private static final Logger logger = LoggerFactory.getLogger(StreamableHttpTransportHandler.class);

	private final NettySseMcpServer server;
	private final McpProtocolHandler protocolHandler;

	public StreamableHttpTransportHandler(NettySseMcpServer server, McpProtocolHandler protocolHandler) {
		this.server = server;
		this.protocolHandler = protocolHandler;
	}

	public void handleGet(ChannelHandlerContext ctx, String serviceKey, String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			this.server.handleBadRequest(ctx, "缺少MCP-Session-Id");
			return;
		}
		this.server.openStreamableSseStream(ctx, serviceKey, sessionId);
	}

	public void handlePost(ChannelHandlerContext ctx, FullHttpRequest request, String serviceKey) {
		String requestSessionId = this.server.readSessionIdHeader(request);
		logger.info("MCP Streamable请求进入: method={}, uri={}, serviceKey={}, sessionId={}, remote={}", request.method().name(), request.uri(),
				serviceKey, requestSessionId, ctx.channel().remoteAddress());

		try {
			McpParsedRequest parsedRequest = this.protocolHandler.parseRequest(request, requestSessionId, "MCP Streamable");
			String sessionId = requestSessionId;
			if ("initialize".equals(parsedRequest.getMethod())) {
				McpSession session = this.server.createSession(serviceKey, false);
				sessionId = session.getId();
				logger.info("MCP Streamable创建会话: serviceKey={}, sessionId={}", serviceKey, sessionId);
			} else if (sessionId == null || sessionId.isBlank()) {
				logger.info("MCP Streamable按无状态请求处理: serviceKey={}", serviceKey);
				sessionId = null;
			} else {
				McpSession session = this.server.getSession(sessionId);
				if (session == null || !serviceKey.equals(session.getServiceKey())) {
					logger.info("MCP Streamable会话无效: serviceKey={}, sessionId={}", serviceKey, sessionId);
					this.server.sendJsonHttp(ctx, HttpResponseStatus.NOT_FOUND,
							this.server.newErrorBody("2.0", parsedRequest.getId(), -32001, "MCP会话不存在或已失效"));
					return;
				}
			}
			McpProtocolResult result = this.protocolHandler.processRequest(serviceKey, sessionId, parsedRequest);

			if (result.hasResponse()) {
				this.server.sendJsonHttp(ctx, HttpResponseStatus.OK, result.getResponse(), sessionId);
				return;
			}
			this.server.sendAccepted(ctx, sessionId);
		} catch (McpProtocolException e) {
			this.server.sendJsonHttp(ctx, e.getStatus(), e.getResponseBody(), requestSessionId);
		}
	}

	public void handleDelete(ChannelHandlerContext ctx, FullHttpRequest request, String serviceKey) {
		String sessionId = this.server.readSessionIdHeader(request);
		if (sessionId == null || sessionId.isBlank()) {
			this.server.handleBadRequest(ctx, "缺少MCP-Session-Id");
			return;
		}
		McpSession session = this.server.getSession(sessionId);
		if (session == null || !serviceKey.equals(session.getServiceKey())) {
			this.server.handleNotFound(ctx);
			return;
		}
		this.server.removeSession(sessionId);
		this.server.sendNoContent(ctx, sessionId);
	}
}
