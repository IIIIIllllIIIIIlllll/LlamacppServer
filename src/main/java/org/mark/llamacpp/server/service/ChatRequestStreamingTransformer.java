package org.mark.llamacpp.server.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

public class ChatRequestStreamingTransformer {

	private static final Logger logger = LoggerFactory.getLogger(ChatRequestStreamingTransformer.class);

	private final int maxFieldBytes;
	private final int maxBufferedBytes;

	public ChatRequestStreamingTransformer(int maxFieldBytes, int maxBufferedBytes) {
		this.maxFieldBytes = maxFieldBytes;
		this.maxBufferedBytes = maxBufferedBytes;
	}

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
		int firstToken = nextNonWhitespace(stream);
		if (firstToken != '{') {
			throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
		}

		output.write('{');
		boolean firstParsedField = true;
		boolean firstOutputField = true;

		while (true) {
			int token = nextNonWhitespace(stream);
			if (token == '}') {
				break;
			}
			if (!firstParsedField) {
				if (token != ',') {
					throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
				}
				token = nextNonWhitespace(stream);
			}
			firstParsedField = false;
			if (token != '"') {
				throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
			}

			String fieldName = readJsonString(stream);
			int colon = nextNonWhitespace(stream);
			if (colon != ':') {
				throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
			}

			int valueStart = nextNonWhitespace(stream);
			if (valueStart < 0) {
				throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
			}

			// messages 是大字段热点，保持原样流式拷贝，不走 JsonObject 解析。
			if ("messages".equals(fieldName)) {
				if (!firstOutputField) {
					output.write(',');
				}
				output.write(MESSAGES_FIELD_PREFIX);
				copyValue(stream, valueStart, output);
				output.flush();
				firstOutputField = false;
				continue;
			}

			byte[] rawBytes = readCurrentValue(stream, valueStart, fieldName);
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
				String candidate = readModelName(element);
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

		applyThinkingInjection(bufferedFields);

		modelName = readModelName(bufferedFields.get("model"));
		if (modelName == null) {
			throw new StreamingRequestException(400, "Missing required parameter: model", "model");
		}
		if (modelName.isBlank()) {
			throw new StreamingRequestException(400, "Invalid parameter: model", "model");
		}

		// 这里对缓冲下来的顶层小字段做采样覆盖，语义对齐旧链路的“顶层参数注入”。
		applySamplingInjection(bufferedFields, modelName);

		Boolean streamValue = readBooleanLenient(bufferedFields.get("stream"));
		if (streamValue != null) {
			isStream = streamValue.booleanValue();
		}

		for (Map.Entry<String, JsonElement> entry : bufferedFields.entrySet()) {
			if (!firstOutputField) {
				output.write(',');
			}
			writeBufferedField(output, entry.getKey(), entry.getValue());
			firstOutputField = false;
		}
		output.write('}');
		output.flush();
		return new TransformResult(modelName, isStream);
	}

	private static final byte[] MESSAGES_FIELD_PREFIX = "\"messages\":".getBytes(StandardCharsets.UTF_8);

	private byte[] readCurrentValue(PushbackInputStream input, int firstByte, String fieldName) throws IOException {
		LimitedByteArrayOutputStream out = new LimitedByteArrayOutputStream(maxFieldBytes, fieldName);
		try {
			copyValue(input, firstByte, out);
		} catch (IllegalStateException e) {
			throw new StreamingRequestException(400, e.getMessage(), fieldName);
		}
		return out.toByteArray();
	}

	private void copyValue(PushbackInputStream input, int firstByte, OutputStream output) throws IOException {
		if (firstByte == '"') {
			copyString(input, output);
			return;
		}
		if (firstByte == '{' || firstByte == '[') {
			copyComposite(input, firstByte, output);
			return;
		}
		if (isPrimitiveStart(firstByte)) {
			copyPrimitive(input, firstByte, output);
			return;
		}
		throw new StreamingRequestException(400, "Request body is not a valid JSON object", null);
	}

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

	private void applySamplingInjection(JsonObject requestJson, String modelName) {
		if (requestJson == null || modelName == null || modelName.isBlank()) {
			logger.info("跳过采样覆盖：请求或模型名为空");
			return;
		}
		String beforeSampling = buildSamplingLogSnapshot(requestJson);
		JsonObject sampling = ModelSamplingService.getInstance().getOpenAISampling(modelName);
		if (sampling == null || sampling.size() == 0) {
			logger.info("未命中模型采样配置，model={}, requestSampling={}", modelName, beforeSampling);
			return;
		}
		logger.info("命中模型采样配置，model={}, requestSampling={}, configSampling={}",
				modelName,
				beforeSampling,
				buildSamplingLogSnapshot(sampling));
		for (Map.Entry<String, JsonElement> item : sampling.entrySet()) {
			String key = item.getKey();
			JsonElement value = item.getValue();
			if (key == null || value == null || value.isJsonNull()) {
				continue;
			}
			requestJson.add(key, value.deepCopy());
			logger.info("聊天流式请求采样覆盖，model={}, field={}, value={}", modelName, key, JsonUtil.toJson(value));
		}
		logger.info("聊天流式请求采样覆盖完成，model={}, finalSampling={}", modelName, buildSamplingLogSnapshot(requestJson));
	}

