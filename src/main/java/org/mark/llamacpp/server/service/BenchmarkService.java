package org.mark.llamacpp.server.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;


/**
 * 	性能测试 V2，直接通过/v1/chat/completions发送提示词来测试性能。
 */
public class BenchmarkService {
	
	private static final Logger logger = LoggerFactory.getLogger(BenchmarkService.class);
	
	public static class BenchmarkTokenOptions {
		public String unitText = " a";
		public int maxIterations = 24;
		public int tolerance = 0;
		public int sampleCount = 64;
		public boolean addSpecial = true;
		public boolean parseSpecial = true;
		public int maxUnitsPerMessage = 0;
	}
	
	private static class PromptTokenResult {
		private final String prompt;
		private final int tokenCount;
		
		private PromptTokenResult(String prompt, int tokenCount) {
			this.prompt = prompt;
			this.tokenCount = tokenCount;
		}
	}
	
	
	/**
	 * 	和llamacpp之间建立的连接。
	 */
	private ConcurrentHashMap<ChannelHandlerContext, HttpURLConnection> connections = new ConcurrentHashMap<>();
	
	
	public BenchmarkService() {
		
	}
	
	/**
	 * 	处理benchmark的请求。
	 * @param ctx
	 * @param json
	 * @return
	 */
	public Map<String, Object> handleBenchmark(ChannelHandlerContext ctx, JsonObject json) {
		HttpURLConnection connection = null;
		try {
			if (json == null) {
				throw new IllegalArgumentException("请求体解析失败");
			}
			String modelId = JsonUtil.getJsonString(json, "modelId", null);
			if (modelId != null) modelId = modelId.trim();
			if (modelId == null || modelId.isEmpty()) {
				throw new IllegalArgumentException("缺少必需的modelId参数");
			}
			Integer promptTokens = JsonUtil.getJsonInt(json, "promptTokens", null);
			if (promptTokens == null || promptTokens.intValue() <= 0) {
				throw new IllegalArgumentException("缺少必需的promptTokens参数");
			}
			Integer maxTokens = JsonUtil.getJsonInt(json, "maxTokens", null);
			if (maxTokens == null || maxTokens.intValue() <= 0) {
				throw new IllegalArgumentException("缺少必需的maxTokens参数");
			}
			
			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (!manager.getLoadedProcesses().containsKey(modelId)) {
				throw new IllegalStateException("模型未加载: " + modelId);
			}
			Integer port = manager.getModelPort(modelId);
			if (port == null) {
				throw new IllegalStateException("未找到模型端口: " + modelId);
			}
			LlamaCppProcess process = manager.getLoadedProcesses().get(modelId);
			final String llamaBinPath = process == null ? null : process.getLlamaBinPath();
			// 查找启动参数中，是否包含--device参数，如果有，则要单独列出来
			List<String> deviceList = this.findDevicesInUse(process);
			
			BenchmarkTokenOptions options = new BenchmarkTokenOptions();
			if (promptTokens.intValue() >= 8192) {
				options.maxUnitsPerMessage = 4096;
			}
			JsonObject bench = this.generatePromptForTargetTokens(modelId, null, promptTokens.intValue() - 1, options);
			JsonArray messages = bench != null && bench.has("messages") && bench.get("messages").isJsonArray()
					? bench.getAsJsonArray("messages").deepCopy()
					: new JsonArray();
			if (messages.size() <= 0) {
				String contentText = bench != null && bench.has("content") && !bench.get("content").isJsonNull()
						? bench.get("content").getAsString()
						: "";
				JsonObject userMsg = this.ensureUserMessage(messages);
				userMsg.addProperty("content", contentText);
			}
			
			JsonObject forward = new JsonObject();
			forward.addProperty("model", modelId);
			forward.add("messages", messages);
			forward.addProperty("max_tokens", maxTokens.intValue());
			forward.addProperty("stream", false);
			

			String targetUrl = String.format("http://localhost:%d/v1/chat/completions", port.intValue());
			URL url = URI.create(targetUrl).toURL();
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(3600 * 7 * 24 * 1000);
			connection.setReadTimeout(3600 * 7 * 24 * 1000);
			connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			
			if (ctx != null) {
				this.connections.put(ctx, connection);
			}

			byte[] outBytes = JsonUtil.toJson(forward).getBytes(StandardCharsets.UTF_8);
			try (OutputStream os = connection.getOutputStream()) {
				os.write(outBytes);
			}

			int responseCode = connection.getResponseCode();
			String responseBody = readBody(connection, responseCode >= 200 && responseCode < 300);
			if (!(responseCode >= 200 && responseCode < 300)) {
				throw new IllegalStateException("模型返回错误: " + responseBody);
			}

			JsonObject respObj = JsonUtil.fromJson(responseBody, JsonObject.class);
			JsonObject timingsObj = (respObj != null && respObj.has("timings") && respObj.get("timings").isJsonObject())
					? respObj.getAsJsonObject("timings")
					: null;
			if (timingsObj == null) {
				throw new IllegalStateException("模型返回内容缺少timings");
			}

			Map<String, Object> data = new HashMap<>();
			String cpu = ComputerService.getCPUModel();
			Long ram = this.toGbFromKb(ComputerService.getPhysicalMemoryKB());
			String cmd = process == null ? null : process.getCmd();
			data.put("modelId", modelId);
			data.put("promptTokens", promptTokens);
			data.put("maxTokens", maxTokens);
			data.put("timings", JsonUtil.fromJson(timingsObj, Object.class));
			data.put("devices", deviceList);
			data.put("cpu", cpu);
			data.put("ram", ram);
			data.put("cmd", cmd);
			data.put("llamaBinPath", llamaBinPath);

			try {
				File outFile = this.saveBenchmarkV2Record(modelId, promptTokens.intValue(), maxTokens.intValue(),
						llamaBinPath, timingsObj, deviceList, cpu, ram, cmd);
				data.put("savedPath", outFile.getAbsolutePath());
			} catch (Exception ex) {
				logger.info("保存基准测试V2结果到文件失败", ex);
			}

			return data;
		} catch (RuntimeException e) {
			logger.info("执行模型基准测试V2时发生错误", e);
			throw e;
		} catch (Exception e) {
			logger.info("执行模型基准测试V2时发生错误", e);
			throw new RuntimeException("执行模型基准测试失败: " + e.getMessage(), e);
		} finally {
			if (ctx != null) {
				this.connections.remove(ctx, connection);
			}
			if (connection != null) {
				try {
					connection.disconnect();
				} catch (Exception ignore) {
				}
			}
		}
	}

