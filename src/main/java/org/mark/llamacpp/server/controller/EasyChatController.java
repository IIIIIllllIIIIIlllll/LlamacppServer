package org.mark.llamacpp.server.controller;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.exception.RequestMethodException;
import org.mark.llamacpp.server.struct.ApiResponse;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;

/**
 * 	新版的EasyChat后端，保存聊天记录。
 */
public class EasyChatController implements BaseController {

	private static final Logger logger = LoggerFactory.getLogger(EasyChatController.class);
	private static final Object STATE_LOCK = new Object();
	private static final String STATE_REVISION_KEY = "_revision";
	private static final String BASE_REVISION_FIELD = "baseRevision";
	private static final String REVISION_CONFLICT_CODE = "STATE_REVISION_CONFLICT";

	private static final String PATH_STATE = "/api/easy-chat/state";

	@Override
	public boolean handleRequest(String uri, ChannelHandlerContext ctx, FullHttpRequest request)
			throws RequestMethodException {
		if (uri.startsWith(PATH_STATE)) {
			this.handleStateRequest(ctx, request);
			return true;
		}
		return false;
	}
	
	private void handleStateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		if (request.method() == HttpMethod.GET) {
			this.handleLoadStateRequest(ctx, request);
			return;
		}
		if (request.method() == HttpMethod.POST) {
			this.handleSaveStateRequest(ctx, request);
			return;
		}
		this.assertRequestMethod(true, "只支持GET和POST请求");
	}
	
	private void handleLoadStateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.GET, "只支持GET请求");
		try {
			Path stateDir = this.getStateDirPath();
			Path stateFile = this.getStateFilePath(stateDir);
			Path conversationDir = this.getConversationDirPath(stateDir);
			JsonObject state = new JsonObject();
			boolean exists;
			String revision = "";
			synchronized (STATE_LOCK) {
				exists = Files.exists(stateFile);
				if (exists) {
					state = this.loadState(stateDir, stateFile);
					revision = this.resolveStateRevision(stateFile, this.readJsonObject(stateFile));
				}
			}
			Map<String, Object> data = new HashMap<>();
			data.put("state", state);
			data.put("exists", exists);
			data.put("revision", revision);
			data.put("file", stateFile.toAbsolutePath().normalize().toString());
			data.put("conversationDir", conversationDir.toAbsolutePath().normalize().toString());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("加载 easy-chat 状态失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("加载 easy-chat 状态失败: " + e.getMessage()));
		}
	}

	private void handleSaveStateRequest(ChannelHandlerContext ctx, FullHttpRequest request) throws RequestMethodException {
		this.assertRequestMethod(request.method() != HttpMethod.POST, "只支持POST请求");
		try {
			JsonObject body = JsonUtil.parseFullHttpRequestToJsonObject(request, ctx);
			if (body == null) {
				return;
			}
			Path stateDir = this.getStateDirPath();
			Path stateFile = this.getStateFilePath(stateDir);
			Path conversationDir = this.getConversationDirPath(stateDir);
			SaveStateResult saveResult;
			synchronized (STATE_LOCK) {
				saveResult = this.saveState(body, stateFile, conversationDir);
			}
			Map<String, Object> data = new HashMap<>();
			data.put("saved", true);
			data.put("file", stateFile.toAbsolutePath().normalize().toString());
			data.put("size", Files.size(stateFile));
			data.put("conversationDir", conversationDir.toAbsolutePath().normalize().toString());
			data.put("conversationCount", saveResult.conversationCount());
			data.put("revision", saveResult.revision());
			LlamaServer.sendJsonResponse(ctx, ApiResponse.success(data));
		} catch (Exception e) {
			logger.info("保存 easy-chat 状态失败", e);
			LlamaServer.sendJsonResponse(ctx, ApiResponse.error("保存 easy-chat 状态失败: " + e.getMessage()));
		}
	}

	private JsonObject loadState(Path stateDir, Path stateFile) throws Exception {
		JsonObject storedState = this.readJsonObject(stateFile);
		Path conversationDir = this.getConversationDirPath(stateDir);
		Map<String, JsonObject> recoveredConversationMap = this.loadConversationFileMap(conversationDir);
		if (storedState == null) {
			JsonObject recoveredState = new JsonObject();
			recoveredState.add("conversations", this.appendRecoveredConversations(new JsonArray(), recoveredConversationMap));
			return recoveredState;
		}
		JsonObject loadedState = storedState.deepCopy();
		JsonArray conversations = this.loadConversations(stateDir, storedState, recoveredConversationMap);
		loadedState.add("conversations", conversations);
		return loadedState;
	}

	private SaveStateResult saveState(JsonObject body, Path stateFile, Path conversationDir) throws Exception {
		boolean hasBaseRevision = body.has(BASE_REVISION_FIELD);
		String baseRevision = JsonUtil.getJsonString(body, BASE_REVISION_FIELD, "");
		String normalizedBaseRevision = baseRevision == null ? "" : baseRevision.trim();
		if (hasBaseRevision && Files.exists(stateFile)) {
			JsonObject currentState = this.readJsonObject(stateFile);
			String currentRevision = this.resolveStateRevision(stateFile, currentState);
			if (normalizedBaseRevision.isEmpty() || !normalizedBaseRevision.equals(currentRevision)) {
				throw new IllegalStateException(REVISION_CONFLICT_CODE);
			}
		}
		JsonObject state = body.deepCopy();
		state.remove(BASE_REVISION_FIELD);
		JsonArray conversations = this.getConversationArray(body);
		Set<String> expectedFiles = this.writeConversationFiles(conversationDir, conversations);
		state.add("conversations", this.buildConversationSummaries(conversations));
		String nextRevision = UUID.randomUUID().toString();
		state.addProperty(STATE_REVISION_KEY, nextRevision);
		this.writeJsonFile(stateFile, state);
		this.deleteStaleConversationFiles(conversationDir, expectedFiles);
		return new SaveStateResult(conversations.size(), nextRevision);
	}

	private JsonArray loadConversations(Path stateDir, JsonObject storedState, Map<String, JsonObject> recoveredConversationMap)
			throws Exception {
		JsonArray summaries = this.getConversationArray(storedState);
		Path conversationDir = this.getConversationDirPath(stateDir);
		JsonArray conversations = new JsonArray();
		for (int i = 0; i < summaries.size(); i++) {
			JsonElement element = summaries.get(i);
			if (element == null || !element.isJsonObject()) {
				continue;
			}
			JsonObject summary = element.getAsJsonObject();
			String id = JsonUtil.getJsonString(summary, "id", null);
			String storageKey = this.normalizeStorageKey(JsonUtil.getJsonString(summary, "storageKey", null), id);
			JsonObject recoveredConversation = recoveredConversationMap.remove(storageKey);
			conversations.add(this.loadConversation(summary, conversationDir, storageKey, recoveredConversation));
		}
		return this.appendRecoveredConversations(conversations, recoveredConversationMap);
	}

	private JsonObject loadConversation(JsonObject summary, Path conversationDir, String storageKey, JsonObject recoveredConversation)
			throws Exception {
		JsonObject conversation = recoveredConversation == null ? new JsonObject() : recoveredConversation.deepCopy();
		Path conversationFile = this.getConversationFilePath(conversationDir, storageKey);
		if (conversation.entrySet().isEmpty() && Files.exists(conversationFile)) {
			JsonObject storedConversation = this.readJsonObject(conversationFile);
			if (storedConversation != null) {
				conversation = storedConversation.deepCopy();
			}
		}
		String id = JsonUtil.getJsonString(summary, "id", null);
		for (Map.Entry<String, JsonElement> entry : summary.entrySet()) {
			String key = entry.getKey();
			if ("messages".equals(key) || "storageKey".equals(key) || "messageCount".equals(key)) {
				continue;
			}
			conversation.add(key, entry.getValue() == null ? null : entry.getValue().deepCopy());
		}
		if (!conversation.has("id") && id != null && !id.isBlank()) {
			conversation.addProperty("id", id);
		}
		if (!conversation.has("messages") || !conversation.get("messages").isJsonArray()) {
			conversation.add("messages", new JsonArray());
		}
		return conversation;
	}

	private Map<String, JsonObject> loadConversationFileMap(Path conversationDir) throws Exception {
		Map<String, JsonObject> conversationMap = new HashMap<>();
		if (!Files.exists(conversationDir)) {
			return conversationMap;
		}
		try (Stream<Path> stream = Files.list(conversationDir)) {
			stream.filter(Files::isRegularFile)
				.filter(path -> path.getFileName().toString().endsWith(".json"))
				.forEach(path -> {
					try {
						JsonObject conversation = this.readJsonObject(path);
						if (conversation != null) {
							conversationMap.put(this.getStorageKeyFromFile(path), conversation);
						}
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				});
		} catch (RuntimeException e) {
			if (e.getCause() instanceof Exception ex) {
				throw ex;
			}
			throw e;
		}
		return conversationMap;
	}

	private JsonArray appendRecoveredConversations(JsonArray conversations, Map<String, JsonObject> recoveredConversationMap) {
		if (recoveredConversationMap.isEmpty()) {
			return conversations;
		}
		List<JsonObject> recoveredConversations = new ArrayList<>(recoveredConversationMap.values());
		recoveredConversations.sort((left, right) -> Long.compare(
				this.getConversationSortTime(right),
				this.getConversationSortTime(left)));
		for (JsonObject conversation : recoveredConversations) {
			conversations.add(conversation.deepCopy());
		}
		return conversations;
	}

	private long getConversationSortTime(JsonObject conversation) {
		long updatedAt = JsonUtil.getJsonLong(conversation, "updatedAt", 0L);
		long createdAt = JsonUtil.getJsonLong(conversation, "createdAt", 0L);
		return Math.max(updatedAt, createdAt);
	}

	private String getStorageKeyFromFile(Path path) {
		String fileName = path.getFileName().toString();
		if (fileName.endsWith(".json")) {
			return fileName.substring(0, fileName.length() - 5);
		}
		return fileName;
	}

	private Set<String> writeConversationFiles(Path conversationDir, JsonArray conversations) throws Exception {
		if (!Files.exists(conversationDir)) {
			Files.createDirectories(conversationDir);
		}
		Set<String> expectedFiles = new HashSet<>();
		for (int i = 0; i < conversations.size(); i++) {
			JsonElement element = conversations.get(i);
			if (element == null || !element.isJsonObject()) {
				continue;
			}
			JsonObject conversation = element.getAsJsonObject().deepCopy();
			String id = JsonUtil.getJsonString(conversation, "id", null);
			String storageKey = this.normalizeStorageKey(null, id);
			Path conversationFile = this.getConversationFilePath(conversationDir, storageKey);
			expectedFiles.add(conversationFile.getFileName().toString());
			this.writeJsonFile(conversationFile, conversation);
		}
		return expectedFiles;
	}

	private JsonArray buildConversationSummaries(JsonArray conversations) {
		JsonArray summaries = new JsonArray();
		for (int i = 0; i < conversations.size(); i++) {
			JsonElement element = conversations.get(i);
			if (element == null || !element.isJsonObject()) {
				continue;
			}
			JsonObject conversation = element.getAsJsonObject();
			JsonObject summary = conversation.deepCopy();
			String id = JsonUtil.getJsonString(summary, "id", null);
			String storageKey = this.normalizeStorageKey(null, id);
			int messageCount = 0;
			if (summary.has("messages") && summary.get("messages").isJsonArray()) {
				messageCount = summary.getAsJsonArray("messages").size();
			}
			summary.remove("messages");
			summary.addProperty("storageKey", storageKey);
			summary.addProperty("messageCount", messageCount);
			summaries.add(summary);
		}
		return summaries;
	}

	private void deleteStaleConversationFiles(Path conversationDir, Set<String> expectedFiles) throws Exception {
		if (!Files.exists(conversationDir)) {
			return;
		}
		try (Stream<Path> stream = Files.list(conversationDir)) {
			stream.filter(Files::isRegularFile).forEach(path -> {
				String fileName = path.getFileName().toString();
				if (!fileName.endsWith(".json")) {
					return;
				}
				if (!expectedFiles.contains(fileName)) {
					try {
						Files.deleteIfExists(path);
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				}
			});
		} catch (RuntimeException e) {
			if (e.getCause() instanceof Exception ex) {
				throw ex;
			}
			throw e;
		}
	}

	private void writeJsonFile(Path file, JsonElement json) throws Exception {
		Path parent = file.getParent();
		if (parent != null && !Files.exists(parent)) {
			Files.createDirectories(parent);
		}
		Path tempFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
		Files.writeString(tempFile, JsonUtil.toJson(json), StandardCharsets.UTF_8);
		try {
			Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private JsonObject readJsonObject(Path file) throws Exception {
		String json = Files.readString(file, StandardCharsets.UTF_8);
		if (json == null || json.trim().isEmpty()) {
			return new JsonObject();
		}
		JsonElement element = JsonUtil.fromJson(json, JsonElement.class);
		if (element == null || !element.isJsonObject()) {
			return null;
		}
		return element.getAsJsonObject();
	}

	private JsonArray getConversationArray(JsonObject state) {
		if (state == null || !state.has("conversations") || !state.get("conversations").isJsonArray()) {
			return new JsonArray();
		}
		return state.getAsJsonArray("conversations");
	}

	private String normalizeStorageKey(String storageKey, String conversationId) {
		String key = storageKey == null ? "" : storageKey.trim();
		if (!key.isEmpty() && key.matches("[A-Za-z0-9_-]+")) {
			return key;
		}
		String source = conversationId == null ? "" : conversationId.trim();
		return Base64.getUrlEncoder().withoutPadding().encodeToString(source.getBytes(StandardCharsets.UTF_8));
	}

	private String resolveStateRevision(Path stateFile, JsonObject state) throws Exception {
		String revision = JsonUtil.getJsonString(state, STATE_REVISION_KEY, "");
		if (revision != null && !revision.isBlank()) {
			return revision.trim();
		}
		if (!Files.exists(stateFile)) {
			return "";
		}
		long modifiedAt = Files.getLastModifiedTime(stateFile).toMillis();
		long size = Files.size(stateFile);
		return "legacy-" + Long.toUnsignedString(modifiedAt, 36) + "-" + Long.toUnsignedString(size, 36);
	}

	private Path getConversationFilePath(Path conversationDir, String storageKey) {
		return conversationDir.resolve(storageKey + ".json").toAbsolutePath().normalize();
	}

	private Path getStateDirPath() throws Exception {
		Path dir = LlamaServer.getCachePath().resolve("easy-chat").toAbsolutePath().normalize();
		if (!Files.exists(dir)) {
			Files.createDirectories(dir);
		}
		return dir;
	}

	private Path getStateFilePath(Path stateDir) {
		return stateDir.resolve("state.json").toAbsolutePath().normalize();
	}

	private Path getConversationDirPath(Path stateDir) {
		return stateDir.resolve("conversations").toAbsolutePath().normalize();
	}

	private record SaveStateResult(int conversationCount, String revision) {}
}
