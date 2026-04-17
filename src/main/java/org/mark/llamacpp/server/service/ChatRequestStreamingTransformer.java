package org.mark.llamacpp.server.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;



/**
 * 	OH天哪，是无敌的流式解析。
 */
public class ChatRequestStreamingTransformer {

	private static final Logger logger = LoggerFactory.getLogger(ChatRequestStreamingTransformer.class);

	private final int maxFieldBytes;
	private final int maxBufferedBytes;
	
	
	
	public ChatRequestStreamingTransformer(int maxFieldBytes, int maxBufferedBytes) {
		this.maxFieldBytes = maxFieldBytes;
		this.maxBufferedBytes = maxBufferedBytes;
	}
	
	
	/**
	 * 	变形金刚。错误的，其实是解析输入流。
	 * @param input
	 * @param output
	 * @param callback
	 * @return
	 * @throws IOException
	 */
	public TransformResult transform(InputStream input, OutputStream output, ModelResolvedCallback callback) throws IOException {
		JsonObject bufferedFields = new JsonObject();
		int totalBufferedBytes = 0;
		String modelName = null;
		boolean isStream = false;
		boolean modelResolved = false;

		// 这里只解析顶层结构：
		// 1. messages 直接按字节流透传，避免超大 base64 进入 Java String
		// 2. 其它顶层小字段缓冲到 bufferedFields，后续在这里做 thinking / sampling 覆盖
		PushbackInputStream stream = new PushbackInputStream(input, 1);
		int firstToken = this.nextNonWhitespace(stream);
		if (firstToken != '{') {
			throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
		}

		output.write('{');
		boolean firstParsedField = true;
		boolean firstOutputField = true;

		while (true) {
			int token = this.nextNonWhitespace(stream);
			if (token == '}') {
				break;
			}
			if (!firstParsedField) {
				if (token != ',') {
					throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
				}
				token = this.nextNonWhitespace(stream);
			}
			firstParsedField = false;
			if (token != '"') {
				throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
			}

			String fieldName = this.readJsonString(stream);
			int colon = this.nextNonWhitespace(stream);
			if (colon != ':') {
				throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
			}

			int valueStart = this.nextNonWhitespace(stream);
			if (valueStart < 0) {
				throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
			}

			// messages 是大字段热点，保持原样流式拷贝，不走 JsonObject 解析。
			if ("messages".equals(fieldName)) {
				if (!firstOutputField) {
					output.write(',');
				}
				output.write(MESSAGES_FIELD_PREFIX);
				this.copyValue(stream, valueStart, output);
				output.flush();
				firstOutputField = false;
				continue;
			}
			
			byte[] rawBytes = this.readCurrentValue(stream, valueStart, fieldName);
			totalBufferedBytes += rawBytes.length;
			if (totalBufferedBytes > maxBufferedBytes) {
				throw new StreamingRequestException(400, "Request contains oversized top-level fields", null);
			}
			
			JsonElement element;
			try {
				element = JsonParser.parseString(new String(rawBytes, StandardCharsets.UTF_8));
			} catch (Exception e) {
				throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
			}
			if (element == null) {
				throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
			}
			bufferedFields.add(fieldName, element);

			if ("model".equals(fieldName) && !modelResolved) {
				String candidate = this.readModelName(element);
				if (candidate != null && !candidate.isBlank()) {
					modelName = candidate;
					modelResolved = true;
					if (callback != null) {
						callback.onModelResolved(modelName);
					}
					logger.info("聊天流式请求已解析到模型字段: {}", modelName);
				}
			} else if ("stream".equals(fieldName)) {
				Boolean streamValue = readBooleanLenient(element);
				if (streamValue != null) {
					isStream = streamValue.booleanValue();
				}
			}
		}
		// 注入请求体中的思维链开关
		this.applyThinkingInjection(bufferedFields);

		modelName = this.readModelName(bufferedFields.get("model"));
		if (modelName == null) {
			throw new StreamingRequestException(400, "Missing required parameter: model", "model");
		}
		if (modelName.isBlank()) {
			throw new StreamingRequestException(400, "Invalid parameter: model", "model");
		}

		// 这里做chat-template-kwargs注入
		this.applyChatTemplateKwargsInjection(bufferedFields, modelName);
		// 这里做采样覆盖操作。
		this.applySamplingInjection(bufferedFields, modelName);
		
		Boolean streamValue = this.readBooleanLenient(bufferedFields.get("stream"));
		if (streamValue != null) {
			isStream = streamValue.booleanValue();
		}

		for (Map.Entry<String, JsonElement> entry : bufferedFields.entrySet()) {
			if (!firstOutputField) {
				output.write(',');
			}
			this.writeBufferedField(output, entry.getKey(), entry.getValue());
			firstOutputField = false;
		}
		output.write('}');
		output.flush();
		return new TransformResult(modelName, isStream);
	}
	