	private File saveBenchmarkV2Record(String modelId, int promptTokens, int maxTokens, String llamaBinPath,
			JsonObject timingsObj, List<String> deviceList, String cpu, Long ram, String cmd) throws Exception {
		String safeModelId = (modelId == null ? "unknown" : modelId).replaceAll("[^a-zA-Z0-9-_\\.]", "_");
		String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
		String fileName = safeModelId + "_V2.jsonl";
		File dir = new File("benchmarks");
		if (!dir.exists()) {
			dir.mkdirs();
		}
		File outFile = new File(dir, fileName);
		JsonObject record = new JsonObject();
		record.addProperty("timestamp", timestamp);
		record.addProperty("modelId", modelId);
		record.addProperty("promptTokens", promptTokens);
		record.addProperty("maxTokens", maxTokens);
		record.addProperty("llamaBinPath", llamaBinPath);
		record.addProperty("cpu", cpu);
		if (ram == null) {
			record.add("ram", null);
		} else {
			record.addProperty("ram", ram);
		}
		record.addProperty("cmd", cmd);
		JsonArray devices = new JsonArray();
		if (deviceList != null) {
			for (String device : deviceList) {
				devices.add(device);
			}
		}
		record.add("devices", devices);
		record.add("timings", timingsObj == null ? new JsonObject() : timingsObj.deepCopy());
		try (FileOutputStream fos = new FileOutputStream(outFile, true)) {
			String line = JsonUtil.toJson(record) + System.lineSeparator();
			fos.write(line.getBytes(StandardCharsets.UTF_8));
		}
		return outFile;
	}

