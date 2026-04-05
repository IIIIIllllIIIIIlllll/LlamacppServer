package org.mark.test.mcp.channel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.LastHttpContent;



/**
 * 	Netty的请求处理器。
 */
public class McpRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

	private static final Logger logger = LoggerFactory.getLogger(McpRouterHandler.class);
	private static final Pattern STREAMABLE_PATH = Pattern.compile("^/mcp/([^/]+)$");
	private static final Pattern SSE_PATH = Pattern.compile("^/mcp/([^/]+)/sse$");
	private static final Pattern MESSAGE_PATH = Pattern.compile("^/mcp/([^/]+)/message$");

	private final NettySseMcpServer server;

	public McpRouterHandler(NettySseMcpServer server) {
		this.server = server;
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
		logger.info("MCP路由收到请求: method={}, uri={}, remote={}", request.method().name(), request.uri(), ctx.channel().remoteAddress());
		if (!request.decoderResult().isSuccess()) {
			logger.info("MCP路由请求解析失败: uri={}", request.uri());
			this.server.handleBadRequest(ctx, "请求解析失败");
			return;
		}
		if (request.method() == HttpMethod.OPTIONS) {
			logger.info("MCP路由分发OPTIONS: uri={}", request.uri());
			this.server.handleOptions(ctx);
			return;
		}
		String path = this.server.cleanPath(request.uri());
		Matcher streamableMatcher = STREAMABLE_PATH.matcher(path);
		if (streamableMatcher.matches()) {
			String serviceKey = streamableMatcher.group(1);
			if (request.method() == HttpMethod.GET) {
				String sessionId = this.server.readSessionIdHeader(request);
				logger.info("MCP路由分发Streamable GET: serviceKey={}, sessionId={}", serviceKey, sessionId);
				this.server.handleStreamableGet(ctx, serviceKey, sessionId);
				return;
			}
			if (request.method() == HttpMethod.POST) {
				logger.info("MCP路由分发Streamable POST: serviceKey={}", serviceKey);
				this.server.handleStreamablePost(ctx, request, serviceKey);
				return;
			}
			if (request.method() == HttpMethod.DELETE) {
				logger.info("MCP路由分发Streamable DELETE: serviceKey={}", serviceKey);
				this.server.handleStreamableDelete(ctx, request, serviceKey);
				return;
			}
		}
		Matcher sseMatcher = SSE_PATH.matcher(path);
		if (request.method() == HttpMethod.GET && sseMatcher.matches()) {
			logger.info("MCP路由分发旧版SSE连接: serviceKey={}", sseMatcher.group(1));
			this.server.handleLegacySseConnect(ctx, sseMatcher.group(1));
			return;
		}
		Matcher msgMatcher = MESSAGE_PATH.matcher(path);
		if (request.method() == HttpMethod.POST && msgMatcher.matches()) {
			logger.info("MCP路由分发消息请求: serviceKey={}", msgMatcher.group(1));
			this.server.handleLegacyMessagePost(ctx, request, msgMatcher.group(1));
			return;
		}
		logger.info("MCP路由未命中: method={}, path={}", request.method().name(), path);
		this.server.handleNotFound(ctx);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		logger.info("MCP路由检测到连接关闭: remote={}", ctx.channel().remoteAddress());
		this.server.cleanupByContext(ctx);
		super.channelInactive(ctx);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		logger.info("MCP测试服务连接异常: {}", cause.getMessage());
		ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
	}
}
