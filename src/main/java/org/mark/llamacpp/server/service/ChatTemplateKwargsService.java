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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class ChatTemplateKwargsService {

	private static final Logger logger = LoggerFactory.getLogger(ChatTemplateKwargsService.class);
	private static final Path CHAT_TEMPLATE_KWARGS_FILE = Paths.get("config", "model-chat-template-kwargs.json");

	private static final ChatTemplateKwargsService INSTANCE = new ChatTemplateKwargsService();
	private final Object reloadLock = new Object();
	private volatile long kwargsLastModified = -1L;
	private final Map<String, JsonObject> kwargsConfigByModel = new ConcurrentHashMap<>();

	public static ChatTemplateKwargsService getInstance() {
		return INSTANCE;
	}

	static {
		INSTANCE.init();
	}

	private ChatTemplateKwargsService() {

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
	 * 	注入chat template kwargs到请求中。
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
		JsonObject kwargs = kwargsConfigByModel.get(modelId);
		if (kwargs == null) {
			return;
		}
		this.injectChatTemplateKwargs(requestJson, kwargs);
	}

	/**
	 * 	查询指定模型的chat template kwargs配置。
	 * @param modelId
	 * @return
	 */
	public JsonObject getOpenAIChatTemplateKwargs(String modelId) {
		if (modelId == null) {
			return null;
		}
		String safeModelId = modelId.trim();
		if (safeModelId.isEmpty()) {
			return null;
		}
		reloadCaches(false);
		JsonObject kwargs = kwargsConfigByModel.get(safeModelId);
		return kwargs == null ? null : kwargs.deepCopy();
	}

	/**
	 * 	注入chat template kwargs到请求中。
	 * @param requestJson
	 * @param kwargs
	 */
	private void injectChatTemplateKwargs(JsonObject requestJson, JsonObject kwargs) {
		if (requestJson == null || kwargs == null) {
			return;
		}
		JsonObject targetKwargs = null;
		if (requestJson.has("chat_template_kwargs") && requestJson.get("chat_template_kwargs").isJsonObject()) {
			targetKwargs = requestJson.getAsJsonObject("chat_template_kwargs");
		} else {
			targetKwargs = new JsonObject();
			requestJson.add("chat_template_kwargs", targetKwargs);
		}
		for (Map.Entry<String, JsonElement> item : kwargs.entrySet()) {
			String key = item.getKey();
			JsonElement value = item.getValue();
			if (key == null || value == null || value.isJsonNull()) {
				continue;
			}
			targetKwargs.add(key, value.deepCopy());
		}
	}

	/**
	 * 	查询所有模型配置。
	 * @return
	 */
	public Map<String, Object> listAllKwargsConfigs() {
		Map<String, Object> data = new HashMap<>();
		reloadCaches(false);
		Map<String, JsonObject> configs = new LinkedHashMap<>(kwargsConfigByModel);
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

	/**
	 * 	更新或新增指定modelId的chat template kwargs配置。
	 * @param modelId
	 * @param kwargsConfig
	 * @return
	 */
	public JsonObject upsertKwargsConfig(String modelId, JsonObject kwargsConfig) {
		String safeModelId = modelId == null ? "" : modelId.trim();
		if (safeModelId.isEmpty()) {
			throw new IllegalArgumentException("缺少modelId参数");
		}
		JsonObject in = kwargsConfig == null ? new JsonObject() : kwargsConfig;
		synchronized (reloadLock) {
			JsonObject root = readKwargsRoot();
			root.add(safeModelId, in);
			writeKwargsRoot(root);
		}
		reloadCaches(true);
		return in;
	}

	/**
	 * 	删除指定modelId的chat template kwargs配置。
	 * @param modelId
	 * @return
	 */
	public Map<String, Object> deleteKwargsConfig(String modelId) {
		String safeModelId = modelId == null ? "" : modelId.trim();
		if (safeModelId.isEmpty()) {
			throw new IllegalArgumentException("缺少modelId参数");
		}
		int removedCount = 0;
		synchronized (reloadLock) {
			JsonObject root = readKwargsRoot();
			if (root.has(safeModelId)) {
				root.remove(safeModelId);
				removedCount++;
			}
			writeKwargsRoot(root);
		}
		reloadCaches(true);
		Map<String, Object> out = new HashMap<>();
		out.put("deleted", removedCount > 0);
		out.put("modelId", safeModelId);
		out.put("removedCount", removedCount);
		return out;
	}

	private void reloadCaches(boolean force) {
		synchronized (reloadLock) {
			long kwargsMtime = getLastModifiedSafe(CHAT_TEMPLATE_KWARGS_FILE);
			if (!force && kwargsMtime == kwargsLastModified) {
				return;
			}
			Map<String, JsonObject> kwargsMap = buildKwargsConfigMap();
			kwargsConfigByModel.clear();
			kwargsConfigByModel.putAll(kwargsMap);
			kwargsLastModified = kwargsMtime;
		}
	}

	private Map<String, JsonObject> buildKwargsConfigMap() {
		Map<String, JsonObject> out = new HashMap<>();
		try {
			JsonObject root = readKwargsRoot();
			if (root == null || root.size() == 0) {
				return out;
			}
			for (Map.Entry<String, JsonElement> item : root.entrySet()) {
				String modelId = item.getKey() == null ? "" : item.getKey().trim();
				if (modelId.isEmpty()) {
					continue;
				}
				JsonElement kwargsEl = item.getValue();
				if (kwargsEl == null || !kwargsEl.isJsonObject()) {
					continue;
				}
				out.put(modelId, kwargsEl.getAsJsonObject());
			}
		} catch (Exception e) {
			logger.info("构建chat template kwargs缓存失败: {}", e.getMessage());
		}
		return out;
	}

	private JsonObject readKwargsRoot() {
		try {
			if (!Files.exists(CHAT_TEMPLATE_KWARGS_FILE)) {
				return new JsonObject();
			}
			String text = Files.readString(CHAT_TEMPLATE_KWARGS_FILE, StandardCharsets.UTF_8);
			JsonObject root = JsonUtil.fromJson(text, JsonObject.class);
			return root == null ? new JsonObject() : root;
		} catch (Exception e) {
			logger.info("读取chat template kwargs配置文件失败: {}", e.getMessage());
			return new JsonObject();
		}
	}

	private void writeKwargsRoot(JsonObject root) {
		try {
			Path parent = CHAT_TEMPLATE_KWARGS_FILE.getParent();
			if (parent != null && !Files.exists(parent)) {
				Files.createDirectories(parent);
			}
			Files.write(CHAT_TEMPLATE_KWARGS_FILE, JsonUtil.toJson(root).getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new RuntimeException("写入chat template kwargs配置失败: " + e.getMessage(), e);
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
}