	private Long toGbFromKb(long kb) {
		if (kb <= 0) return null;
		return kb / (1024L * 1024L);
	}
	
	/**
	 * 	
	 * @param process
	 * @return
	 */
	private List<String> findDevicesInUse(LlamaCppProcess process) {
		// 找到llamacpp的路径
		final String llamaBinPath = process == null ? null : process.getLlamaBinPath();
		// 这个集合中的内容参考：Vulkan0: AMD Radeon(TM) Graphics (16253 MiB, 15440 MiB free)
		List<String> deviceList = LlamaServerManager.getInstance().handleListDevices(llamaBinPath);
		// 如果使用了指定的设备
		String cmd = process.getCmd();
		if(cmd.contains("--device")) {
			Pattern pattern = Pattern.compile("--device\\s+(\\S+)");
			Matcher matcher = pattern.matcher(cmd);
			String devices = null;
	        if (matcher.find()) {
	        	devices = matcher.group(1).toLowerCase();
	        }
	        // 被启用的设备
			String[] deviceArray = devices.split(",");
			//
			Set<String> enabledDeviceNames = Arrays.stream(deviceArray)
		            .map(String::trim)
		            .collect(Collectors.toSet());
			return deviceList.stream()
					.filter(deviceInfo -> {
						String deviceName = deviceInfo.split(":")[0].trim();
						return enabledDeviceNames.contains(deviceName.toLowerCase());
					})
					.collect(Collectors.toList());
		}else {
			return deviceList;
		}
	}
	
	
	/**
	 * 	生成提示词。
	 * @param modelId
	 * @param messages
	 * @param targetTokens
	 * @return
	 */
	public JsonObject generatePromptForTargetTokens(String modelId, JsonArray messages, int targetTokens) {
		return generatePromptForTargetTokens(modelId, messages, targetTokens, null);
	}
	