	private static final byte[] MESSAGES_FIELD_PREFIX = "\"messages\":".getBytes(StandardCharsets.UTF_8);
	
	/**
	 * 	
	 * @param input
	 * @param firstByte
	 * @param fieldName
	 * @return
	 * @throws IOException
	 */
	private byte[] readCurrentValue(PushbackInputStream input, int firstByte, String fieldName) throws IOException {
		LimitedByteArrayOutputStream out = new LimitedByteArrayOutputStream(maxFieldBytes, fieldName);
		try {
			this.copyValue(input, firstByte, out);
		} catch (IllegalStateException e) {
			throw new StreamingRequestException(400, e.getMessage(), fieldName);
		}
		return out.toByteArray();
	}
	
	/**
	 * 	
	 * @param input
	 * @param firstByte
	 * @param output
	 * @throws IOException
	 */
	private void copyValue(PushbackInputStream input, int firstByte, OutputStream output) throws IOException {
		if (firstByte == '"') {
			this.copyString(input, output);
			return;
		}
		if (firstByte == '{' || firstByte == '[') {
			this.copyComposite(input, firstByte, output);
			return;
		}
		if (isPrimitiveStart(firstByte)) {
			copyPrimitive(input, firstByte, output);
			return;
		}
		throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
	}
	
	/**
	 * 	
	 * @param input
	 * @param output
	 * @throws IOException
	 */
	private void copyString(PushbackInputStream input, OutputStream output) throws IOException {
		output.write('"');
		boolean escaped = false;
		while (true) {
			int b = input.read();
			if (b < 0) {
				throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
			}
			output.write(b);
			if (escaped) {
				escaped = false;
				continue;
			}
			if (b == '\\') {
				escaped = true;
				continue;
			}
			if (b == '"') {
				return;
			}
		}
	}
	
	/**
	 * 	这是拷贝啥来着，忘了。
	 * @param input
	 * @param firstByte
	 * @param output
	 * @throws IOException
	 */
	private void copyComposite(PushbackInputStream input, int firstByte, OutputStream output) throws IOException {
		int objectDepth = firstByte == '{' ? 1 : 0;
		int arrayDepth = firstByte == '[' ? 1 : 0;
		boolean inString = false;
		boolean escaped = false;
		output.write(firstByte);
		while (objectDepth > 0 || arrayDepth > 0) {
			int b = input.read();
			if (b < 0) {
				throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
			}
			output.write(b);
			if (inString) {
				if (escaped) {
					escaped = false;
				} else if (b == '\\') {
					escaped = true;
				} else if (b == '"') {
					inString = false;
				}
				continue;
			}
			if (b == '"') {
				inString = true;
			} else if (b == '{') {
				objectDepth++;
			} else if (b == '}') {
				objectDepth--;
			} else if (b == '[') {
				arrayDepth++;
			} else if (b == ']') {
				arrayDepth--;
			}
		}
	}
	
	/**
	 * 	
	 * @param input
	 * @param firstByte
	 * @param output
	 * @throws IOException
	 */
	private void copyPrimitive(PushbackInputStream input, int firstByte, OutputStream output) throws IOException {
		output.write(firstByte);
		while (true) {
			int b = input.read();
			if (b < 0) {
				return;
			}
			if (isValueTerminator(b)) {
				input.unread(b);
				return;
			}
			output.write(b);
		}
	}
	
	private boolean isPrimitiveStart(int value) {
		return value == 't' || value == 'f' || value == 'n' || value == '-' || (value >= '0' && value <= '9');
	}
	
	private boolean isValueTerminator(int value) {
		return value == ',' || value == '}' || value == ']' || value == ' ' || value == '\t' || value == '\r' || value == '\n';
	}
	
	private int nextNonWhitespace(PushbackInputStream input) throws IOException {
		while (true) {
			int b = input.read();
			if (b < 0) {
				return -1;
			}
			if (!Character.isWhitespace(b)) {
				return b;
			}
		}
	}
	
