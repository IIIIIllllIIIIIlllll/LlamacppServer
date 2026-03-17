package org.mark.llamacpp.server.channel;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.service.CompletionService;
import org.mark.llamacpp.server.struct.CharactorDataStruct;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

/**
 * 	这是自用的创作服务的路由控制器。
 */
public class CompletionRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
	
	/**
	 * 	
	 */
	private static final Gson gson = new Gson();

	private static final long MAX_UPLOAD_BYTES = 16L * 1024L * 1024L;
	private static final long MAX_AVATAR_UPLOAD_BYTES = 1L * 1024L * 1024L;

	private static final ExecutorService async = Executors.newVirtualThreadPerTaskExecutor();
	
	/**
	 * 	
	 */
	private CompletionService completionService = new CompletionService();
	
	
	
	public CompletionRouterHandler() {
		
	}
	
	
	
	@Override
	protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
		FullHttpRequest retained = msg.retainedDuplicate();
		async.execute(() -> {
			try {
				this.handleRequest(ctx, retained);
			} finally {
				ReferenceCountUtil.release(retained);
			}
		});
	}

	private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest msg) {
		String uri = msg.uri();
		if (uri == null) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "缺少URI");
			return;
		}
		if (HttpMethod.OPTIONS.equals(msg.method()) && uri.startsWith("/api/chat/completion")) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		
		if (uri.startsWith("/api/chat/completion")) {
			this.handleCompletionApi(ctx, msg, uri);
			return;
		}
		ctx.fireChannelRead(msg.retain());
	}
	
	/**
	 * 	处理API请求。
	 * @param ctx
	 * @param msg
	 * @param uri
	 */
	private void handleCompletionApi(ChannelHandlerContext ctx, FullHttpRequest msg, String uri) {
		try {
			String path = uri;
			String query = null;
			int qIdx = uri.indexOf('?');
			if (qIdx >= 0) {
				path = uri.substring(0, qIdx);
				query = uri.substring(qIdx + 1);
			}

			HttpMethod method = msg.method();
			
			if ("/api/chat/completion/list".equals(path) && HttpMethod.GET.equals(method)) {
				this.handleCharactorList(ctx);
				return;
			}

			if ("/api/chat/completion/create".equals(path) && HttpMethod.POST.equals(method)) {
				String body = msg.content().toString(CharsetUtil.UTF_8);
				this.handleCharactorCreate(ctx, body);
				return;
			}

			if ("/api/chat/completion/get".equals(path) && HttpMethod.GET.equals(method)) {
				String name = getQueryParam(query, "name");
				this.handleCharactorGet(ctx, name);
				return;
			}

			if ("/api/chat/completion/save".equals(path) && HttpMethod.POST.equals(method)) {
				String name = getQueryParam(query, "name");
				String body = msg.content().toString(CharsetUtil.UTF_8);
				this.handleCharactorSave(ctx, name, body);
				return;
			}

			if ("/api/chat/completion/delete".equals(path) && HttpMethod.DELETE.equals(method)) {
				String id = getQueryParam(query, "name");
				this.handleCharactorDelete(ctx, id);
				return;
			}

			if ("/api/chat/completion/file/upload".equals(path) && HttpMethod.POST.equals(method)) {
				this.handleChatFileUpload(ctx, msg);
				return;
			}

			if ("/api/chat/completion/file/download".equals(path) && HttpMethod.GET.equals(method)) {
				String name = getQueryParam(query, "name");
				this.handleChatFileDownload(ctx, name);
				return;
			}
			
			if ("/api/chat/completion/avatar/upload".equals(path) && HttpMethod.POST.equals(method)) {
				String name = getQueryParam(query, "name");
				this.handleAvatarUpload(ctx, msg, name);
				return;
			}
			
			if ("/api/chat/completion/avatar/get".equals(path) && HttpMethod.GET.equals(method)) {
				String name = getQueryParam(query, "name");
				this.handleAvatarGet(ctx, name);
				return;
			}

			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "404 Not Found");
		} catch (Exception e) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "服务器内部错误: " + e.getMessage());
		}
	}

	private void handleAvatarUpload(ChannelHandlerContext ctx, FullHttpRequest request, String charactorId) {
		if (charactorId == null || charactorId.trim().isEmpty()) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "缺少name参数");
			return;
		}
		if (request.content() == null || request.content().readableBytes() <= 0) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体为空");
			return;
		}
		if (request.content().readableBytes() > (MAX_AVATAR_UPLOAD_BYTES + 2L * 1024L * 1024L)) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "请求体过大");
			return;
		}
		
		HttpPostRequestDecoder decoder = null;
		try {
			decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), request);
			List<InterfaceHttpData> datas = decoder.getBodyHttpDatas();
			FileUpload upload = null;
			for (InterfaceHttpData d : datas) {
				if (d == null) continue;
				if (d.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
					FileUpload fu = (FileUpload) d;
					if (fu.isCompleted() && fu.length() > 0) {
						upload = fu;
						break;
					}
				}
			}
			if (upload == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "未找到上传文件");
				return;
			}
			if (upload.length() > MAX_AVATAR_UPLOAD_BYTES) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "头像文件超过最大限制: 1MB");
				return;
			}
			byte[] bytes = upload.get();
			if (bytes == null || bytes.length == 0) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "文件内容为空");
				return;
			}
			
			String savedName = this.completionService.saveAvatar(charactorId.trim(), bytes, upload.getFilename(), upload.getContentType());
			Map<String, Object> data = new HashMap<>();
			data.put("name", savedName);
			data.put("url", "/api/chat/completion/avatar/get?name=" + java.net.URLEncoder.encode(charactorId.trim(), StandardCharsets.UTF_8));
			Map<String, Object> resp = new HashMap<>();
			resp.put("success", true);
			resp.put("data", data);
			LlamaServer.sendJsonResponse(ctx, resp);
		} catch (Exception e) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "上传失败: " + e.getMessage());
		} finally {
			if (decoder != null) {
				try {
					decoder.destroy();
				} catch (Exception ignore) {
				}
			}
		}
	}
	
	private void handleAvatarGet(ChannelHandlerContext ctx, String charactorId) {
		if (charactorId == null || charactorId.trim().isEmpty()) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "缺少name参数");
			return;
		}
		Path file = this.completionService.getAvatarFilePath(charactorId.trim());
		if (file == null) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "头像不存在");
			return;
		}
		if (!Files.exists(file) || Files.isDirectory(file)) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "头像不存在");
			return;
		}
		try {
			sendInlineFile(ctx, file, guessImageContentType(file));
		} catch (Exception e) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "读取头像失败: " + e.getMessage());
		}
	}
	
	private static String guessImageContentType(Path file) {
		if (file == null) return "application/octet-stream";
		String n = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase();
		if (n.endsWith(".png")) return "image/png";
		if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
		if (n.endsWith(".gif")) return "image/gif";
		if (n.endsWith(".webp")) return "image/webp";
		return "application/octet-stream";
	}
	
	private static void sendInlineFile(ChannelHandlerContext ctx, Path file, String contentType) throws Exception {
		RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
		long fileLength = raf.length();
		
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType == null ? "application/octet-stream" : contentType);
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
		
		ctx.write(response);
		ctx.write(new ChunkedFile(raf, 0, fileLength, 8192), ctx.newProgressivePromise());
		ChannelFuture last = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
		last.addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				try {
					raf.close();
				} catch (Exception ignore) {
				}
				ctx.close();
			}
		});
	}

	private void handleChatFileUpload(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.content() == null || request.content().readableBytes() <= 0) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "请求体为空");
			return;
		}
		if (request.content().readableBytes() > (MAX_UPLOAD_BYTES + 2L * 1024L * 1024L)) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "请求体过大");
			return;
		}

		HttpPostRequestDecoder decoder = null;
		try {
			decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), request);
			List<InterfaceHttpData> datas = decoder.getBodyHttpDatas();
			FileUpload upload = null;
			for (InterfaceHttpData d : datas) {
				if (d == null) continue;
				if (d.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
					FileUpload fu = (FileUpload) d;
					if (fu.isCompleted() && fu.length() > 0) {
						upload = fu;
						break;
					}
				}
			}
			if (upload == null) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "未找到上传文件");
				return;
			}
			if (upload.length() > MAX_UPLOAD_BYTES) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "文件超过最大限制: 16MB");
				return;
			}
			byte[] bytes = upload.get();
			if (bytes == null || bytes.length == 0) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "文件内容为空");
				return;
			}
			String savedName = this.completionService.saveChatFile(bytes, upload.getFilename());
			Map<String, Object> data = new HashMap<>();
			data.put("name", savedName);
			Map<String, Object> resp = new HashMap<>();
			resp.put("success", true);
			resp.put("data", data);
			LlamaServer.sendJsonResponse(ctx, resp);
		} catch (Exception e) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "上传失败: " + e.getMessage());
		} finally {
			if (decoder != null) {
				try {
					decoder.destroy();
				} catch (Exception ignore) {
				}
			}
		}
	}

	private void handleChatFileDownload(ChannelHandlerContext ctx, String name) {
		if (name == null || name.trim().isEmpty()) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "缺少name参数");
			return;
		}
		Path file = this.completionService.getChatFilePath(name.trim());
		if (file == null) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, "非法文件名");
			return;
		}
		if (!Files.exists(file) || Files.isDirectory(file)) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "文件不存在");
			return;
		}
		try {
			sendDownloadFile(ctx, file, name.trim());
		} catch (Exception e) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "下载失败: " + e.getMessage());
		}
	}

	private static void sendDownloadFile(ChannelHandlerContext ctx, Path file, String downloadName) throws Exception {
		RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
		long fileLength = raf.length();

		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
		response.headers().set(HttpHeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");

		ctx.write(response);
		ctx.write(new ChunkedFile(raf, 0, fileLength, 8192), ctx.newProgressivePromise());
		ChannelFuture last = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
		last.addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				try {
					raf.close();
				} catch (Exception ignore) {
				}
				ctx.close();
			}
		});
	}
	
	/**
	 * 	列出全部的character，以JSON格式返回
	 * @param ctx
	 */
	private void handleCharactorList(ChannelHandlerContext ctx) {
		List<CharactorDataStruct> list = this.completionService.listCharactor();
		
		List<Map<String, Object>> slim = new ArrayList<>();
		for (CharactorDataStruct s : list) {
			if (s == null) continue;
			Map<String, Object> item = new HashMap<>();
			item.put("id", s.getId());
			item.put("title", s.getTitle());
			item.put("createdAt", s.getCreatedAt());
			item.put("updatedAt", s.getUpdatedAt());
			slim.add(item);
		}
		
		Map<String, Object> response = new HashMap<String, Object>();
		response.put("data", slim);
		response.put("success", true);
		
		LlamaServer.sendJsonResponse(ctx, response);
	}
	
	/**
	 * 	创建一个新的character
	 * @param ctx
	 * @param body
	 */
	private void handleCharactorCreate(ChannelHandlerContext ctx, String body) {
		CharactorDataStruct created = this.completionService.createDefaultCharactor();
		try {
			JsonObject json = gson.fromJson(body, JsonObject.class);
			if (json != null && json.has("title")) {
				String title = json.get("title").getAsString();
				if (title != null && !title.trim().isEmpty()) {
					created.setTitle(title.trim());
					created.setUpdatedAt(System.currentTimeMillis());
					this.completionService.saveCharactor(created);
				}
			}
		} catch (Exception ignore) {
		}
		
		Map<String, Object> response = new HashMap<String, Object>();
		response.put("data", created);
		response.put("success", true);
		LlamaServer.sendJsonResponse(ctx, response);
	}
	
	/**
	 * 	获取指定角色的信息
	 * @param ctx
	 * @param id
	 */
	private void handleCharactorGet(ChannelHandlerContext ctx, String name) {
		CharactorDataStruct charactorDataStruct =  this.completionService.getCharactor(name);
		if (charactorDataStruct == null) {
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "找不到指定的角色：" + name);
			return;
		}
		
		Map<String, Object> response = new HashMap<String, Object>();
		response.put("success", true);
		response.put("message", "success");
		response.put("data", charactorDataStruct);
		
		LlamaServer.sendJsonResponse(ctx, response);
	}
	
	/**
	 * 	保存角色信息。
	 * @param ctx
	 * @param id
	 * @param body
	 */
	private void handleCharactorSave(ChannelHandlerContext ctx, String name, String body) {
		Map<String, Object> response = new HashMap<String, Object>();
		try {
			CharactorDataStruct charactorDataStruct = gson.fromJson(body, CharactorDataStruct.class);
			try {
				Long id = name == null ? null : Long.parseLong(name.trim());
				if (id != null && id.longValue() > 0) {
					if (charactorDataStruct != null && charactorDataStruct.getId() != id.longValue()) {
						charactorDataStruct.setId(id.longValue());
					}
				}
			} catch (Exception ignore) {
			}
			this.completionService.saveCharactor(charactorDataStruct);
			response.put("success", true);
			LlamaServer.sendJsonResponse(ctx, response);
			return;
		}catch (Exception e) {
			e.printStackTrace();
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
		}
	}

	/**
	 * 	删除一个角色
	 * @param ctx
	 * @param name
	 */
	private void handleCharactorDelete(ChannelHandlerContext ctx, String name) {
		Map<String, Object> response = new HashMap<String, Object>();
		try {
			boolean ok = this.completionService.deleteCharactor(name);
			response.put("success", ok);
			if (!ok) {
				LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.NOT_FOUND, "找不到指定的角色：" + name);
				return;
			}
		}catch (Exception e) {
			e.printStackTrace();
			LlamaServer.sendJsonErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
			return;
		}
		LlamaServer.sendJsonResponse(ctx, response);
	}

	/**
	 * 	
	 * @param query
	 * @param key
	 * @return
	 */
	private static String getQueryParam(String query, String key) {
		if (query == null || query.isEmpty() || key == null || key.isEmpty())
			return null;
		String[] parts = query.split("&");
		for (String p : parts) {
			int idx = p.indexOf('=');
			if (idx < 0)
				continue;
			String k = p.substring(0, idx);
			if (!key.equals(k))
				continue;
			String v = p.substring(idx + 1);
			try {
				return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8);
			} catch (Exception e) {
				return v;
			}
		}
		return null;
	}
}