	/**
	 * 	生成提示词。
	 * @param modelId
	 * @param messages
	 * @param targetTokens
	 * @param options
	 * @return
	 */
	public JsonObject generatePromptForTargetTokens(String modelId, JsonArray messages, int targetTokens,
			BenchmarkTokenOptions options) {
		if (targetTokens <= 0) {
			throw new IllegalArgumentException("targetTokens必须大于0");
		}
		BenchmarkTokenOptions opt = options == null ? new BenchmarkTokenOptions() : options;
		String finalModelId = resolveModelId(modelId);
		JsonArray templateMessages = messages == null ? new JsonArray() : messages.deepCopy();
		ensureUserMessage(templateMessages);
		int targetUserIndex = findLastUserMessageIndex(templateMessages);
		JsonObject targetMsg = targetUserIndex >= 0 && targetUserIndex < templateMessages.size()
				? templateMessages.get(targetUserIndex).getAsJsonObject()
				: null;
		String baseContent = readMessageContent(targetMsg);
		int maxUnitsPerMessage = Math.max(0, opt.maxUnitsPerMessage);
		
		JsonArray baseMessages = buildMessagesWithUnits(templateMessages, targetUserIndex, baseContent, opt.unitText, 0,
				maxUnitsPerMessage);
		PromptTokenResult base = countPromptTokens(finalModelId, baseMessages, opt.addSpecial, opt.parseSpecial);
		int baseTokens = base.tokenCount;
		if (baseTokens >= targetTokens) {
			JsonObject out = new JsonObject();
			out.addProperty("modelId", finalModelId);
			out.addProperty("targetTokens", targetTokens);
			out.addProperty("promptTokens", baseTokens);
			out.addProperty("baseTokens", baseTokens);
			out.addProperty("content", baseContent);
			out.addProperty("prompt", base.prompt);
			out.addProperty("iterations", 0);
			out.add("messages", baseMessages.deepCopy());
			out.addProperty("messageCount", baseMessages.size());
			return out;
		}
		
		int sampleCount = Math.max(1, opt.sampleCount);
		JsonArray sampleMessages = buildMessagesWithUnits(templateMessages, targetUserIndex, baseContent, opt.unitText,
				sampleCount, maxUnitsPerMessage);
		PromptTokenResult sample = countPromptTokens(finalModelId, sampleMessages, opt.addSpecial, opt.parseSpecial);
		int delta = sample.tokenCount - baseTokens;
		if (delta <= 0) {
			delta = sampleCount;
		}
		double tokensPerUnit = delta / (double) sampleCount;
		int needed = (int) Math.ceil((targetTokens - baseTokens) / Math.max(tokensPerUnit, 0.01d));
		int low = 0;
		int high = Math.max(needed, 1);
		
		int expand = 0;
		while (expand < 8) {
			JsonArray candidateMessages = buildMessagesWithUnits(templateMessages, targetUserIndex, baseContent,
					opt.unitText, high, maxUnitsPerMessage);
			PromptTokenResult r = countPromptTokens(finalModelId, candidateMessages, opt.addSpecial, opt.parseSpecial);
			if (r.tokenCount >= targetTokens) {
				break;
			}
			high = high * 2;
			expand++;
		}
		
		int bestUnits = 0;
		PromptTokenResult bestResult = base;
		int iterations = 0;
		while (low <= high && iterations < opt.maxIterations) {
			int mid = low + (high - low) / 2;
			JsonArray candidateMessages = buildMessagesWithUnits(templateMessages, targetUserIndex, baseContent,
					opt.unitText, mid, maxUnitsPerMessage);
			PromptTokenResult r = countPromptTokens(finalModelId, candidateMessages, opt.addSpecial, opt.parseSpecial);
			iterations++;
			if (r.tokenCount == targetTokens) {
				bestUnits = mid;
				bestResult = r;
				break;
			}
			if (r.tokenCount < targetTokens) {
				bestUnits = mid;
				bestResult = r;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		
		int bestTokens = bestResult.tokenCount;
		if (bestTokens < targetTokens) {
			int start = Math.max(bestUnits + 1, 1);
			int limit = bestUnits + 16;
			for (int i = start; i <= limit; i++) {
				JsonArray candidateMessages = buildMessagesWithUnits(templateMessages, targetUserIndex, baseContent,
						opt.unitText, i, maxUnitsPerMessage);
				PromptTokenResult r = countPromptTokens(finalModelId, candidateMessages, opt.addSpecial, opt.parseSpecial);
				iterations++;
				if (r.tokenCount >= targetTokens) {
					bestUnits = i;
					bestResult = r;
					bestTokens = r.tokenCount;
					break;
				}
			}
		}
		
		if (bestTokens > targetTokens && opt.tolerance > 0 && bestTokens - targetTokens <= opt.tolerance) {
			bestTokens = bestResult.tokenCount;
		}
		
		String finalContent = baseContent + repeatUnit(opt.unitText, bestUnits);
		JsonArray finalMessages = buildMessagesWithUnits(templateMessages, targetUserIndex, baseContent, opt.unitText,
				bestUnits, maxUnitsPerMessage);
		PromptTokenResult finalResult = countPromptTokens(finalModelId, finalMessages, opt.addSpecial, opt.parseSpecial);
		
		JsonObject out = new JsonObject();
		out.addProperty("modelId", finalModelId);
		out.addProperty("targetTokens", targetTokens);
		out.addProperty("promptTokens", finalResult.tokenCount);
		out.addProperty("baseTokens", baseTokens);
		out.addProperty("content", finalContent);
		out.addProperty("prompt", finalResult.prompt);
		out.addProperty("iterations", iterations);
		out.addProperty("unitText", opt.unitText);
		out.add("messages", finalMessages.deepCopy());
		out.addProperty("messageCount", finalMessages.size());
		return out;
	}
	
	private String resolveModelId(String modelId) {
		LlamaServerManager manager = LlamaServerManager.getInstance();
		String id = modelId == null ? null : modelId.trim();
		if (id == null || id.isEmpty()) {
			id = manager.getFirstModelName();
		}
		if (id == null || id.isEmpty()) {
			throw new IllegalStateException("模型未加载");
		}
		if (!manager.getLoadedProcesses().containsKey(id)) {
			throw new IllegalStateException("模型未加载: " + id);
		}
		return id;
	}
	
	private PromptTokenResult countPromptTokens(String modelId, JsonArray messages, boolean addSpecial, boolean parseSpecial) {
		String prompt = applyTemplate(modelId, messages);
		int tokenCount = tokenizePrompt(modelId, prompt, addSpecial, parseSpecial);
		return new PromptTokenResult(prompt, tokenCount);
	}
	
	private String applyTemplate(String modelId, JsonArray messages) {
		JsonObject payload = new JsonObject();
		payload.add("messages", messages == null ? new JsonArray() : messages);
		JsonObject resp = postJson(modelId, "/apply-template", payload);
		if (resp == null || !resp.has("prompt") || resp.get("prompt").isJsonNull()) {
			throw new IllegalStateException("apply-template响应缺少prompt字段");
		}
		return resp.get("prompt").getAsString();
	}
	
	private int tokenizePrompt(String modelId, String content, boolean addSpecial, boolean parseSpecial) {
		JsonObject payload = new JsonObject();
		payload.addProperty("content", content == null ? "" : content);
		payload.addProperty("add_special", addSpecial);
		payload.addProperty("parse_special", parseSpecial);
		payload.addProperty("with_pieces", false);
		JsonObject resp = postJson(modelId, "/tokenize", payload);
		int count = extractTokenCount(resp);
		if (count < 0) {
			throw new IllegalStateException("tokenize响应缺少tokens字段");
		}
		return count;
	}
	
	private int extractTokenCount(JsonObject resp) {
		if (resp == null || !resp.has("tokens") || resp.get("tokens") == null || !resp.get("tokens").isJsonArray()) {
			return -1;
		}
		JsonArray arr = resp.getAsJsonArray("tokens");
		return arr.size();
	}
	
	/**
	 * 	向指定的llamacpp进程发送提示词。
	 * @param modelId
	 * @param path
	 * @param payload
	 * @return
	 */
	private JsonObject postJson(String modelId, String path, JsonObject payload) {
		HttpURLConnection connection = null;
		try {
			LlamaServerManager manager = LlamaServerManager.getInstance();
			Integer port = manager.getModelPort(modelId);
			if (port == null) {
				throw new IllegalStateException("未找到模型端口: " + modelId);
			}
			String targetUrl = String.format("http://localhost:%d%s", port.intValue(), path);
			URL url = URI.create(targetUrl).toURL();
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setConnectTimeout(30000);
			connection.setReadTimeout(30000);
			connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			byte[] outBytes = JsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8);
			connection.setRequestProperty("Content-Length", String.valueOf(outBytes.length));
			try (OutputStream os = connection.getOutputStream()) {
				os.write(outBytes);
			}
			int responseCode = connection.getResponseCode();
			String responseBody = readBody(connection, responseCode >= 200 && responseCode < 300);
			JsonElement parsed = null;
			try {
				parsed = JsonUtil.fromJson(responseBody, JsonElement.class);
			} catch (Exception ignore) {
			}
			if (parsed != null && parsed.isJsonObject()) {
				return parsed.getAsJsonObject();
			}
			if (responseBody != null && !responseBody.isBlank()) {
				throw new IllegalStateException(responseBody);
			}
			throw new IllegalStateException("模型返回了非JSON响应");
		} catch (Exception e) {
			logger.info("调用模型接口失败: " + path, e);
			throw new RuntimeException("调用模型接口失败: " + e.getMessage(), e);
		} finally {
			if (connection != null) {
				try {
					connection.disconnect();
				} catch (Exception ignore) {
				}
			}
		}
	}
	
	private static String readBody(HttpURLConnection connection, boolean ok) {
		if (connection == null) return "";
		InputStream in = null;
		try {
			in = ok ? connection.getInputStream() : connection.getErrorStream();
			if (in == null) return "";
			try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				StringBuilder sb = new StringBuilder();
				String line;
				while ((line = br.readLine()) != null) {
					sb.append(line);
				}
				return sb.toString();
			}
		} catch (Exception e) {
			return "";
		}
	}
	
