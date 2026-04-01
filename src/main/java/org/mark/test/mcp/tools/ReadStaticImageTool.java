package org.mark.test.mcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

import org.mark.test.mcp.IMCPTool;
import org.mark.test.mcp.struct.McpMessage;
import org.mark.test.mcp.struct.McpToolInputSchema;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;


/**
 * 	用来测试读图的。
 */
public class ReadStaticImageTool implements IMCPTool {

	// private static final Logger logger = LoggerFactory.getLogger(ReadStaticImageTool.class);
	private static final long MAX_IMAGE_BYTES = 2L * 1024L * 1024L;

	public ReadStaticImageTool() {
		
	}

	@Override
	public String getMcpName() {
		return "read_static_image";
	}

	@Override
	public String getMcpTitle() {
		return "读取指定路径图片";
	}

	@Override
	public String getMcpDescription() {
		return "读取指定路径的图片并返回base64内容";
	}

	@Override
	public McpToolInputSchema getInputSchema() {
		return new McpToolInputSchema().addProperty("absolutePath", "string", "图片绝对路径，例如 C:\\images\\a.png", true);
	}

	@Override
	public McpMessage execute(String serviceKey, JsonObject arguments) {
		String absolutePath = this.getAbsolutePath(arguments);
		if (absolutePath == null || absolutePath.isBlank()) {
			return new McpMessage().addText("图片读取失败: absolutePath不能为空");
		}
		
		JsonObject imageResult = this.readImage(absolutePath);
		if (!imageResult.has("success") || !imageResult.get("success").getAsBoolean()) {
			return new McpMessage().addText("图片读取失败: " + imageResult.get("error").getAsString());
		}
		String summary = "图片已加载: " + imageResult.get("fileName").getAsString() + ", size=" + imageResult.get("byteSize").getAsLong() + " bytes";
		//McpMessage msg = new McpMessage().addImage(imageResult.get("base64").getAsString(), imageResult.get("mimeType").getAsString()).addText(summary);
		return new McpMessage().addImage(imageResult.get("base64").getAsString(), imageResult.get("mimeType").getAsString()).addText(summary);
	}

	private JsonObject readImage(String absolutePathText) {
		JsonObject result = new JsonObject();
		Path rawPath;
		Path absolutePath;
		try {
			rawPath = Paths.get(absolutePathText);
			absolutePath = rawPath.toAbsolutePath().normalize();
		} catch (Exception e) {
			result.addProperty("success", false);
			result.addProperty("error", "路径格式非法: " + absolutePathText);
			return result;
		}
		if (!rawPath.isAbsolute()) {
			result.addProperty("success", false);
			result.addProperty("error", "必须传入绝对路径: " + absolutePathText);
			return result;
		}
		if (!Files.exists(absolutePath) || !Files.isRegularFile(absolutePath)) {
			result.addProperty("success", false);
			result.addProperty("error", "图片文件不存在: " + absolutePath);
			return result;
		}
		try {
			long size = Files.size(absolutePath);
			if (size > MAX_IMAGE_BYTES) {
				result.addProperty("success", false);
				result.addProperty("error", "图片过大，当前限制为2MB");
				result.addProperty("byteSize", size);
				return result;
			}
			byte[] bytes = Files.readAllBytes(absolutePath);
			String mimeType = this.detectMimeType(absolutePath);
			String base64 = Base64.getEncoder().encodeToString(bytes);
			result.addProperty("success", true);
			result.addProperty("fileName", absolutePath.getFileName().toString());
			result.addProperty("imagePath", absolutePath.toString());
			result.addProperty("mimeType", mimeType);
			result.addProperty("byteSize", bytes.length);
			result.addProperty("base64", base64);
			return result;
		} catch (IOException e) {
			result.addProperty("success", false);
			result.addProperty("error", "读取图片失败: " + e.getMessage());
			return result;
		}
	}

	private String getAbsolutePath(JsonObject arguments) {
		if (arguments == null || !arguments.has("absolutePath") || arguments.get("absolutePath").isJsonNull()) {
			return "";
		}
		try {
			return arguments.get("absolutePath").getAsString();
		} catch (Exception e) {
			return "";
		}
	}

	private String detectMimeType(Path path) {
		try {
			String mimeType = Files.probeContentType(path);
			if (mimeType != null && !mimeType.isBlank()) {
				return mimeType;
			}
		} catch (IOException e) {
			// logger.info("识别图片MIME失败: path={}, error={}", path, e.getMessage());
		}
		String fileName = path.getFileName().toString().toLowerCase();
		if (fileName.endsWith(".png")) {
			return "image/png";
		}
		if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
			return "image/jpeg";
		}
		if (fileName.endsWith(".webp")) {
			return "image/webp";
		}
		if (fileName.endsWith(".gif")) {
			return "image/gif";
		}
		return "application/octet-stream";
	}
}
