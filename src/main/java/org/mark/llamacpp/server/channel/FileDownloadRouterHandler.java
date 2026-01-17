package org.mark.llamacpp.server.channel;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.mark.llamacpp.download.struct.ModelDownloadRequest;
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
 * 模型下载API路由处理器
 */
public class FileDownloadRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    
	/**
	 * 	下载服务
	 */
    private static final DownloadService downloadService = DownloadService.getInstance();
    
    /**
     * 	JSON处理器
     */
    private static final Gson gson = new Gson();
    
    
    /**
     * 	空的构造器。
     */
    public FileDownloadRouterHandler() {
    	
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
		// 列出全部的下载任务
		if (uri.startsWith("/api/downloads/list")) {
			this.handleListDownloads(ctx);
			return;
		}
		// 创建下载任务
		if (uri.startsWith("/api/downloads/create")) {
			this.handleCreateDownload(ctx, request);
			return;
		}
		// 创建模型下载任务
		if (uri.startsWith("/api/downloads/model/create")) {
			this.handleModelDonwload(ctx, request);
			return;
		}
		
		// 暂停指定的下载任务
		if (uri.startsWith("/api/downloads/pause")) {
			this.handlePauseDownload(ctx, request);
			return;
		}
		// 恢复下载任务
		if (uri.startsWith("/api/downloads/resume")) {
			this.handleResumeDownload(ctx, request);
			return;
		}
		// 删除下载任务
		if (uri.startsWith("/api/downloads/delete")) {
			this.handleDeleteDownload(ctx, request);
			return;
		}
		// 获取状态
		if (uri.startsWith("/api/downloads/stats")) {
			this.handleGetStats(ctx);
			return;
		}
		// 获取下载路径
		if (uri.startsWith("/api/downloads/path/get")) {
			this.handleGetDownloadPath(ctx);
			return;
		}
		// 设置下载路径
		if (uri.startsWith("/api/downloads/path/set")) {
			this.handleSetDownloadPath(ctx, request);
			return;
		}
		ctx.fireChannelRead(request.retain());
	}
	
	
	/**
	 * 	处理模型下载的请求。
	 * @param ctx
	 * @param request
	 */
	private void handleModelDonwload(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.POST) {
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "只支持POST请求");
			return;
		}
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体为空");
				return;
			}
			ModelDownloadRequest req = gson.fromJson(content, ModelDownloadRequest.class);
			if (req == null) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体解析失败");
				return;
			}
			String author = trimToNull(req.getAuthor());
			String modelId = trimToNull(req.getModelId());
			String[] downloadUrl = req.getDownloadUrl();
			String ggufPath = trimToNull(req.getPath());
			if (author == null) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "author不能为空");
				return;
			}
			if (modelId == null) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "modelId不能为空");
				return;
			}
			if (downloadUrl == null || downloadUrl.length == 0) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "downloadUrl不能为空");
				return;
			}
			String safeAuthor = sanitizePathSegment(author);
			String safeModelId = sanitizePathSegment(modelId);
			if (safeAuthor.isBlank()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "author不合法");
				return;
			}
			if (safeModelId.isBlank()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "modelId不合法");
				return;
			}

			Path baseDir = Paths.get(LlamaServer.getDefaultModelsPath()).toAbsolutePath().normalize();
			Path modelRootDir = baseDir.resolve(safeAuthor).resolve(safeModelId).toAbsolutePath().normalize();
			if (!modelRootDir.startsWith(baseDir)) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "保存路径不合法");
				return;
			}
			if (Files.exists(modelRootDir) && !Files.isDirectory(modelRootDir)) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.CONFLICT, "目标路径已存在且不是目录");
				return;
			}
			if (!Files.exists(modelRootDir)) {
				Files.createDirectories(modelRootDir);
			}

			String folderName = null;
			if (ggufPath != null) {
				folderName = normalizeVariantFolderName(ggufPath);
			}
			if (folderName == null) {
				folderName = normalizeVariantFolderName(req.getName());
			}
			folderName = sanitizePathSegment(folderName);
			if (folderName.isBlank()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "path不合法");
				return;
			}

			Path targetDir = modelRootDir.resolve(folderName).toAbsolutePath().normalize();
			if (!targetDir.startsWith(modelRootDir)) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "保存路径不合法");
				return;
			}
			if (Files.exists(targetDir) && !Files.isDirectory(targetDir)) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.CONFLICT, "目标目录已存在且不是目录");
				return;
			}
			if (Files.exists(targetDir)) {
				try (Stream<Path> entries = Files.list(targetDir)) {
					if (entries.findAny().isPresent()) {
						LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.CONFLICT, "目标目录已存在且非空");
						return;
					}
				} catch (IOException e) {
					LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "无法检查目标目录状态: " + e.getMessage());
					return;
				}
			} else {
				Files.createDirectories(targetDir);
			}
			
			List<Map<String, Object>> taskResults = new ArrayList<>();
			boolean allSuccess = true;
			for (int i = 0; i < downloadUrl.length; i++) {
				String url = trimToNull(downloadUrl[i]);
				if (url == null) {
					allSuccess = false;
					Map<String, Object> r = new HashMap<>();
					r.put("success", false);
					r.put("error", "downloadUrl包含空值");
					taskResults.add(r);
					continue;
				}
//				String fileName = null;
//				if (i == 0) {
//					fileName = sanitizeFileName(req.getName());
//				}
				Map<String, Object> r = downloadService.createModelDownloadTask(url, targetDir.toString(), null);
				if (!Boolean.TRUE.equals(r.get("success"))) {
					allSuccess = false;
				}
				taskResults.add(r);
			}

			Map<String, Object> resp = new HashMap<>();
			resp.put("success", allSuccess);
			resp.put("path", targetDir.toString());
			resp.put("tasks", taskResults);
			LlamaServer.sendJsonResponse(ctx, resp);
		} catch (Exception e) {
			e.printStackTrace();
			LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
					"创建模型下载任务失败: " + e.getMessage());
		}
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private static String sanitizePathSegment(String segment) {
		if (segment == null) {
			return "";
		}
		String s = segment.trim();
		if (s.isEmpty()) {
			return "";
		}
		return s.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
	}

	private static String normalizeVariantFolderName(String s) {
		String v = trimToNull(s);
		if (v == null) {
			return null;
		}
		try {
			v = Paths.get(v).getFileName().toString();
		} catch (Exception ignored) {
		}
		v = v.trim();
		v = v.replaceFirst("(?i)\\.gguf$", "");
		v = v.trim();
		return v.isEmpty() ? null : v;
	}

//	private static String sanitizeFileName(String fileName) {
//		String f = trimToNull(fileName);
//		if (f == null) {
//			return null;
//		}
//		try {
//			f = Paths.get(f).getFileName().toString();
//		} catch (Exception e) {
//			return null;
//		}
//		f = f.replaceAll("[<>:\"/\\\\|?*]", "_");
//		f = f.trim();
//		return f.isEmpty() ? null : f;
//	}
    
	/**
	 * 	处理获取下载列表请求
	 * @param ctx
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
	 * 	处理创建下载任务请求
	 * @param ctx
	 * @param request
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
	 * 	处理暂停下载任务请求
	 * @param ctx
	 * @param request
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
	 * 	处理恢复下载任务请求
	 * @param ctx
	 * @param request
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
	 * 	处理删除下载任务请求
	 * @param ctx
	 * @param request
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
	 * 	处理获取下载统计信息请求
	 * @param ctx
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
	 * 	处理获取下载路径请求
	 * @param ctx
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
	 * 	处理设置下载路径请求
	 * @param ctx
	 * @param request
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
