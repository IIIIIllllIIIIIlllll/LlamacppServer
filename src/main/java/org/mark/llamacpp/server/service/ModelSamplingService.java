package org.mark.llamacpp.server.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class ModelSamplingService {
	
	private static final Logger logger = LoggerFactory.getLogger(ModelSamplingService.class);
	private static final Path SAMPLING_SETTING_FILE = Paths.get("config", "model-sampling-settings.json");
	private static final Path LAUNCH_CONFIG_FILE = Paths.get("config", "launch_config.json");
	
	private static final ModelSamplingService INSTANCE = new ModelSamplingService();
	private final Object reloadLock = new Object();
	private volatile long samplingSettingLastModified = -1L;
	private volatile long launchConfigLastModified = -1L;
	private final Map<String, String> selectedSamplingByModel = new ConcurrentHashMap<>();
	private final Map<String, JsonObject> samplingConfigByModel = new ConcurrentHashMap<>();
	
	public static ModelSamplingService getInstance() {
		return INSTANCE;
	}
	
	
	static {
		INSTANCE.init();
	}
	
	
	private ModelSamplingService() {
		
	}
	
	/**
	 * 	这里读取本地文件并缓存。
	 */
	public void init() {
		reloadCaches(true);
	}
	
	public void reload() {
		reloadCaches(true);
	}
	
	/**
	 * 	这里注入采样信息。
	 * @param requestJson
	 */
	public void handleOpenAI(JsonObject requestJson) {
		if (requestJson == null) {
			return;
		}
		reloadCaches(false);
		String modelId = JsonUtil.getJsonString(requestJson, "model", null);
		if (modelId == null) {
			return;
		}
		modelId = modelId.trim();
		if (modelId.isEmpty()) {
			return;
		}
		JsonObject sampling = samplingConfigByModel.get(modelId);
		if (sampling == null) {
			return;
		}
		injectSampling(requestJson, sampling);
	}
	
	private void reloadCaches(boolean force) {
		synchronized (reloadLock) {
			long samplingMtime = getLastModifiedSafe(SAMPLING_SETTING_FILE);
			long launchMtime = getLastModifiedSafe(LAUNCH_CONFIG_FILE);
			if (!force && samplingMtime == samplingSettingLastModified && launchMtime == launchConfigLastModified) {
				return;
			}
			Map<String, String> selectedMap = loadSelectedSamplingMap();
			Map<String, JsonObject> samplingMap = buildSamplingConfigMap(selectedMap);
			selectedSamplingByModel.clear();
			selectedSamplingByModel.putAll(selectedMap);
			samplingConfigByModel.clear();
			samplingConfigByModel.putAll(samplingMap);
			samplingSettingLastModified = samplingMtime;
			launchConfigLastModified = launchMtime;
		}
	}
	
	private long getLastModifiedSafe(Path path) {
		try {
			if (!Files.exists(path)) {
				return -1L;
			}
			return Files.getLastModifiedTime(path).toMillis();
		} catch (Exception e) {
			return -1L;
		}
	}
	
	private Map<String, String> loadSelectedSamplingMap() {
		Map<String, String> out = new HashMap<>();
		try {
			if (!Files.exists(SAMPLING_SETTING_FILE)) {
				return out;
			}
			String text = Files.readString(SAMPLING_SETTING_FILE, StandardCharsets.UTF_8);
			JsonObject root = JsonUtil.fromJson(text, JsonObject.class);
			if (root == null) {
				return out;
			}
			for (Map.Entry<String, JsonElement> item : root.entrySet()) {
				String modelId = item.getKey() == null ? "" : item.getKey().trim();
				if (modelId.isEmpty()) {
					continue;
				}
				JsonElement nameEl = item.getValue();
				if (nameEl == null || nameEl.isJsonNull()) {
					continue;
				}
				String configName = null;
				try {
					configName = nameEl.getAsString();
				} catch (Exception e) {
					configName = null;
				}
				configName = configName == null ? "" : configName.trim();
				if (!configName.isEmpty()) {
					out.put(modelId, configName);
				}
			}
		} catch (Exception e) {
			logger.info("加载模型采样配置映射失败: {}", e.getMessage());
		}
		return out;
	}
	
	private Map<String, JsonObject> buildSamplingConfigMap(Map<String, String> selectedMap) {
		Map<String, JsonObject> out = new HashMap<>();
		if (selectedMap == null || selectedMap.isEmpty()) {
			return out;
		}
		try {
			if (!Files.exists(LAUNCH_CONFIG_FILE)) {
				return out;
			}
			String text = Files.readString(LAUNCH_CONFIG_FILE, StandardCharsets.UTF_8);
			JsonObject launchRoot = JsonUtil.fromJson(text, JsonObject.class);
			if (launchRoot == null) {
				return out;
			}
			for (Map.Entry<String, String> item : selectedMap.entrySet()) {
				String modelId = item.getKey();
				String configName = item.getValue();
				if (modelId == null || configName == null) {
					continue;
				}
				JsonObject modelEntry = launchRoot.has(modelId) && launchRoot.get(modelId).isJsonObject()
						? launchRoot.getAsJsonObject(modelId)
						: null;
				if (modelEntry == null || !modelEntry.has("configs") || !modelEntry.get("configs").isJsonObject()) {
					continue;
				}
				JsonObject configs = modelEntry.getAsJsonObject("configs");
				if (!configs.has(configName) || !configs.get(configName).isJsonObject()) {
					continue;
				}
				JsonObject configObj = configs.getAsJsonObject(configName);
				JsonObject sampling = extractOpenAISampling(configObj);
				if (sampling.size() > 0) {
					out.put(modelId, sampling);
				}
			}
		} catch (Exception e) {
			logger.info("构建模型采样配置缓存失败: {}", e.getMessage());
		}
		return out;
	}
	
	private JsonObject extractOpenAISampling(JsonObject configObj) {
		JsonObject out = new JsonObject();
		setDoubleFromKeys(out, "temperature", configObj, "temperature", "temp");
		setDoubleFromKeys(out, "top_p", configObj, "top_p", "topP", "top-p");
		setDoubleFromKeys(out, "min_p", configObj, "min_p", "minP", "min-p");
		setDoubleFromKeys(out, "repeat_penalty", configObj, "repeat_penalty", "repeatPenalty", "repeat-penalty");
		setIntFromKeys(out, "top_k", configObj, "top_k", "topK", "top-k");
		setDoubleFromKeys(out, "presence_penalty", configObj, "presence_penalty", "presencePenalty", "presence-penalty");
		setDoubleFromKeys(out, "frequency_penalty", configObj, "frequency_penalty", "frequencyPenalty", "frequency-penalty");
		applySamplingFromCmd(out, JsonUtil.getJsonString(configObj, "cmd", null));
		return out;
	}
	
	private void applySamplingFromCmd(JsonObject out, String cmd) {
		if (out == null || cmd == null || cmd.trim().isEmpty()) {
			return;
		}
		List<String> tokens = ParamTool.splitCmdArgs(cmd);
		for (int i = 0; i < tokens.size(); i++) {
			String token = tokens.get(i);
			if (token == null || token.isBlank() || !token.startsWith("-")) {
				continue;
			}
			String flag = token;
			String value = null;
			int eq = token.indexOf('=');
			if (eq > 0) {
				flag = token.substring(0, eq);
				value = token.substring(eq + 1);
			} else if (i + 1 < tokens.size()) {
				String next = tokens.get(i + 1);
				if (next != null && !next.startsWith("-")) {
					value = next;
				}
			}
			if (value == null || value.isBlank()) {
				continue;
			}
			switch (flag) {
				case "--temp":
					setDoubleIfAbsent(out, "temperature", value);
					break;
				case "--top-p":
					setDoubleIfAbsent(out, "top_p", value);
					break;
				case "--min-p":
					setDoubleIfAbsent(out, "min_p", value);
					break;
				case "--repeat-penalty":
					setDoubleIfAbsent(out, "repeat_penalty", value);
					break;
				case "--top-k":
					setIntIfAbsent(out, "top_k", value);
					break;
				case "--presence-penalty":
					setDoubleIfAbsent(out, "presence_penalty", value);
					break;
				case "--frequency-penalty":
					setDoubleIfAbsent(out, "frequency_penalty", value);
					break;
				default:
					break;
			}
		}
	}
	
	private void injectSampling(JsonObject requestJson, JsonObject sampling) {
		for (Map.Entry<String, JsonElement> item : sampling.entrySet()) {
			String key = item.getKey();
			JsonElement value = item.getValue();
			if (key == null || value == null || value.isJsonNull()) {
				continue;
			}
			requestJson.add(key, value.deepCopy());
		}
	}
	
	private void setDoubleFromKeys(JsonObject out, String targetKey, JsonObject src, String... keys) {
		if (out.has(targetKey) || src == null || keys == null) {
			return;
		}
		for (String k : keys) {
			Double v = readDouble(src, k);
			if (v != null) {
				out.addProperty(targetKey, v);
				return;
			}
		}
	}
	
	private void setIntFromKeys(JsonObject out, String targetKey, JsonObject src, String... keys) {
		if (out.has(targetKey) || src == null || keys == null) {
			return;
		}
		for (String k : keys) {
			Integer v = readInt(src, k);
			if (v != null) {
				out.addProperty(targetKey, v);
				return;
			}
		}
	}
	
	private void setDoubleIfAbsent(JsonObject out, String key, String raw) {
		if (out.has(key)) {
			return;
		}
		Double v = parseDouble(raw);
		if (v != null) {
			out.addProperty(key, v);
		}
	}
	
	private void setIntIfAbsent(JsonObject out, String key, String raw) {
		if (out.has(key)) {
			return;
		}
		Integer v = parseInt(raw);
		if (v != null) {
			out.addProperty(key, v);
		}
	}
	
	private Double readDouble(JsonObject obj, String key) {
		if (obj == null || key == null || !obj.has(key)) {
			return null;
		}
		JsonElement el = obj.get(key);
		if (el == null || el.isJsonNull()) {
			return null;
		}
		try {
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
				return el.getAsDouble();
			}
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
				return parseDouble(el.getAsString());
			}
		} catch (Exception e) {
			return null;
		}
		return null;
	}
	
	private Integer readInt(JsonObject obj, String key) {
		if (obj == null || key == null || !obj.has(key)) {
			return null;
		}
		JsonElement el = obj.get(key);
		if (el == null || el.isJsonNull()) {
			return null;
		}
		try {
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
				return el.getAsInt();
			}
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
				return parseInt(el.getAsString());
			}
		} catch (Exception e) {
			return null;
		}
		return null;
	}
	
	private Double parseDouble(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			String s = raw.trim();
			if (s.isEmpty()) {
				return null;
			}
			return Double.parseDouble(s);
		} catch (Exception e) {
			return null;
		}
	}
	
	private Integer parseInt(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			String s = raw.trim();
			if (s.isEmpty()) {
				return null;
			}
			return Integer.parseInt(s);
		} catch (Exception e) {
			return null;
		}
	}
}
