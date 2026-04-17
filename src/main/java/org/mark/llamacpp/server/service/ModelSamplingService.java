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

import com.google.gson.JsonArray;
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
		this.reloadCaches(false);
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
		this.injectSampling(requestJson, sampling);
		this.applyThinkingBySampling(requestJson, sampling);
	}
	
	/**
	 * 	查询指定模型的采样配置。
	 * @param modelId
	 * @return
	 */
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
		this.setIntFromKeys(out, "seed", configObj, "seed");
		this.setDoubleFromKeys(out, "temperature", configObj, "temperature", "temp");
		this.setStringArrayFromKeys(out, "samplers", configObj, true, "samplers");
		this.setDoubleFromKeys(out, "top_p", configObj, "top_p", "topP", "top-p");
		this.setDoubleFromKeys(out, "min_p", configObj, "min_p", "minP", "min-p");
		this.setDoubleFromKeys(out, "top_n_sigma", configObj, "top_n_sigma", "topNSigma", "top-n-sigma");
		this.setDoubleFromKeys(out, "repeat_penalty", configObj, "repeat_penalty", "repeatPenalty", "repeat-penalty");
		this.setIntFromKeys(out, "top_k", configObj, "top_k", "topK", "top-k");
		this.setDoubleFromKeys(out, "presence_penalty", configObj, "presence_penalty", "presencePenalty", "presence-penalty");
		this.setDoubleFromKeys(out, "frequency_penalty", configObj, "frequency_penalty", "frequencyPenalty", "frequency-penalty");
		this.setDoubleFromKeys(out, "dry_multiplier", configObj, "dry_multiplier", "dryMultiplier", "dry-multiplier");
		this.setDoubleFromKeys(out, "dry_base", configObj, "dry_base", "dryBase", "dry-base");
		this.setIntFromKeys(out, "dry_allowed_length", configObj, "dry_allowed_length", "dryAllowedLength", "dry-allowed-length");
		this.setIntFromKeys(out, "dry_penalty_last_n", configObj, "dry_penalty_last_n", "dryPenaltyLastN", "dry-penalty-last-n");
		this.setStringArrayFromKeys(out, "dry_sequence_breakers", configObj, false, "dry_sequence_breakers", "drySequenceBreakers", "dry-sequence-breakers");
		this.setBooleanFromKeys(out, "force_enable_thinking", configObj, "force_enable_thinking", "forceEnableThinking");
		this.setBooleanFromKeys(out, "enable_thinking", configObj, "enable_thinking");
		this.applySamplingFromCmd(out, JsonUtil.getJsonString(configObj, "cmd", null));
		if (out.has("enable_thinking") && !out.has("force_enable_thinking")) {
			out.addProperty("force_enable_thinking", true);
		}
		return out;
	}
	
	/**
	 * 	有点意义不明。
	 * @param out
	 * @param cmd
	 */
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
				if (next != null && !looksLikeOptionToken(next)) {
					value = next;
				}
			}
			switch (flag) {
				case "--seed":
					if (value == null || value.isBlank()) {
						continue;
					}
					setIntIfAbsent(out, "seed", value);
					break;
				case "--temp":
					if (value == null || value.isBlank()) {
						continue;
					}
					setDoubleIfAbsent(out, "temperature", value);
					break;
				case "--samplers":
					if (value == null || value.isBlank()) {
						continue;
					}
					setStringArrayIfAbsent(out, "samplers", parseCliSamplers(value));
					break;
				case "--top-p":
					if (value == null || value.isBlank()) {
						continue;
					}
					setDoubleIfAbsent(out, "top_p", value);
					break;
				case "--min-p":
					if (value == null || value.isBlank()) {
						continue;
					}
					setDoubleIfAbsent(out, "min_p", value);
					break;
				case "--top-nsigma":
				case "--top-n-sigma":
					if (value == null || value.isBlank()) {
						continue;
					}
					setDoubleIfAbsent(out, "top_n_sigma", value);
					break;
				case "--repeat-penalty":
					if (value == null || value.isBlank()) {
						continue;
					}
					setDoubleIfAbsent(out, "repeat_penalty", value);
					break;
				case "--top-k":
					if (value == null || value.isBlank()) {
						continue;
					}
					setIntIfAbsent(out, "top_k", value);
					break;
				case "--presence-penalty":
					if (value == null || value.isBlank()) {
						continue;
					}
					setDoubleIfAbsent(out, "presence_penalty", value);
					break;
				case "--frequency-penalty":
					if (value == null || value.isBlank()) {
						continue;
					}
					setDoubleIfAbsent(out, "frequency_penalty", value);
					break;
				case "--dry-multiplier":
					if (value == null || value.isBlank()) {
						continue;
					}
					setDoubleIfAbsent(out, "dry_multiplier", value);
					break;
				case "--dry-base":
					if (value == null || value.isBlank()) {
						continue;
					}
					setDoubleIfAbsent(out, "dry_base", value);
					break;
				case "--dry-allowed-length":
					if (value == null || value.isBlank()) {
						continue;
					}
					setIntIfAbsent(out, "dry_allowed_length", value);
					break;
				case "--dry-penalty-last-n":
					if (value == null || value.isBlank()) {
						continue;
					}
					setIntIfAbsent(out, "dry_penalty_last_n", value);
					break;
				case "--dry-sequence-breaker":
					if (value == null || value.isBlank()) {
						continue;
					}
					appendStringArrayValue(out, "dry_sequence_breakers", value);
					break;
				case "--enable-thinking":
					if (eq > 0) {
						setBooleanIfAbsent(out, "enable_thinking", value);
						break;
					}
					if (value == null || value.isBlank()) {
						out.addProperty("enable_thinking", true);
						break;
					}
					setBooleanIfAbsent(out, "enable_thinking", value);
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
			if (isThinkingToggleKey(key)) {
				continue;
			}
			requestJson.add(key, value.deepCopy());
		}
	}
	
	/**
	 * 	加入enable_thinking的参数。
	 * @param requestJson
	 * @param sampling
	 */
	private void applyThinkingBySampling(JsonObject requestJson, JsonObject sampling) {
		if (requestJson == null || sampling == null) {
			return;
		}
		if (!readForceThinkingToggle(sampling)) {
			return;
		}
		Boolean enabled = readThinkingToggle(sampling);
		if (enabled == null) {
			return;
		}
		requestJson.addProperty("enable_thinking", enabled);
		ParamTool.handleOpenAIChatThinking(requestJson);
		requestJson.remove("enable_thinking");
	}
	
	/**
	 * 	读取思维链开关的值。
	 * @param sampling
	 * @return
	 */
	private Boolean readThinkingToggle(JsonObject sampling) {
		if (sampling == null) {
			return null;
		}
		for (String key : new String[] {"enable_thinking"}) {
			Boolean value = this.readBoolean(sampling, key);
			if (value != null) {
				return value;
			}
		}
		return null;
	}
	
	/**
	 * 	读取强制思维链开关的值。
	 * @param sampling
	 * @return
	 */
	private boolean readForceThinkingToggle(JsonObject sampling) {
		if (sampling == null) {
			return false;
		}
		Boolean force = readBoolean(sampling, "force_enable_thinking");
		if (force != null) {
			return force;
		}
		return this.readThinkingToggle(sampling) != null;
	}

	private boolean isThinkingToggleKey(String key) {
		if (key == null) {
			return false;
		}
		return "enable_thinking".equals(key) || "force_enable_thinking".equals(key);
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

	private void setBooleanFromKeys(JsonObject out, String targetKey, JsonObject src, String... keys) {
		if (out.has(targetKey) || src == null || keys == null) {
			return;
		}
		for (String k : keys) {
			Boolean v = readBoolean(src, k);
			if (v != null) {
				out.addProperty(targetKey, v);
				return;
			}
		}
	}

	private void setStringArrayFromKeys(JsonObject out, String targetKey, JsonObject src, boolean splitBySemicolon, String... keys) {
		if (out.has(targetKey) || src == null || keys == null) {
			return;
		}
		for (String k : keys) {
			List<String> values = readStringArray(src, k, splitBySemicolon);
			if (values != null && !values.isEmpty()) {
				setStringArrayIfAbsent(out, targetKey, values);
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

	private void setBooleanIfAbsent(JsonObject out, String key, String raw) {
		if (out.has(key)) {
			return;
		}
		Boolean v = parseBoolean(raw);
		if (v != null) {
			out.addProperty(key, v);
		}
	}

	private void setStringArrayIfAbsent(JsonObject out, String key, List<String> values) {
		if (out.has(key) || values == null || values.isEmpty()) {
			return;
		}
		JsonArray arr = new JsonArray();
		for (String value : values) {
			if (value == null) {
				continue;
			}
			String s = value.trim();
			if (s.isEmpty()) {
				continue;
			}
			arr.add(s);
		}
		if (arr.size() > 0) {
			out.add(key, arr);
		}
	}

	private void appendStringArrayValue(JsonObject out, String key, String raw) {
		if (out == null || key == null || raw == null) {
			return;
		}
		String value = raw.trim();
		if (value.isEmpty()) {
			return;
		}
		JsonArray arr;
		if (out.has(key) && out.get(key).isJsonArray()) {
			arr = out.getAsJsonArray(key);
		} else {
			arr = new JsonArray();
			out.add(key, arr);
		}
		arr.add(value);
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

	private List<String> readStringArray(JsonObject obj, String key, boolean splitBySemicolon) {
		if (obj == null || key == null || !obj.has(key)) {
			return null;
		}
		JsonElement el = obj.get(key);
		if (el == null || el.isJsonNull()) {
			return null;
		}
		try {
			if (el.isJsonArray()) {
				List<String> out = new ArrayList<>();
				JsonArray arr = el.getAsJsonArray();
				for (int i = 0; i < arr.size(); i++) {
					JsonElement item = arr.get(i);
					if (item == null || item.isJsonNull()) {
						continue;
					}
					String value = JsonUtil.jsonValueToString(item);
					if (value != null && !value.trim().isEmpty()) {
						out.add(value.trim());
					}
				}
				return out.isEmpty() ? null : out;
			}
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
				String raw = el.getAsString();
				return splitBySemicolon ? parseCliSamplers(raw) : parseJsonStringArray(raw);
			}
		} catch (Exception e) {
			return null;
		}
		return null;
	}

	private Boolean readBoolean(JsonObject obj, String key) {
		if (obj == null || key == null || !obj.has(key)) {
			return null;
		}
		JsonElement el = obj.get(key);
		if (el == null || el.isJsonNull()) {
			return null;
		}
		try {
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
				return el.getAsBoolean();
			}
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
				return el.getAsInt() != 0;
			}
			if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
				return parseBoolean(el.getAsString());
			}
		} catch (Exception e) {
			return null;
		}
		return null;
	}

	private List<String> parseCliSamplers(String raw) {
		if (raw == null) {
			return null;
		}
		List<String> out = new ArrayList<>();
		String[] parts = raw.split("[;,\r\n]+");
		for (String part : parts) {
			if (part == null) {
				continue;
			}
			String s = part.trim();
			if (!s.isEmpty()) {
				out.add(s);
			}
		}
		return out.isEmpty() ? null : out;
	}

	private List<String> parseJsonStringArray(String raw) {
		if (raw == null) {
			return null;
		}
		String text = raw.trim();
		if (text.isEmpty()) {
			return null;
		}
		try {
			JsonElement el = JsonUtil.fromJson(text, JsonElement.class);
			if (el != null && el.isJsonArray()) {
				List<String> out = new ArrayList<>();
				JsonArray arr = el.getAsJsonArray();
				for (int i = 0; i < arr.size(); i++) {
					JsonElement item = arr.get(i);
					if (item == null || item.isJsonNull()) {
						continue;
					}
					String s = JsonUtil.jsonValueToString(item);
					if (s != null && !s.trim().isEmpty()) {
						out.add(s.trim());
					}
				}
				return out.isEmpty() ? null : out;
			}
		} catch (Exception ignore) {
		}
		List<String> single = new ArrayList<>();
		single.add(text);
		return single;
	}

	private boolean looksLikeOptionToken(String token) {
		if (token == null) {
			return false;
		}
		String s = token.trim();
		if (s.isEmpty()) {
			return false;
		}
		if (s.startsWith("--")) {
			return true;
		}
		return s.startsWith("-") && !s.matches("-?\\d+(\\.\\d+)?");
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

	private Boolean parseBoolean(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.trim().toLowerCase();
		if (s.isEmpty()) {
			return null;
		}
		if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s)) {
			return true;
		}
		if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "off".equals(s)) {
			return false;
		}
		return null;
	}
}
