package org.mark.llamacpp.server.channel;


import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.service.DownloadService;

import com.google.gson.Gson;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
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
        	LlamaServer.sendCorsResponse(ctx);
            return;
        }
        String uri = request.uri();
        // 解析路径
        String[] pathParts = uri.split("/");
        if (pathParts.length < 2) {
        	LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "无效的API路径");
            return;
        }
        
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
		
		if (uri.startsWith("/api/downloads/path/get")) {
			this.handleGetDownloadPath(ctx);
			return;
		}
		
		if (uri.startsWith("/api/downloads/path/set")) {
			this.handleSetDownloadPath(ctx, request);
			return;
		}
		      ctx.fireChannelRead(request.retain());
    }
    
	/**
	 * 处理获取下载列表请求
	 */
	private void handleListDownloads(ChannelHandlerContext ctx) {
		try {
			var result = downloadService.getAllDownloadTasks();
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (Exception e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "获取下载列表失败: " + e.getMessage());
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
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "URL不能为空");
				return;
			}

			if (path == null || path.trim().isEmpty()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "保存路径不能为空");
				return;
			}
			var result = downloadService.createDownloadTask(url, path, fileName);
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (Exception e) {
			e.printStackTrace();
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "创建下载任务失败: " + e.getMessage());
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
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "任务ID不能为空");
				return;
			}

			var result = downloadService.pauseDownloadTask(taskId);
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (Exception e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "暂停下载任务失败: " + e.getMessage());
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
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "任务ID不能为空");
				return;
			}

			var result = downloadService.resumeDownloadTask(taskId);
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (Exception e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "恢复下载任务失败: " + e.getMessage());
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
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "任务ID不能为空");
				return;
			}

			var result = downloadService.deleteDownloadTask(taskId);
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (Exception e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "删除下载任务失败: " + e.getMessage());
		}
	}
    
	/**
	 * 处理获取下载统计信息请求
	 */
	private void handleGetStats(ChannelHandlerContext ctx) {
		try {
			var result = downloadService.getDownloadStats();
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (Exception e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "获取下载统计信息失败: " + e.getMessage());
		}
	}

	/**
	 * 处理获取下载路径请求
	 */
	private void handleGetDownloadPath(ChannelHandlerContext ctx) {
		try {
			String downloadPath = LlamaServer.getDownloadDirectory();
			java.util.Map<String, String> result = new java.util.HashMap<>();
			result.put("path", downloadPath);
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (Exception e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "获取下载路径失败: " + e.getMessage());
		}
	}

	/**
	 * 处理设置下载路径请求
	 */
	private void handleSetDownloadPath(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> requestData = gson.fromJson(content, java.util.Map.class);

			String path = (String) requestData.get("path");

			if (path == null || path.trim().isEmpty()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "下载路径不能为空");
				return;
			}

			// 设置下载路径
			LlamaServer.setDownloadDirectory(path);
			
			// 保存配置到文件
			LlamaServer.saveApplicationConfig();

			java.util.Map<String, String> result = new java.util.HashMap<>();
			result.put("path", path);
			result.put("message", "下载路径设置成功");
			LlamaServer.sendJsonResponse(ctx, result);
		} catch (Exception e) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "设置下载路径失败: " + e.getMessage());
		}
	}
}