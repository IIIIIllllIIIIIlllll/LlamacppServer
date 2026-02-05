package org.mark.llamacpp.server.controller;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.mark.llamacpp.lmstudio.LMStudio;
import org.mark.llamacpp.ollama.Ollama;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.CommandLineRunner;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.CharsetUtil;



/**
 * 	系统相关。
 */
public class SystemController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(SystemController.class);
	
	
	
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 停止服务API
		if (uri.startsWith("/api/shutdown")) {
			this.handleShutdownRequest(ctx, request);
			return true;
		}
		// 控制台
		if (uri.startsWith("/api/sys/console")) {
			this.handleSysConsoleRequest(ctx, request);
			return true;
		}
		
		// 列出可用的设备，基于当前选择的llamacpp
		if (uri.startsWith("/api/model/device/list")) {
			this.handleDeviceListRequest(ctx, request);
			return true;
		}
		
		// 显存估算API
		if (uri.startsWith("/api/models/vram/estimate")) {
			this.handleVramEstimateRequest(ctx, request);
			return true;
		}
		// 启用、禁用ollama兼容api
		if (uri.startsWith("/api/sys/ollama")) {
			this.handleOllamaEnableRequest(ctx, request);
			return true;
		}
		// 启用、禁用lmstudio
		if (uri.startsWith("/api/sys/lmstudio")) {
			this.handleLmstudioEnableRequest(ctx, request);
			return true;
		}
		// 获取兼容服务状态
		if (uri.startsWith("/api/sys/compat/status")) {
			this.handleCompatStatusRequest(ctx, request);
			return true;
		}
		// 保存系统设置
		if (uri.startsWith("/api/sys/setting")) {
			this.handleSysSettingRequest(ctx, request);
			return true;
		}
		// 保存搜索设置
		if (uri.startsWith("/api/search/setting")) {
			this.handleSearchSettingRequest(ctx, request);
			return true;
		}
		
		// 文件系统：目录浏览
		if (uri.startsWith("/api/sys/fs/list")) {
			this.handleFsListRequest(ctx, request);
			return true;
		}
		
		
		return false;
	}

	private void handleFsListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			String in = params.get("path");
			if (in != null) in = in.trim();
			
			int fileLimit = 10;
			int dirLimit = 500;
			
			Map<String, Object> data = new HashMap<>();
			
			if (in == null || in.isEmpty()) {
				List<Map<String, Object>> dirs = new ArrayList<>();
				File[] roots = File.listRoots();
				if (roots != null) {
					for (File r : roots) {
						if (r == null) continue;
						String p = r.getAbsolutePath();
						Map<String, Object> item = new HashMap<>();
						item.put("name", p);
						item.put("path", p);
						dirs.add(item);
					}
				}
				dirs.sort(Comparator.comparing(o -> String.valueOf(o.getOrDefault("name", "")), String.CASE_INSENSITIVE_ORDER));
				
				data.put("path", null);
				data.put("parent", null);
				data.put("directories", dirs);
				data.put("files", new ArrayList<>());
				data.put("truncatedDirs", false);
				data.put("truncatedFiles", false);
				data.put("mode", "roots");
				LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
				return;
			}
			
			Path raw;
			try {
				raw = Paths.get(in);
			} catch (Exception e) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("非法路径"));
				return;
			}
			Path abs = raw.toAbsolutePath().normalize();
			if (!Files.exists(abs)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("目录不存在"));
				return;
			}
			if (!Files.isDirectory(abs)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("不是目录"));
				return;
			}
			if (this.pathHasSymlink(abs)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("不允许使用符号链接目录"));
				return;
			}
			
			Path base;
			try {
				base = abs.toRealPath();
			} catch (Exception e) {
				base = abs;
			}
			if (!Files.isDirectory(base)) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("不是目录"));
				return;
			}
			
			final List<Map<String, Object>> dirs = new ArrayList<>();
			final List<Map<String, Object>> files = new ArrayList<>();
			boolean truncatedDirs = false;
			boolean truncatedFiles = false;
			
			try (Stream<Path> stream = Files.list(base)) {
				stream.forEach(p -> {
					if (p == null) return;
					try {
						String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
						if (Files.isDirectory(p)) {
							Map<String, Object> item = new HashMap<>();
							item.put("name", name);
							item.put("path", p.toAbsolutePath().normalize().toString());
							dirs.add(item);
							return;
						}
						Map<String, Object> item = new HashMap<>();
						item.put("name", name);
						files.add(item);
					} catch (Exception ignore) {
					}
				});
			}
			
			dirs.sort(Comparator.comparing(o -> String.valueOf(o.getOrDefault("name", "")), String.CASE_INSENSITIVE_ORDER));
			files.sort(Comparator.comparing(o -> String.valueOf(o.getOrDefault("name", "")), String.CASE_INSENSITIVE_ORDER));
			
			List<Map<String, Object>> outDirs = dirs;
			List<Map<String, Object>> outFiles = files;
			
			if (outDirs.size() > dirLimit) {
				outDirs = new ArrayList<>(outDirs.subList(0, dirLimit));
				truncatedDirs = true;
			}
			if (outFiles.size() > fileLimit) {
				outFiles = new ArrayList<>(outFiles.subList(0, fileLimit));
				truncatedFiles = true;
			}
			
			Path parent = base.getParent();
			data.put("path", base.toString());
			data.put("parent", parent == null ? null : parent.toString());
			data.put("directories", outDirs);
			data.put("files", outFiles);
			data.put("truncatedDirs", truncatedDirs);
			data.put("truncatedFiles", truncatedFiles);
			data.put("mode", "directory");
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("处理目录浏览请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("目录浏览失败: " + e.getMessage()));
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
					if (Files.isSymbolicLink(cur)) {
						return true;
					}
				} catch (Exception ignore) {
				}
			}
			return false;
		} catch (Exception e) {
			return false;
		}
	}
	
	private void handleCompatStatusRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Ollama ollama = Ollama.getInstance();
			LMStudio lmstudio = LMStudio.getInstance();
			
			Map<String, Object> data = new HashMap<>();
			
			Map<String, Object> ollamaData = new HashMap<>();
			ollamaData.put("enabled", LlamaServer.isOllamaCompatEnabled());
			ollamaData.put("configuredPort", LlamaServer.getOllamaCompatPort());
			ollamaData.put("running", ollama.isRunning());
			ollamaData.put("port", ollama.getPort());
			data.put("ollama", ollamaData);
			
			Map<String, Object> lmstudioData = new HashMap<>();
			lmstudioData.put("enabled", LlamaServer.isLmstudioCompatEnabled());
			lmstudioData.put("configuredPort", LlamaServer.getLmstudioCompatPort());
			lmstudioData.put("running", lmstudio.isRunning());
			lmstudioData.put("port", lmstudio.getPort());
			data.put("lmstudio", lmstudioData);
			
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取兼容服务状态时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取兼容服务状态失败: " + e.getMessage()));
		}
	}
	
	private void handleOllamaEnableRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
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
			if (!obj.has("enable") || obj.get("enable") == null || obj.get("enable").isJsonNull()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的enable参数"));
				return;
			}
			
			boolean enable = ParamTool.parseJsonBoolean(obj, "enable", false);
			Integer port = JsonUtil.getJsonInt(obj, "port", null);
			int bindPort = port == null ? LlamaServer.getOllamaCompatPort() : port.intValue();
			if (bindPort <= 0 || bindPort > 65535) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("port参数不合法"));
				return;
			}
			
			Ollama ollama = Ollama.getInstance();
			if (enable) {
				ollama.start(bindPort);
			} else {
				ollama.stop();
			}
			
			LlamaServer.updateOllamaCompatConfig(enable, bindPort);
			
			Map<String, Object> data = new HashMap<>();
			data.put("enable", enable);
			data.put("port", bindPort);
			data.put("running", ollama.isRunning());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("处理ollama启停请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("处理ollama启停失败: " + e.getMessage()));
		}
	}
	
	private void handleLmstudioEnableRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
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
			if (!obj.has("enable") || obj.get("enable") == null || obj.get("enable").isJsonNull()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的enable参数"));
				return;
			}
			
			boolean enable = ParamTool.parseJsonBoolean(obj, "enable", false);
			Integer port = JsonUtil.getJsonInt(obj, "port", null);
			int bindPort = port == null ? LlamaServer.getLmstudioCompatPort() : port.intValue();
			if (bindPort <= 0 || bindPort > 65535) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("port参数不合法"));
				return;
			}
			
			LMStudio lmstudio = LMStudio.getInstance();
			if (enable) {
				lmstudio.start(bindPort);
			} else {
				lmstudio.stop();
			}
			
			LlamaServer.updateLmstudioCompatConfig(enable, bindPort);
			
			Map<String, Object> data = new HashMap<>();
			data.put("enable", enable);
			data.put("port", bindPort);
			data.put("running", lmstudio.isRunning());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("处理lmstudio启停请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("处理lmstudio启停失败: " + e.getMessage()));
		}
	}

	private void handleSysSettingRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
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

			Integer ollamaPort = firstPort(obj, "ollamaPort", "ollama_port", "ollamaCompatPort", "ollama_compat_port");
			Integer lmstudioPort = firstPort(obj, "lmstudioPort", "lmstudio_port", "lmstudioCompatPort", "lmstudio_compat_port");

			if (ollamaPort == null && lmstudioPort == null) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少端口参数"));
				return;
			}

			if (ollamaPort != null) {
				if (!isValidPort(ollamaPort.intValue())) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("ollamaPort参数不合法"));
					return;
				}
				LlamaServer.updateOllamaCompatConfig(LlamaServer.isOllamaCompatEnabled(), ollamaPort.intValue());
			}

			if (lmstudioPort != null) {
				if (!isValidPort(lmstudioPort.intValue())) {
					LlamaServer.sendJsonResponse(ctx, ApiResponse.error("lmstudioPort参数不合法"));
					return;
				}
				LlamaServer.updateLmstudioCompatConfig(LlamaServer.isLmstudioCompatEnabled(), lmstudioPort.intValue());
			}

			Map<String, Object> data = new HashMap<>();
			Map<String, Object> ollama = new HashMap<>();
			ollama.put("enabled", LlamaServer.isOllamaCompatEnabled());
			ollama.put("port", LlamaServer.getOllamaCompatPort());
			data.put("ollama", ollama);

			Map<String, Object> lmstudio = new HashMap<>();
			lmstudio.put("enabled", LlamaServer.isLmstudioCompatEnabled());
			lmstudio.put("port", LlamaServer.getLmstudioCompatPort());
			data.put("lmstudio", lmstudio);

			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("处理系统设置请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存系统设置失败: " + e.getMessage()));
		}
	}

	private void handleSearchSettingRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.OPTIONS) {
			LlamaServer.sendCorsResponse(ctx);
			return;
		}
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

			String apiKey = JsonUtil.getJsonString(obj, "zhipu_search_apikey", null);
			if (apiKey == null) {
				apiKey = JsonUtil.getJsonString(obj, "apiKey", null);
			}
			apiKey = apiKey == null ? "" : apiKey.trim();

			JsonObject out = new JsonObject();
			out.addProperty("apiKey", apiKey);
			String json = JsonUtil.toJson(out);

			Path configPath = Paths.get("config", "zhipu_search.json");
			if (!Files.exists(configPath.getParent())) {
				Files.createDirectories(configPath.getParent());
			}
			Files.write(configPath, json.getBytes(StandardCharsets.UTF_8));

			Map<String, Object> data = new HashMap<>();
			data.put("saved", true);
			data.put("file", configPath.toString());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("处理搜索设置请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存搜索设置失败: " + e.getMessage()));
		}
	}

	private static Integer firstPort(JsonObject obj, String... keys) {
		if (obj == null || keys == null) {
			return null;
		}
		for (String k : keys) {
			Integer v = JsonUtil.getJsonInt(obj, k, null);
			if (v != null) {
				return v;
			}
		}
		return null;
	}

	private static boolean isValidPort(int port) {
		return port > 0 && port <= 65535;
	}
	
	
	/**
	 * 处理停止服务请求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleShutdownRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		
		try {
			logger.info("收到停止服务请求");

			// 先发送响应，然后再执行关闭操作
			Map<String, Object> data = new HashMap<>();
			data.put("message", "服务正在停止，所有模型进程将被终止");

			// 发送响应
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));

			// 在新线程中执行关闭操作，避免阻塞响应发送
			new Thread(() -> {
				try {
					// 等待一小段时间确保响应已发送
					Thread.sleep(500);

					// 调用LlamaServerManager停止所有进程并退出
					LlamaServerManager manager = LlamaServerManager.getInstance();
					manager.shutdownAll();
					//
					System.exit(0);
				} catch (Exception e) {
					logger.info("停止服务时发生错误", e);
				}
			}).start();

		} catch (Exception e) {
			logger.info("处理停止服务请求时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("停止服务失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 处理控制台的请求。
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleSysConsoleRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Path logPath = LlamaServer.getConsoleLogPath();
			File file = logPath.toFile();
			if (!file.exists()) {
				LlamaServer.sendTextResponse(ctx, "");
				return;
			}
			long max = 1L * 256 * 1024;
			try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
				long len = raf.length();
				long start = Math.max(0, len - max);
				raf.seek(start);
				int toRead = (int) Math.min(max, len - start);
				byte[] buf = new byte[toRead];
				int read = raf.read(buf);
				if (read <= 0) {
					LlamaServer.sendTextResponse(ctx, "");
					return;
				}
				String text = new String(buf, 0, read, StandardCharsets.UTF_8);
				LlamaServer.sendTextResponse(ctx, text);
			}
		} catch (Exception e) {
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("读取控制台日志失败: " + e.getMessage()));
		}
	}
	
	
	/**
	 * 处理设备列表请求 执行 llama-bench --list-devices 命令获取可用设备列表
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleDeviceListRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		
		try {
			// 从URL参数中提取 llamaBinPath
			String query = request.uri();
			String llamaBinPath = null;
			
			Map<String, String> params = ParamTool.getQueryParam(query);
			llamaBinPath = params.get("llamaBinPath");

			// 验证必需的参数
			if (llamaBinPath == null || llamaBinPath.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的llamaBinPath参数"));
				return;
			}

			List<String> devices = LlamaServerManager.getInstance().handleListDevices(llamaBinPath);

			String executableName = "llama-bench";
			// 拼接完整命令路径
			String command = llamaBinPath.trim();
			command += File.separator;

			command += executableName + " --list-devices";

			// 执行命令
			CommandLineRunner.CommandResult result = CommandLineRunner.execute(command, 30);
			// 构建响应数据
			Map<String, Object> data = new HashMap<>();
			data.put("command", command);
			data.put("exitCode", result.getExitCode());
			data.put("output", result.getOutput());
			data.put("error", result.getError());
			data.put("devices", devices);

			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("获取设备列表时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("获取设备列表失败: " + e.getMessage()));
		}
	}
	
	/**
	 * 估算模型显存需求
	 * 
	 * @param ctx
	 * @param request
	 * @throws RequestMethodException 
	 */
	private void handleVramEstimateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		// 断言一下请求方式
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		
		try {
			// 读取请求体
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体为空"));
				return;
			}

			JsonElement root = JsonUtil.fromJson(content, JsonElement.class);
			if (root == null || !root.isJsonObject()) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("请求体必须为JSON对象"));
				return;
			}

			JsonObject obj = root.getAsJsonObject();
			String cmd = JsonUtil.getJsonString(obj, "cmd", "");
			String extraParams = JsonUtil.getJsonString(obj, "extraParams", "");
			if (cmd != null) cmd = cmd.trim();
			if (extraParams != null) extraParams = extraParams.trim();
			if ((cmd == null || cmd.isEmpty()) && (extraParams == null || extraParams.isEmpty())) {
				LlamaServer.sendJsonResponse(ctx, ApiResponse.error("缺少必需的启动参数"));
				return;
			}
			String combinedCmd = "";
			if (cmd != null && !cmd.isEmpty()) combinedCmd = cmd;
			if (extraParams != null && !extraParams.isEmpty()) combinedCmd = combinedCmd.isEmpty() ? extraParams : (combinedCmd + " " + extraParams);
			boolean enableVision = ParamTool.parseJsonBoolean(obj, "enableVision", true);
			String modelId = JsonUtil.getJsonString(obj, "modelId", null);
			String llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPathSelect", null);
			if (llamaBinPathSelect == null || llamaBinPathSelect.trim().isEmpty()) {
				llamaBinPathSelect = JsonUtil.getJsonString(obj, "llamaBinPath", null);
			}
			// 预留返回值
			Map<String, Object> data = new HashMap<>();
			
			// 只保留部分参数：--ctx-size --flash-attn --batch-size --ubatch-size --parallel --kv-unified --cache-type-k --cache-type-v
			List<String> cmdlist = ParamTool.splitCmdArgs(combinedCmd);
			// 运行fit-param
			String output = LlamaServerManager.getInstance().handleFitParam(llamaBinPathSelect, modelId, enableVision, cmdlist);
			// 提取第一个数值
			Pattern numberPattern = Pattern.compile("llama_params_fit_impl: projected to use (\\d+) MiB");
			Matcher numberMatcher = numberPattern.matcher(output);
			if (numberMatcher.find()) {
			    String value = numberMatcher.group(1);
			    data.put("vram", value);
			}
			// 如果没有找到值，就去找错误信息
			else {
				
				Pattern pattern = Pattern.compile("^.*llama_init_from_model.*$", Pattern.MULTILINE);
		        Matcher matcher = pattern.matcher(output);
		        if (matcher.find()) {
		            data.put("message", matcher.group(0));
		        }
			}
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("估算显存时发生错误", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("估算显存失败: " + e.getMessage()));
		}
	}
}
