package org.mark.llamacpp.server.channel;

import java.nio.charset.StandardCharsets;

import org.mark.llamacpp.server.service.DownloadService;
import org.mark.llamacpp.server.struct.ApiResponse;

import com.google.gson.Gson;

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
import io.netty.util.CharsetUtil;

/**
 * 下载API路由处理器
 */
public class DownloadRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    
    private final DownloadService downloadService = new DownloadService();
    private final Gson gson = new Gson();
    
    public DownloadRouterHandler() {
    }
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // 处理CORS
        if (request.method() == HttpMethod.OPTIONS) {
            sendCorsResponse(ctx);
            return;
        }
        String uri = request.uri();
        // 解析路径
        String[] pathParts = uri.split("/");
        if (pathParts.length < 2) {
            sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "无效的API路径");
            return;
        }
        
        System.err.println(uri);
        
		if (uri.startsWith("/api/downloads/list")) {
			this.handleListDownloads(ctx);
		}

		if (uri.startsWith("/api/downloads/create")) {
			this.handleCreateDownload(ctx, request);
		}
		if (uri.startsWith("/api/downloads/pause")) {
			this.handlePauseDownload(ctx, request);
		}
		if (uri.startsWith("/api/downloads/resume")) {
			this.handleResumeDownload(ctx, request);
		}
		if (uri.startsWith("/api/downloads/delete")) {
			this.handleDeleteDownload(ctx, request);
		}
		if (uri.startsWith("/api/downloads/stats")) {
			this.handleGetStats(ctx);
		}
        ctx.fireChannelRead(request.retain());
    }
    
	/**
	 * 处理获取下载列表请求
	 */
	private void handleListDownloads(ChannelHandlerContext ctx) {
		try {
			var result = downloadService.getAllDownloadTasks();
			sendJsonResponse(ctx, result);
		} catch (Exception e) {
			sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "获取下载列表失败: " + e.getMessage());
		}
	}
    
	/**
	 * 处理创建下载任务请求
	 */
	private void handleCreateDownload(ChannelHandlerContext ctx, FullHttpRequest request) {
		
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> requestData = gson.fromJson(content, java.util.Map.class);

			String url = (String) requestData.get("url");
			String path = (String) requestData.get("path");
			String fileName = (String) requestData.get("fileName");

			if (url == null || url.trim().isEmpty()) {
				sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "URL不能为空");
				return;
			}

			if (path == null || path.trim().isEmpty()) {
				sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "保存路径不能为空");
				return;
			}
			var result = downloadService.createDownloadTask(url, path, fileName);
			sendJsonResponse(ctx, result);
		} catch (Exception e) {
			e.printStackTrace();
			sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "创建下载任务失败: " + e.getMessage());
		}
	}
    
	/**
	 * 处理暂停下载任务请求
	 */
	private void handlePauseDownload(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> requestData = gson.fromJson(content, java.util.Map.class);

			String taskId = (String) requestData.get("taskId");

			if (taskId == null || taskId.trim().isEmpty()) {
				sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "任务ID不能为空");
				return;
			}

			var result = downloadService.pauseDownloadTask(taskId);
			sendJsonResponse(ctx, result);
		} catch (Exception e) {
			sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "暂停下载任务失败: " + e.getMessage());
		}
	}
    
	/**
	 * 处理恢复下载任务请求
	 */
	private void handleResumeDownload(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> requestData = gson.fromJson(content, java.util.Map.class);

			String taskId = (String) requestData.get("taskId");

			if (taskId == null || taskId.trim().isEmpty()) {
				sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "任务ID不能为空");
				return;
			}

			var result = downloadService.resumeDownloadTask(taskId);
			sendJsonResponse(ctx, result);
		} catch (Exception e) {
			sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "恢复下载任务失败: " + e.getMessage());
		}
	}
    
	/**
	 * 处理删除下载任务请求
	 */
	private void handleDeleteDownload(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> requestData = gson.fromJson(content, java.util.Map.class);

			String taskId = (String) requestData.get("taskId");

			if (taskId == null || taskId.trim().isEmpty()) {
				sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "任务ID不能为空");
				return;
			}

			var result = downloadService.deleteDownloadTask(taskId);
			sendJsonResponse(ctx, result);
		} catch (Exception e) {
			sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "删除下载任务失败: " + e.getMessage());
		}
	}
    
	/**
	 * 处理获取下载统计信息请求
	 */
	private void handleGetStats(ChannelHandlerContext ctx) {
		try {
			var result = downloadService.getDownloadStats();
			sendJsonResponse(ctx, result);
		} catch (Exception e) {
			sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "获取下载统计信息失败: " + e.getMessage());
		}
	}
    
	/**
	 * 发送JSON响应
	 */
	private void sendJsonResponse(ChannelHandlerContext ctx, Object data) {
		try {
			String jsonResponse = gson.toJson(data);
			FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
					Unpooled.copiedBuffer(jsonResponse, StandardCharsets.UTF_8));

			response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
			response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");

			ctx.writeAndFlush(response);
		} catch (Exception e) {
			sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "发送响应失败: " + e.getMessage());
		}
	}
    
    /**
     * 发送错误响应
     */
	private void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
		try {
			ApiResponse errorResponse = new ApiResponse(false, message);
			String jsonResponse = gson.toJson(errorResponse);

			FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
					Unpooled.copiedBuffer(jsonResponse, StandardCharsets.UTF_8));

			response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
			response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
			response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");

			ctx.writeAndFlush(response);
		} catch (Exception e) {
			e.printStackTrace();
			// 如果发送错误响应也失败了，记录日志
			System.err.println("发送错误响应失败: " + e.getMessage());
		}
	}
    
	/**
	 * 发送CORS响应
	 */
	private void sendCorsResponse(ChannelHandlerContext ctx) {
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);

		ctx.writeAndFlush(response);
	}
}