	private JsonObject ensureUserMessage(JsonArray messages) {
		JsonObject lastUser = null;
		if (messages != null) {
			for (int i = messages.size() - 1; i >= 0; i--) {
				JsonElement el = messages.get(i);
				if (el == null || !el.isJsonObject()) {
					continue;
				}
				JsonObject obj = el.getAsJsonObject();
				String role = readString(obj.get("role"));
				if ("user".equals(role)) {
					lastUser = obj;
					break;
				}
			}
		}
		if (lastUser == null) {
			lastUser = new JsonObject();
			lastUser.addProperty("role", "user");
			lastUser.addProperty("content", "");
			messages.add(lastUser);
		}
		return lastUser;
	}

	private int findLastUserMessageIndex(JsonArray messages) {
		if (messages == null) return -1;
		for (int i = messages.size() - 1; i >= 0; i--) {
			JsonElement el = messages.get(i);
			if (el == null || !el.isJsonObject()) continue;
			JsonObject obj = el.getAsJsonObject();
			if ("user".equals(readString(obj.get("role")))) {
				return i;
			}
		}
		return -1;
	}

	private JsonArray buildMessagesWithUnits(JsonArray templateMessages, int targetUserIndex, String baseContent,
			String unitText, int units, int maxUnitsPerMessage) {
		JsonArray out = templateMessages == null ? new JsonArray() : templateMessages.deepCopy();
		int index = targetUserIndex;
		if (index < 0 || index >= out.size() || !out.get(index).isJsonObject()) {
			ensureUserMessage(out);
			index = findLastUserMessageIndex(out);
		}
		JsonObject targetMsg = index >= 0 && index < out.size() && out.get(index).isJsonObject()
				? out.get(index).getAsJsonObject()
				: null;
		if (targetMsg == null) {
			targetMsg = new JsonObject();
			targetMsg.addProperty("role", "user");
			targetMsg.addProperty("content", "");
			out.add(targetMsg);
		}
		int cap = maxUnitsPerMessage <= 0 ? Integer.MAX_VALUE : Math.max(1, maxUnitsPerMessage);
		int remaining = Math.max(0, units);
		int firstUnits = Math.min(remaining, cap);
		String firstContent = (baseContent == null ? "" : baseContent) + repeatUnit(unitText, firstUnits);
		setMessageContent(targetMsg, firstContent);
		remaining -= firstUnits;
		while (remaining > 0) {
			int chunk = Math.min(remaining, cap);
			JsonObject extraMsg = new JsonObject();
			extraMsg.addProperty("role", "user");
			extraMsg.addProperty("content", repeatUnit(unitText, chunk));
			out.add(extraMsg);
			remaining -= chunk;
		}
		return out;
	}
	
