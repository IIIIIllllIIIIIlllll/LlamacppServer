package org.mark.llamacpp.server.controller;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.ModelPathConfig;
import org.mark.llamacpp.server.struct.ModelPathDataStruct;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;

/**
 * 	
 */
public class ModelPathController implements BaseController {

	
	private static final Logger logger = LoggerFactory.getLogger(ModelPathController.class);
	
	
	public ModelPathController() {
		
	}
	
	
	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (uri.startsWith("/api/model/path/add")) {
			this.handleModelPathAdd(ctx, request);
			return true;
		}

		if (uri.startsWith("/api/model/path/remove")) {
			this.handleModelPathRemove(ctx, request);
			return true;
		}

		if (uri.startsWith("/api/model/path/update")) {
			this.handleModelPathUpdate(ctx, request);
			return true;
		}

		if (uri.startsWith("/api/model/path/list")) {
			this.handleModelPathList(ctx, request);
			return true;
		}
		
		return false;
	}
	
	
	/**
	 * 添加模型路径
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelPathAdd(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			ModelPathDataStruct reqData = JsonUtil.fromJson(content, ModelPathDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}

			LlamaServerManager manager = LlamaServerManager.getInstance();
			Path configFile = LlamaServer.getModelPathConfigPath();
			ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
			cfg = this.ensureModelPathConfigInitialized(cfg, manager.getModelPaths(), configFile);
			List<ModelPathDataStruct> items = cfg.getItems();
			if (items == null) {
				items = new ArrayList<>();
				cfg.setItems(items);
			}
			String normalized = reqData.getPath().trim();
			Path validated = this.validateAndNormalizeDirectory(normalized);
			if (validated == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("目录无效或不可访问"));
				return;
			}
			normalized = validated.toString();
			boolean exists = false;
			for (ModelPathDataStruct i : items) {
				if (i != null && i.getPath() != null && this.isSamePath(normalized, i.getPath().trim())) {
					exists = true;
					break;
				}
			}
			if (exists) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("路径已存在"));
				return;
			}
			ModelPathDataStruct item = new ModelPathDataStruct();
			item.setPath(normalized);
			String name = reqData.getName();
			if (name == null || name.trim().isEmpty()) {
				try {
					name = java.nio.file.Paths.get(normalized).getFileName().toString();
				} catch (Exception ex) {
					name = normalized;
				}
			}
			item.setName(name);
			item.setDescription(reqData.getDescription());
			items.add(item);
			LlamaServer.writeModelPathConfig(configFile, cfg);
			this.syncModelPathsToRuntime(manager, cfg, true);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "添加模型路径成功");
			data.put("added", item);
			data.put("count", items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("添加模型路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("添加模型路径失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 移除模型路径
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelPathRemove(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			ModelPathDataStruct reqData = JsonUtil.fromJson(content, ModelPathDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}
			String normalized = reqData.getPath().trim();

			LlamaServerManager manager = LlamaServerManager.getInstance();
			Path configFile = LlamaServer.getModelPathConfigPath();
			ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
			cfg = this.ensureModelPathConfigInitialized(cfg, manager.getModelPaths(), configFile);
			List<ModelPathDataStruct> items = cfg.getItems();
			int before = items == null ? 0 : items.size();
			boolean changed = false;
			if (items != null) {
				changed = items.removeIf(i -> this.isSamePath(normalized, i == null || i.getPath() == null ? "" : i.getPath().trim()));
			}

			LlamaServer.writeModelPathConfig(configFile, cfg);
			this.syncModelPathsToRuntime(manager, cfg, true);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "移除模型路径成功");
			data.put("removed", normalized);
			data.put("count", items == null ? 0 : items.size());
			data.put("changed", changed || before != (items == null ? 0 : items.size()));
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("移除模型路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("移除模型路径失败: " + e.getMessage()));
		}
	}
	
	
	/**
	 * 更新模型路径（原地修改）
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelPathUpdate(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			JsonObject obj = JsonUtil.fromJson(content, JsonObject.class);
			if (obj == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体解析失败"));
				return;
			}
			String originalPath = obj.has("originalPath") ? obj.get("originalPath").getAsString() : null;
			String newPath = obj.has("path") ? obj.get("path").getAsString() : null;
			String name = obj.has("name") ? obj.get("name").getAsString() : null;
			String description = obj.has("description") ? obj.get("description").getAsString() : null;

			if (originalPath == null || originalPath.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("originalPath不能为空"));
				return;
			}
			if (newPath == null || newPath.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}

			String originalNormalized = originalPath.trim();
			String newNormalized = newPath.trim();
			Path validated = this.validateAndNormalizeDirectory(newNormalized);
			if (validated == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("目录无效或不可访问"));
				return;
			}
			newNormalized = validated.toString();

			LlamaServerManager manager = LlamaServerManager.getInstance();
			Path configFile = LlamaServer.getModelPathConfigPath();
			ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
			cfg = this.ensureModelPathConfigInitialized(cfg, manager.getModelPaths(), configFile);

			List<ModelPathDataStruct> items = cfg.getItems();
			if (items == null || items.isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到可更新的路径配置"));
				return;
			}

			ModelPathDataStruct target = null;
			for (ModelPathDataStruct i : items) {
				if (i == null || i.getPath() == null) continue;
				if (this.isSamePath(originalNormalized, i.getPath().trim())) {
					target = i;
					break;
				}
			}
			if (target == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("未找到要更新的路径: " + originalNormalized));
				return;
			}

			boolean pathChanged = !this.isSamePath(originalNormalized, newNormalized);
			if (pathChanged) {
				for (ModelPathDataStruct i : items) {
					if (i == null || i.getPath() == null) continue;
					if (i == target) continue;
					if (this.isSamePath(newNormalized, i.getPath().trim())) {
						LlamaServer.sendJsonResponse(ctx, ApiResponse.error("路径已存在"));
						return;
					}
				}
			}

			target.setPath(newNormalized);
			if (name == null || name.trim().isEmpty()) {
				try {
					name = Paths.get(newNormalized).getFileName().toString();
				} catch (Exception ex) {
					name = newNormalized;
				}
			}
			target.setName(name);
			target.setDescription(description);

			LlamaServer.writeModelPathConfig(configFile, cfg);
			this.syncModelPathsToRuntime(manager, cfg, pathChanged);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "更新模型路径成功");
			data.put("updated", target);
			data.put("count", items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("更新模型路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("更新模型路径失败: " + e.getMessage()));
		}
	}
	
	
	/**
	 * 	
	 * @param cfg
	 * @param legacyPaths
	 * @param configFile
	 * @return
	 * @throws Exception
	 */
	private ModelPathConfig ensureModelPathConfigInitialized(ModelPathConfig cfg, List<ModelPathDataStruct> legacyPaths, Path configFile)
			throws Exception {
		if (cfg == null) {
			cfg = new ModelPathConfig();
		}
		List<ModelPathDataStruct> items = cfg.getItems();
		boolean empty = items == null || items.isEmpty();
		if (!empty) {
			return cfg;
		}
		if (legacyPaths == null || legacyPaths.isEmpty()) {
			return cfg;
		}
		List<ModelPathDataStruct> migrated = new ArrayList<>();
		for (ModelPathDataStruct p : legacyPaths) {
			if (p == null || p.getPath().trim().isEmpty()) {
				continue;
			}
			String normalized = p.getPath().trim();
			boolean exists = false;
			for (ModelPathDataStruct i : migrated) {
				if (i != null && i.getPath() != null && this.isSamePath(normalized, i.getPath().trim())) {
					exists = true;
					break;
				}
			}
			if (exists) {
				continue;
			}
			ModelPathDataStruct item = new ModelPathDataStruct();
			item.setPath(normalized);
			try {
				item.setName(java.nio.file.Paths.get(normalized).getFileName().toString());
			} catch (Exception ex) {
				item.setName(normalized);
			}
			migrated.add(item);
		}
		cfg.setItems(migrated);
		LlamaServer.writeModelPathConfig(configFile, cfg);
		return cfg;
	}
	
	/**
	 * 返回全部的模型路径
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleModelPathList(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			LlamaServerManager manager = LlamaServerManager.getInstance();
			Path configFile = LlamaServer.getModelPathConfigPath();
			ModelPathConfig cfg = LlamaServer.readModelPathConfig(configFile);
			cfg = this.ensureModelPathConfigInitialized(cfg, manager.getModelPaths(), configFile);
			List<ModelPathDataStruct> items = cfg.getItems();
			Map<String, Object> data = new HashMap<>();
			data.put("items", items);
			data.put("count", items == null ? 0 : items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取模型路径列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取模型路径列表失败: " + e.getMessage()));
		}
	}


	/**
	 * 	
	 * @param manager
	 * @param cfg
	 * @param refreshModelList
	 */
	private void syncModelPathsToRuntime(LlamaServerManager manager, ModelPathConfig cfg, boolean refreshModelList) {
		if (manager == null || cfg == null) {
			return;
		}
		List<ModelPathDataStruct> items = cfg.getItems();
		List<ModelPathDataStruct> paths = new ArrayList<>();
		if (items != null) {
			for (ModelPathDataStruct i : items) {
				if (i == null || i.getPath() == null || i.getPath().trim().isEmpty()) {
					continue;
				}
				String p = i.getPath().trim();
				boolean exists = false;
				for (ModelPathDataStruct e : paths) {
					if (this.isSamePath(p, e.getPath().trim())) {
						exists = true;
						break;
					}
				}
				if (!exists) {
					paths.add(i);
				}
			}
		}
		manager.setModelPaths(paths);
		if (refreshModelList) {
			try {
				manager.listModel(true);
			} catch (Exception e) {
				logger.info("刷新模型列表失败: {}", e.getMessage());
			}
		}
	}
	
	/**
	 * 	
	 * @param a
	 * @param b
	 * @return
	 */
	private boolean isSamePath(String a, String b) {
		String aa = this.normalizePathForCompare(a);
		String bb = this.normalizePathForCompare(b);
		if (aa.isEmpty() || bb.isEmpty()) return false;
		String os = System.getProperty("os.name");
		if (os != null && os.toLowerCase(Locale.ROOT).contains("win")) return aa.equalsIgnoreCase(bb);
		return aa.equals(bb);
	}
	
	/**
	 * 	
	 * @param p
	 * @return
	 */
	private String normalizePathForCompare(String p) {
		if (p == null) return "";
		String s = p.trim();
		if (s.isEmpty()) return "";
		String os = System.getProperty("os.name");
		boolean win = os != null && os.toLowerCase(Locale.ROOT).contains("win");
		if (win) {
			s = s.replace('/', '\\');
		}

		while (s.length() > 1) {
			char last = s.charAt(s.length() - 1);
			if (last != '\\' && last != '/') break;
			if (win && s.length() == 3 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':' && (last == '\\' || last == '/')) {
				break;
			}
			if (win && "\\\\".equals(s)) {
				break;
			}
			s = s.substring(0, s.length() - 1);
		}

		try {
			s = java.nio.file.Paths.get(s).normalize().toString();
		} catch (Exception e) {
		}
		return s;
	}
	
	private Path validateAndNormalizeDirectory(String input) {
		if (input == null) return null;
		String s = input.trim();
		if (s.isEmpty()) return null;
		Path p;
		try {
			p = Paths.get(s).toAbsolutePath().normalize();
		} catch (Exception e) {
			return null;
		}
		try {
			if (!Files.exists(p) || !Files.isDirectory(p)) return null;
		} catch (Exception e) {
			return null;
		}
		if (this.pathHasSymlink(p)) return null;
		try {
			return p.toRealPath();
		} catch (Exception e) {
			return p;
		}
	}
	
	private boolean pathHasSymlink(Path p) {
		if (p == null) return false;
		try {
			Path abs = p.toAbsolutePath().normalize();
			Path root = abs.getRoot();
			if (root == null) {
				return Files.isSymbolicLink(abs);
			}
			Path cur = root;
			for (Path part : abs) {
				if (part == null) continue;
				cur = cur.resolve(part);
				try {
					if (Files.isSymbolicLink(cur)) return true;
				} catch (Exception ignore) {
				}
			}
			return false;
		} catch (Exception e) {
			return false;
		}
	}
}
