package org.mark.llamacpp.server.channel;


import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.mark.file.downloader.DownloadTaskInfo;
import org.mark.file.downloader.DownloadTaskManager;
import org.mark.file.downloader.DownloadTaskStatus;
import org.mark.llamacpp.download.struct.ModelDownloadRequest;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.tools.JsonUtil;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

/**
 * 模型下载API路由处理器
 */
public class FileDownloadRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    
    private static final DownloadTaskManager taskManager = createTaskManager();

	private static final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
    
    /**
     * 	空的构造器。
     */
    public FileDownloadRouterHandler() {
    	
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

	private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
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
			ModelDownloadRequest req = JsonUtil.fromJson(content, ModelDownloadRequest.class);
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
			Path modelLeaf = modelRootDir.getFileName();
			if (modelLeaf != null && modelLeaf.toString().equalsIgnoreCase(folderName)) {
				targetDir = modelRootDir;
			}
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
				Map<String, Object> r = createAndStartTask(url, targetDir.toString(), null);
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
			List<DownloadTaskInfo> tasks = taskManager.listTasks();
			List<Map<String, Object>> downloads = new ArrayList<>();
			for (DownloadTaskInfo task : tasks) {
				downloads.add(toTaskView(task));
			}
			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("downloads", downloads);
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
			java.util.Map<String, Object> requestData = JsonUtil.fromJson(content, java.util.Map.class);

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
			var result = createAndStartTask(url, path, fileName);
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
			java.util.Map<String, Object> requestData = JsonUtil.fromJson(content, java.util.Map.class);

			String taskId = (String) requestData.get("taskId");

			if (taskId == null || taskId.trim().isEmpty()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "任务ID不能为空");
				return;
			}

			taskManager.pauseTask(taskId);
			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("taskId", taskId);
			result.put("message", "下载任务已暂停");
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
			java.util.Map<String, Object> requestData = JsonUtil.fromJson(content, java.util.Map.class);

			String taskId = (String) requestData.get("taskId");

			if (taskId == null || taskId.trim().isEmpty()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "任务ID不能为空");
				return;
			}

			DownloadTaskInfo task = taskManager.startTask(taskId);
			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("taskId", task.getTaskId());
			result.put("message", "下载任务已恢复");
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
			java.util.Map<String, Object> requestData = JsonUtil.fromJson(content, java.util.Map.class);

			String taskId = (String) requestData.get("taskId");

			if (taskId == null || taskId.trim().isEmpty()) {
				LlamaServer.sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "任务ID不能为空");
				return;
			}

			Object deleteFileObj = requestData.get("deleteFile");
			boolean deleteLocalFile = deleteFileObj instanceof Boolean b ? b.booleanValue() : false;
			boolean deleted = taskManager.deleteTask(taskId, deleteLocalFile);
			Map<String, Object> result = new HashMap<>();
			result.put("success", deleted);
			result.put("taskId", taskId);
			if (deleted) {
				result.put("message", "下载任务已删除");
			} else {
				result.put("error", "无法删除任务，任务可能不存在");
			}
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
			List<DownloadTaskInfo> tasks = taskManager.listTasks();
			long activeCount = tasks.stream().filter(t -> t.getStatus() == DownloadTaskStatus.RUNNING).count();
			long pendingCount = tasks.stream().filter(t -> t.getStatus() == DownloadTaskStatus.PENDING).count();
			long completedCount = tasks.stream().filter(t -> t.getStatus() == DownloadTaskStatus.COMPLETED).count();
			long failedCount = tasks.stream().filter(t -> t.getStatus() == DownloadTaskStatus.FAILED).count();
			Map<String, Object> stats = new HashMap<>();
			stats.put("active", activeCount);
			stats.put("pending", pendingCount);
			stats.put("completed", completedCount);
			stats.put("failed", failedCount);
			stats.put("total", tasks.size());
			Map<String, Object> result = new HashMap<>();
			result.put("success", true);
			result.put("stats", stats);
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
			java.util.Map<String, Object> requestData = JsonUtil.fromJson(content, java.util.Map.class);

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

	private static DownloadTaskManager createTaskManager() {
		try {
			return DownloadTaskManager.createDefault(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
		} catch (IOException e) {
			throw new RuntimeException("初始化下载任务管理器失败", e);
		}
	}

	private Map<String, Object> createAndStartTask(String url, String path, String fileName) {
		Map<String, Object> result = new HashMap<>();
		try {
			Path targetFile = resolveTargetFile(path, fileName, url);
			DownloadTaskInfo created = taskManager.createTask(url, targetFile, 8);
			taskManager.startTask(created.getTaskId());
			result.put("success", true);
			result.put("taskId", created.getTaskId());
			result.put("message", "下载任务创建成功");
		} catch (Exception e) {
			result.put("success", false);
			result.put("error", "创建下载任务失败: " + e.getMessage());
		}
		return result;
	}

	private static Path resolveTargetFile(String path, String fileName, String url) {
		Path base = Paths.get(path);
		String selectedName = trimToNull(fileName);
		if (selectedName == null) {
			selectedName = inferFileName(url);
		}
		if (selectedName == null || selectedName.isBlank()) {
			throw new IllegalArgumentException("无法推断文件名");
		}
		selectedName = selectedName.replaceAll("[<>:\"/\\\\|?*]", "_").trim();
		if (selectedName.isEmpty()) {
			throw new IllegalArgumentException("文件名不合法");
		}
		if (Files.exists(base) && !Files.isDirectory(base)) {
			return base;
		}
		return base.resolve(selectedName);
	}

	private static String inferFileName(String url) {
		try {
			URI uri = URI.create(url);
			String path = uri.getPath();
			if (path == null || path.isBlank()) {
				return null;
			}
			String name = Paths.get(path).getFileName().toString();
			return trimToNull(name);
		} catch (Exception e) {
			return null;
		}
	}

	private Map<String, Object> toTaskView(DownloadTaskInfo task) {
		Map<String, Object> view = new HashMap<>();
		Path target = Path.of(task.getTargetPath());
		String fileName = target.getFileName() == null ? "" : target.getFileName().toString();
		String parentPath = target.getParent() == null ? "" : target.getParent().toString();
		view.put("taskId", task.getTaskId());
		view.put("url", task.getSourceUrl());
		view.put("targetPath", parentPath);
		view.put("fileName", fileName);
		view.put("state", mapState(task.getStatus()));
		view.put("totalBytes", task.getTotalBytes());
		view.put("downloadedBytes", task.getDownloadedBytes());
		view.put("partsTotal", task.getPartsTotal());
		view.put("partsCompleted", task.getPartsCompleted());
		view.put("progressRatio", task.getProgressRatio());
		view.put("createdAt", task.getCreatedAt());
		view.put("updatedAt", task.getUpdatedAt());
		if (task.getErrorMessage() != null) {
			view.put("errorMessage", task.getErrorMessage());
		}
		return view;
	}

	private String mapState(DownloadTaskStatus status) {
		return switch (status) {
		case RUNNING -> "DOWNLOADING";
		case PAUSED -> "PAUSED";
		case COMPLETED -> "COMPLETED";
		case FAILED -> "FAILED";
		case PENDING -> "IDLE";
		};
	}
}
