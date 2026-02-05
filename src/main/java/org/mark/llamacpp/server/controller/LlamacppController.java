package org.mark.llamacpp.server.controller;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.struct.LlamaCppConfig;
import org.mark.llamacpp.server.struct.LlamaCppDataStruct;
import org.mark.llamacpp.server.tools.CommandLineRunner;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;

/**
 * 	
 */
public class LlamacppController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(LlamacppController.class);
	
	
	public LlamacppController() {
		
	}
	
	/**
	 * 	
	 */
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 添加一个llamacpp
		if (uri.startsWith("/api/llamacpp/add")) {
			this.handleLlamaCppAdd(ctx, request);
			return true;
		}
		// 移除
		if (uri.startsWith("/api/llamacpp/remove")) {
			this.handleLlamaCppRemove(ctx, request);
			return true;
		}
		// 列出全部
		if (uri.startsWith("/api/llamacpp/list")) {
			this.handleLlamaCppList(ctx, request);
			return true;
		}
		// 执行测试
		if (uri.startsWith("/api/llamacpp/test")) {
			this.handleLlamaCppTest(ctx, request);
			return true;
		}
		
		return false;
	}
	
	
	
	/**
	 * 添加llamacpp目录
	 *
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleLlamaCppAdd(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			LlamaCppDataStruct reqData = JsonUtil.fromJson(content, LlamaCppDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}

			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<LlamaCppDataStruct> items = cfg.getItems();
			if (items == null) {
				items = new ArrayList<>();
				cfg.setItems(items);
			}
			String normalized = reqData.getPath().trim();
			Path validated = this.validateAndNormalizeLlamaBinDirectory(normalized);
			if (validated == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("目录无效、不可访问或缺少llama-server可执行文件"));
				return;
			}
			normalized = validated.toString();
			boolean exists = false;
			for (LlamaCppDataStruct i : items) {
				if (i != null && i.getPath() != null && normalized.equals(i.getPath().trim())) {
					exists = true;
					break;
				}
			}
			if (exists) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("路径已存在"));
				return;
			}
			LlamaCppDataStruct item = new LlamaCppDataStruct();
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
			LlamaServer.writeLlamaCppConfig(configFile, cfg);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "添加llama.cpp路径成功");
			data.put("added", item);
			data.put("count", items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("添加llama.cpp路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("添加llama.cpp路径失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 移除一个llamcpp目录
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleLlamaCppRemove(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			LlamaCppDataStruct reqData = JsonUtil.fromJson(content, LlamaCppDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}
			String normalized = reqData.getPath().trim();

			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<LlamaCppDataStruct> items = cfg.getItems();
			int before = items == null ? 0 : items.size();
			boolean changed = false;
			if (items != null) {
				changed = items.removeIf(i -> normalized.equals(i == null || i.getPath() == null ? "" : i.getPath().trim()));
			}
			LlamaServer.writeLlamaCppConfig(configFile, cfg);

			Map<String, Object> data = new HashMap<>();
			data.put("message", "移除llama.cpp路径成功");
			data.put("removed", normalized);
			data.put("count", items == null ? 0 : items.size());
			data.put("changed", changed || before != (items == null ? 0 : items.size()));
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("移除llama.cpp路径时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("移除llama.cpp路径失败: " + e.getMessage()));
		}
	}
	
	private Path validateAndNormalizeLlamaBinDirectory(String input) {
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
		Path real;
		try {
			real = p.toRealPath();
		} catch (Exception e) {
			real = p;
		}
		
		File dir = real.toFile();
		File serverLinux = new File(dir, "llama-server");
		File serverWin = new File(dir, "llama-server.exe");
		if (!(serverLinux.exists() && serverLinux.isFile()) && !(serverWin.exists() && serverWin.isFile())) {
			return null;
		}
		return real;
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
	
	/**
	 * 返回全部的llamacpp目录
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleLlamaCppList(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Path configFile = LlamaServer.getLlamaCppConfigPath();
			LlamaCppConfig cfg = LlamaServer.readLlamaCppConfig(configFile);
			List<LlamaCppDataStruct> items = cfg.getItems();
			// 扫描一遍，加入新的。
			List<LlamaCppDataStruct> list = LlamaServer.scanLlamaCpp();
			if(list != null && list.size() > 0)
				items.addAll(list);
			
			Map<String, Object> data = new HashMap<>();
			data.put("items", items);
			data.put("count", items == null ? 0 : items.size());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取llama.cpp路径列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取llama.cpp路径列表失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 	
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException
	 */
	private void handleLlamaCppTest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}
			LlamaCppDataStruct reqData = JsonUtil.fromJson(content, LlamaCppDataStruct.class);
			if (reqData == null || reqData.getPath() == null || reqData.getPath().trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("path不能为空"));
				return;
			}

			String llamaBinPath = reqData.getPath().trim();
			String exeName = "llama-cli";
			File exeFile = new File(llamaBinPath, exeName);
			if (!exeFile.exists() || !exeFile.isFile()) {
				String osName = System.getProperty("os.name");
				String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
				if (os.contains("win")) {
					File exeFileWin = new File(llamaBinPath, exeName + ".exe");
					if (exeFileWin.exists() && exeFileWin.isFile()) {
						exeFile = exeFileWin;
					}
				}
			}
			if (!exeFile.exists() || !exeFile.isFile()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("llama-cli可执行文件不存在: " + exeFile.getAbsolutePath()));
				return;
			}

			String cmdVersion = ParamTool.quoteIfNeeded(exeFile.getAbsolutePath()) + " --version";
			CommandLineRunner.CommandResult versionResult = CommandLineRunner.execute(
					new String[] { exeFile.getAbsolutePath(), "--version" }, 30);

			String cmdListDevices = ParamTool.quoteIfNeeded(exeFile.getAbsolutePath()) + " --list-devices";
			CommandLineRunner.CommandResult listDevicesResult = CommandLineRunner.execute(
					new String[] { exeFile.getAbsolutePath(), "--list-devices" }, 30);

			Map<String, Object> data = new HashMap<>();

			Map<String, Object> version = new HashMap<>();
			version.put("command", cmdVersion);
			version.put("exitCode", versionResult.getExitCode());
			version.put("output", versionResult.getOutput());
			version.put("error", versionResult.getError());

			Map<String, Object> devices = new HashMap<>();
			devices.put("command", cmdListDevices);
			devices.put("exitCode", listDevicesResult.getExitCode());
			devices.put("output", listDevicesResult.getOutput());
			devices.put("error", listDevicesResult.getError());

			data.put("version", version);
			data.put("listDevices", devices);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("执行llama.cpp测试命令时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("执行llama.cpp测试失败: " + e.getMessage()));
		}
	}
}