	private String buildSamplingLogSnapshot(JsonObject jsonObject) {
		if (jsonObject == null) {
			return "{}";
		}
		JsonObject snapshot = new JsonObject();
		copySamplingField(snapshot, jsonObject, "temperature");
		copySamplingField(snapshot, jsonObject, "top_p");
		copySamplingField(snapshot, jsonObject, "min_p");
		copySamplingField(snapshot, jsonObject, "repeat_penalty");
		copySamplingField(snapshot, jsonObject, "top_k");
		copySamplingField(snapshot, jsonObject, "presence_penalty");
		copySamplingField(snapshot, jsonObject, "frequency_penalty");
		return JsonUtil.toJson(snapshot);
	}

	private void copySamplingField(JsonObject target, JsonObject source, String fieldName) {
		if (target == null || source == null || fieldName == null || !source.has(fieldName)) {
			return;
		}
		JsonElement value = source.get(fieldName);
		if (value == null || value.isJsonNull()) {
			return;
		}
		target.add(fieldName, value.deepCopy());
	}

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

	private void applyThinkingInjection(JsonObject requestJson) {
		if (requestJson == null) {
			return;
		}

		boolean needInjection = false;
		boolean enableThinking = true;
		JsonElement enableThinkingElement = requestJson.get("enable_thinking");
		Boolean enableThinkingValue = readBooleanLenient(enableThinkingElement);
		if (enableThinkingValue != null) {
			needInjection = true;
			enableThinking = enableThinkingValue.booleanValue();
		}

		if (!needInjection) {
			JsonElement thinkingElement = requestJson.get("thinking");
			if (thinkingElement != null && thinkingElement.isJsonObject()) {
				JsonObject thinkingObject = thinkingElement.getAsJsonObject();
				JsonElement typeElement = thinkingObject.get("type");
				if (typeElement != null && typeElement.isJsonPrimitive()) {
					try {
						String typeValue = typeElement.getAsString();
						if (typeValue != null && "disabled".equals(typeValue.trim().toLowerCase())) {
							needInjection = true;
							enableThinking = false;
						}
					} catch (Exception e) {
					}
				}
			}
		}

		if (!needInjection) {
			return;
		}

		JsonObject chatTemplateKwargs = parseChatTemplateKwargs(requestJson.get("chat_template_kwargs"));
		if (chatTemplateKwargs == null) {
			chatTemplateKwargs = new JsonObject();
		}
		chatTemplateKwargs.addProperty("enable_thinking", enableThinking);
		requestJson.add("chat_template_kwargs", chatTemplateKwargs);
	}

	private JsonObject parseChatTemplateKwargs(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		try {
			if (element.isJsonObject()) {
				return element.getAsJsonObject().deepCopy();
			}
			if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
				return JsonUtil.tryParseObject(element.getAsString());
			}
		} catch (Exception e) {
			return null;
		}
		return null;
	}

	public interface ModelResolvedCallback {
		void onModelResolved(String modelName) throws IOException;
	}

	public static class TransformResult {

		private final String modelName;
		private final boolean stream;

		public TransformResult(String modelName, boolean stream) {
			this.modelName = modelName;
			this.stream = stream;
		}

		public String getModelName() {
			return modelName;
		}

		public boolean isStream() {
			return stream;
		}
	}

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
			return httpStatus;
		}

		public String getParam() {
			return param;
		}
	}

	private static class LimitedByteArrayOutputStream extends ByteArrayOutputStream {

		private final int limit;
		private final String fieldName;

		private LimitedByteArrayOutputStream(int limit, String fieldName) {
			this.limit = limit;
			this.fieldName = fieldName;
		}

		@Override
		public synchronized void write(int b) {
			ensureCapacity(1);
			super.write(b);
		}

		@Override
		public synchronized void write(byte[] b, int off, int len) {
			ensureCapacity(len);
			super.write(b, off, len);
		}

		private void ensureCapacity(int delta) {
			if (count + delta > limit) {
				throw new IllegalStateException("Top-level field too large: " + fieldName);
			}
		}
	}
}