	private String readJsonString(PushbackInputStream input) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write('"');
		boolean escaped = false;
		while (true) {
			int b = input.read();
			if (b < 0) {
				throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
			}
			out.write(b);
			if (escaped) {
				escaped = false;
				continue;
			}
			if (b == '\\') {
				escaped = true;
				continue;
			}
			if (b == '"') {
				break;
			}
		}
		try {
			return JsonParser.parseString(out.toString(StandardCharsets.UTF_8)).getAsString();
		} catch (Exception e) {
			throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
		}
	}
	
	private void writeBufferedField(OutputStream output, String fieldName, JsonElement value) throws IOException {
		output.write(JsonUtil.toJson(fieldName).getBytes(StandardCharsets.UTF_8));
		output.write(':');
		output.write(JsonUtil.toJson(value).getBytes(StandardCharsets.UTF_8));
	}
	
	/**
	 * 	
	 * @param requestJson
	 * @param modelName
	 */
	private void applyChatTemplateKwargsInjection(JsonObject requestJson, String modelName) {
		if (requestJson == null || modelName == null || modelName.isBlank()) {
			return;
		}
		ChatTemplateKwargsService.getInstance().handleOpenAI(requestJson);
	}
	
	/**
	 * 	这里是采样覆盖专用。
	 * @param requestJson
	 * @param modelName
	 */
	private void applySamplingInjection(JsonObject requestJson, String modelName) {
		if (requestJson == null || modelName == null || modelName.isBlank()) {
			return;
		}
		ModelSamplingService.getInstance().handleOpenAI(requestJson);
	}
	
	/**
	 * 	
	 * @param modelElement
	 * @return
	 */
	private String readModelName(JsonElement modelElement) {
		if (modelElement == null || modelElement.isJsonNull() || !modelElement.isJsonPrimitive()) {
			return null;
		}
		try {
			return modelElement.getAsString();
		} catch (Exception e) {
			return null;
		}
	}
	
	/**
	 * 	
	 * @param element
	 * @return
	 */
	private Boolean readBooleanLenient(JsonElement element) {
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return null;
		}
		try {
			JsonPrimitive primitive = element.getAsJsonPrimitive();
			if (primitive.isBoolean()) {
				return primitive.getAsBoolean();
			}
			if (primitive.isString()) {
				return Boolean.parseBoolean(primitive.getAsString().trim());
			}
		} catch (Exception e) {
			return null;
		}
		return null;
	}
	
	/**
	 * 统一委托公共工具处理 thinking 兼容字段，避免与普通聊天链路分叉。
	 * @param requestJson
	 */
	private void applyThinkingInjection(JsonObject requestJson) {
		ParamTool.handleOpenAIChatThinking(requestJson);
	}
	
	/**
	 * 	
	 */
	public interface ModelResolvedCallback {
		void onModelResolved(String modelName) throws IOException;
	}
	
	/**
	 * 	
	 */
	public static class TransformResult {

		private final String modelName;
		private final boolean stream;

		public TransformResult(String modelName, boolean stream) {
			this.modelName = modelName;
			this.stream = stream;
		}

		public String getModelName() {
			return this.modelName;
		}

		public boolean isStream() {
			return this.stream;
		}
	}
	
	
	/**
	 * 	
	 */
	public static class StreamingRequestException extends IOException {

		private static final long serialVersionUID = 1L;

		private final int httpStatus;
		private final String param;

		public StreamingRequestException(int httpStatus, String message, String param) {
			super(message);
			this.httpStatus = httpStatus;
			this.param = param;
		}

		public int getHttpStatus() {
			return this.httpStatus;
		}

		public String getParam() {
			return this.param;
		}
	}
	
	/**
	 * 	
	 */
	private static class LimitedByteArrayOutputStream extends ByteArrayOutputStream {

		private final int limit;
		private final String fieldName;

		private LimitedByteArrayOutputStream(int limit, String fieldName) {
			this.limit = limit;
			this.fieldName = fieldName;
		}

		@Override
		public synchronized void write(int b) {
			this.ensureCapacity(1);
			super.write(b);
		}

		@Override
		public synchronized void write(byte[] b, int off, int len) {
			this.ensureCapacity(len);
			super.write(b, off, len);
		}

		private void ensureCapacity(int delta) {
			if (this.count + delta > this.limit) {
				throw new IllegalStateException("Top-level field too large: " + fieldName);
			}
		}
	}
}
