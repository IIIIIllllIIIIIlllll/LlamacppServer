package org.mark.llamacpp.server.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
	private static final Path MODEL_SAMPLING_FILE = Paths.get("config", "model-sampling.json");
	
	private static final ModelSamplingService INSTANCE = new ModelSamplingService();
	private final Object reloadLock = new Object();
	private volatile long samplingSettingLastModified = -1L;
	private volatile long modelSamplingLastModified = -1L;
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

	public JsonObject getOpenAISampling(String modelId) {
		if (modelId == null) {
			return null;
		}
		String safeModelId = modelId.trim();
		if (safeModelId.isEmpty()) {
			return null;
		}
		reloadCaches(false);
		JsonObject sampling = samplingConfigByModel.get(safeModelId);
		return sampling == null ? null : sampling.deepCopy();
	}
	
	public Map<String, Object> listSamplingSettings() {
		Map<String, Object> data = new HashMap<>();
		Map<String, JsonObject> configs = loadAllSamplingConfigs();
		List<String> names = new ArrayList<>(configs.keySet());
		Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
		Map<String, JsonObject> ordered = new LinkedHashMap<>();
		for (String name : names) {
			JsonObject cfg = configs.get(name);
			ordered.put(name, cfg == null ? new JsonObject() : cfg);
		}
		data.put("configs", ordered);
		data.put("names", names);
		data.put("count", names.size());
		return data;
	}
	
	public JsonObject upsertSamplingConfig(String configName, JsonObject samplingConfig) {
		String safeConfigName = configName == null ? "" : configName.trim();
		if (safeConfigName.isEmpty()) {
			throw new IllegalArgumentException("缺少samplingConfigName参数");
		}
		JsonObject in = samplingConfig == null ? new JsonObject() : samplingConfig;
		JsonObject normalizedSampling = extractOpenAISampling(in);
		synchronized (reloadLock) {
			JsonObject root = readSamplingRoot();
			JsonObject configs = getSharedConfigs(root, true);
			configs.add(safeConfigName, normalizedSampling);
			writeSamplingRoot(root);
		}
		reloadCaches(true);
		return normalizedSampling;
	}
	
	public Map<String, Object> deleteSamplingConfig(String configName) {
		String safeConfigName = configName == null ? "" : configName.trim();
		if (safeConfigName.isEmpty()) {
			throw new IllegalArgumentException("缺少samplingConfigName参数");
		}
		int removedConfigCount = 0;
		int removedBindingCount = 0;
		synchronized (reloadLock) {
			JsonObject root = readSamplingRoot();
			JsonObject sharedConfigs = getSharedConfigs(root, false);
			if (sharedConfigs != null && sharedConfigs.has(safeConfigName)) {
				sharedConfigs.remove(safeConfigName);
				removedConfigCount++;
				if (sharedConfigs.size() == 0) {
					root.remove("configs");
				}
			}
			writeSamplingRoot(root);
			Map<String, String> selectedMap = loadSelectedSamplingMap();
			List<String> removeSelectedModelIds = new ArrayList<>();
			for (Map.Entry<String, String> item : selectedMap.entrySet()) {
				String selectedName = item.getValue();
				if (selectedName != null && safeConfigName.equals(selectedName.trim())) {
					removeSelectedModelIds.add(item.getKey());
				}
			}
			for (String modelId : removeSelectedModelIds) {
				selectedMap.remove(modelId);
				removedBindingCount++;
			}
			writeSelectedSamplingMap(selectedMap);
		}
		reloadCaches(true);
		Map<String, Object> out = new HashMap<>();
		out.put("deleted", removedConfigCount > 0 || removedBindingCount > 0);
		out.put("samplingConfigName", safeConfigName);
		out.put("removedConfigCount", removedConfigCount);
		out.put("removedBindingCount", removedBindingCount);
		return out;
	}
	
	private void reloadCaches(boolean force) {
		synchronized (reloadLock) {
			long samplingMtime = getLastModifiedSafe(SAMPLING_SETTING_FILE);
			long modelSamplingMtime = getLastModifiedSafe(MODEL_SAMPLING_FILE);
			if (!force && samplingMtime == samplingSettingLastModified && modelSamplingMtime == modelSamplingLastModified) {
				return;
			}
			Map<String, String> selectedMap = loadSelectedSamplingMap();
			Map<String, JsonObject> samplingMap = buildSamplingConfigMap(selectedMap);
			selectedSamplingByModel.clear();
			selectedSamplingByModel.putAll(selectedMap);
			samplingConfigByModel.clear();
			samplingConfigByModel.putAll(samplingMap);
			samplingSettingLastModified = samplingMtime;
			modelSamplingLastModified = modelSamplingMtime;
		}
	}
	
	private Map<String, JsonObject> loadAllSamplingConfigs() {
		Map<String, JsonObject> out = new LinkedHashMap<>();
		try {
			JsonObject launchRoot = readSamplingRoot();
			if (launchRoot == null || launchRoot.size() == 0) {
				return out;
			}
			JsonObject sharedConfigs = getSharedConfigs(launchRoot, false);
			mergeConfigs(out, sharedConfigs);
		} catch (Exception e) {
			logger.info("读取全部采样配置失败: {}", e.getMessage());
		}
		return out;
	}
	
	private JsonObject readSamplingRoot() {
		try {
			if (!Files.exists(MODEL_SAMPLING_FILE)) {
				return new JsonObject();
			}
			String text = Files.readString(MODEL_SAMPLING_FILE, StandardCharsets.UTF_8);
			JsonObject root = JsonUtil.fromJson(text, JsonObject.class);
			return root == null ? new JsonObject() : root;
		} catch (Exception e) {
			logger.info("读取采样配置文件失败: {}", e.getMessage());
			return new JsonObject();
		}
	}
	
	private void writeSamplingRoot(JsonObject root) {
		try {
			Path parent = MODEL_SAMPLING_FILE.getParent();
			if (parent != null && !Files.exists(parent)) {
				Files.createDirectories(parent);
			}
			Files.write(MODEL_SAMPLING_FILE, JsonUtil.toJson(root).getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new RuntimeException("写入采样配置失败: " + e.getMessage(), e);
		}
	}
	
	private void writeSelectedSamplingMap(Map<String, String> map) {
		try {
			JsonObject root = new JsonObject();
			if (map != null) {
				for (Map.Entry<String, String> item : map.entrySet()) {
					String modelId = item.getKey() == null ? "" : item.getKey().trim();
					String configName = item.getValue() == null ? "" : item.getValue().trim();
					if (modelId.isEmpty() || configName.isEmpty()) {
						continue;
					}
					root.addProperty(modelId, configName);
				}
			}
			Path parent = SAMPLING_SETTING_FILE.getParent();
			if (parent != null && !Files.exists(parent)) {
				Files.createDirectories(parent);
			}
			Files.write(SAMPLING_SETTING_FILE, JsonUtil.toJson(root).getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new RuntimeException("写入模型采样设定失败: " + e.getMessage(), e);
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
			JsonObject launchRoot = readSamplingRoot();
			if (launchRoot == null || launchRoot.size() == 0) {
				return out;
			}
			JsonObject sharedConfigs = getSharedConfigs(launchRoot, false);
			for (Map.Entry<String, String> item : selectedMap.entrySet()) {
				String modelId = item.getKey();
				String configName = item.getValue();
				if (modelId == null || configName == null) {
					continue;
				}
				JsonObject configObj = readConfig(sharedConfigs, configName);
				if (configObj == null) {
					continue;
				}
				out.put(modelId, extractOpenAISampling(configObj));
			}
		} catch (Exception e) {
			logger.info("构建模型采样配置缓存失败: {}", e.getMessage());
		}
		return out;
	}

	private JsonObject getSharedConfigs(JsonObject root, boolean createIfMissing) {
		if (root == null) {
			return null;
		}
		if (root.has("configs") && root.get("configs").isJsonObject()) {
			return root.getAsJsonObject("configs");
		}
		if (!createIfMissing) {
			return null;
		}
		JsonObject configs = new JsonObject();
		root.add("configs", configs);
		return configs;
	}

	private void mergeConfigs(Map<String, JsonObject> out, JsonObject configs) {
		if (out == null || configs == null) {
			return;
		}
		for (Map.Entry<String, JsonElement> cfgItem : configs.entrySet()) {
			String configName = cfgItem.getKey() == null ? "" : cfgItem.getKey().trim();
			if (configName.isEmpty()) {
				continue;
			}
			JsonElement cfgEl = cfgItem.getValue();
			if (cfgEl == null || !cfgEl.isJsonObject()) {
				continue;
			}
			JsonObject sampling = extractOpenAISampling(cfgEl.getAsJsonObject());
			out.put(configName, sampling);
		}
	}

	private JsonObject readConfig(JsonObject configs, String configName) {
		if (configs == null || configName == null || !configs.has(configName) || !configs.get(configName).isJsonObject()) {
			return null;
		}
		return configs.getAsJsonObject(configName);
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