	private String readMessageContent(JsonObject msg) {
		if (msg == null) return "";
		JsonElement el = msg.get("content");
		if (el == null || el.isJsonNull()) return "";
		if (el.isJsonPrimitive()) {
			try {
				return el.getAsString();
			} catch (Exception ignore) {
			}
		}
		return JsonUtil.jsonValueToString(el);
	}
	
	private void setMessageContent(JsonObject msg, String content) {
		if (msg == null) return;
		msg.addProperty("content", content == null ? "" : content);
	}
	
	private String readString(JsonElement el) {
		if (el == null || el.isJsonNull()) return "";
		try {
			return el.getAsString();
		} catch (Exception e) {
			return "";
		}
	}
	
	private String repeatUnit(String unit, int count) {
		if (count <= 0) return "";
		String u = unit == null ? "" : unit;
		StringBuilder sb = new StringBuilder(u.length() * count);
		for (int i = 0; i < count; i++) {
			sb.append(u);
		}
		return sb.toString();
	}
	
	
	/**
	 * 	通信断开时的操作
	 * @param ctx
	 * @throws Exception
	 */
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		// 如果不为null，就关闭连接
		if (ctx == null) return;
		HttpURLConnection conn = this.connections.remove(ctx);
		if (conn == null) return;
		try {
			conn.disconnect();
		} catch (Exception ignore) {
		}
	}
}
