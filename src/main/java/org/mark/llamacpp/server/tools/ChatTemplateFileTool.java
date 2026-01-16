package org.mark.llamacpp.server.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.mark.llamacpp.server.LlamaServer;

public final class ChatTemplateFileTool {
	private ChatTemplateFileTool() {
	}

	public static Path resolveChatTemplateCachePath(String modelId) {
		if (modelId == null || modelId.trim().isEmpty()) {
			return null;
		}
		Path cacheDir = LlamaServer.getCachePath();
		String safeModelId = modelId.replaceAll("[^a-zA-Z0-9._-]", "_");
		return cacheDir.resolve(safeModelId + ".jinja").toAbsolutePath().normalize();
	}

	public static String writeChatTemplateToCacheFile(String modelId, String chatTemplate) throws Exception {
		if (modelId == null || modelId.trim().isEmpty()) {
			return null;
		}
		if (chatTemplate == null || chatTemplate.trim().isEmpty()) {
			return null;
		}

		Path templatePath = resolveChatTemplateCachePath(modelId);
		if (templatePath == null) {
			return null;
		}
		Files.createDirectories(templatePath.getParent());
		Files.write(templatePath, chatTemplate.getBytes(StandardCharsets.UTF_8));
		return templatePath.toString();
	}

	public static String getChatTemplateCacheFilePathIfExists(String modelId) {
		try {
			Path templatePath = resolveChatTemplateCachePath(modelId);
			if (templatePath == null) return null;
			return Files.exists(templatePath) && Files.isRegularFile(templatePath) ? templatePath.toString() : null;
		} catch (Exception ignore) {
			return null;
		}
	}

	public static boolean deleteChatTemplateCacheFile(String modelId) {
		try {
			Path templatePath = resolveChatTemplateCachePath(modelId);
			if (templatePath == null) return false;
			if (!Files.exists(templatePath) || !Files.isRegularFile(templatePath)) return false;
			Files.delete(templatePath);
			return true;
		} catch (Exception ignore) {
			return false;
		}
	}

	public static String readChatTemplateFromCacheFile(String modelId) {
		if (modelId == null || modelId.trim().isEmpty()) {
			return null;
		}
		try {
			Path templatePath = resolveChatTemplateCachePath(modelId);
			if (templatePath == null) return null;
			if (!Files.exists(templatePath)) {
				return null;
			}
			String content = Files.readString(templatePath, StandardCharsets.UTF_8);
			return content != null && !content.trim().isEmpty() ? content : null;
		} catch (Exception ignore) {
			return null;
		}
	}
}
