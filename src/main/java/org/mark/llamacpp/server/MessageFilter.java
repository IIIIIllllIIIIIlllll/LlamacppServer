package org.mark.llamacpp.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.buffer.ByteBuf;

public class MessageFilter {

	private static final Logger LOGGER = LoggerFactory.getLogger(MessageFilter.class);
	
	private static final Gson gson = new Gson();
	
	
	/**
	 * 	[ {"user": "", "content": ""}, {...} ]
	 * @param ctx
	 * @param modelId
	 * @param messages
	 * @return
	 */
	public static String filter(ChannelHandlerContext ctx, String modelId, JsonObject requestJson) {
		// 转成数组
		JsonElement messages = requestJson.get("messages");
		JsonArray originalArray = messages.getAsJsonArray();
		// 创建新数组用于存放结果
		JsonArray filteredArray = new JsonArray();

		boolean isStream = false;
		if (requestJson != null && requestJson.has("stream") && !requestJson.get("stream").isJsonNull()) {
			try {
				isStream = requestJson.get("stream").getAsBoolean();
			} catch (Exception ignore) {
			}
		}

		int size = originalArray.size();
		int n = 0;
		// 遍历原始数组
		for (JsonElement element : originalArray) {
			if (element.isJsonObject()) {
				JsonObject jsonObject = element.getAsJsonObject();
				// 安全地获取 "content" 字段
				if (jsonObject.has("content") && !jsonObject.get("content").isJsonNull()) {
					//
					String content = jsonObject.get("content").getAsString();

					// 判断是否需要保留（不包含要移除的关键字）
					boolean shouldRemove = content.startsWith(LlamaServer.SLOTS_SAVE_KEYWORD)
							|| content.startsWith(LlamaServer.SLOTS_LOAD_KEYWORD);
					// 如果不应该被移除，就添加到新数组
					if (!shouldRemove) {
						filteredArray.add(jsonObject);
					}

					if (size - 1 == n) {
						// 出现了，是特殊处理！
						// TODO
						if(content.startsWith(LlamaServer.SLOTS_SAVE_KEYWORD)) {
							
							save(modelId, ctx, isStream);
							return null;
						}
						if(content.startsWith(LlamaServer.SLOTS_LOAD_KEYWORD)) {
							load(modelId, ctx, isStream);
							return null;
						}
					}
				} else {
					// 如果消息对象没有 content 字段，我们选择保留它
					filteredArray.add(jsonObject);
				}
			} else {
				// 如果数组元素不是 JsonObject，也保留它
				filteredArray.add(element);
			}
			n += 1;
		}
		// 将过滤后的新数组转换回 JSON 字符串并返回
		requestJson.add("messages", filteredArray);
		return gson.toJson(requestJson);
	}
	
	
	
	
	private static void save(String modelId, ChannelHandlerContext ctx, boolean isStream) {
		try {
			Map<String, Object> result = callSlotsEndpoint(modelId, 0, "save");
			String content = "/SLOTSAVE" + gson.toJson(result);
			sendOpenAIResponse(ctx, modelId, content, isStream);
		} catch (Exception e) {
			LOGGER.error("保存slot缓存时发生错误", e);
			Map<String, Object> err = new HashMap<>();
			err.put("success", false);
			err.put("modelId", modelId);
			err.put("error", e.getMessage());
			String content = "/SLOTSAVE" + gson.toJson(err);
			sendOpenAIResponse(ctx, modelId, content, isStream);
		}
	}
	
	
	
	
	
	private static void load(String modelId, ChannelHandlerContext ctx, boolean isStream) {
		try {
			Map<String, Object> result = callSlotsEndpoint(modelId, 0, "restore");
			String content = "/SLOTLOAD" + gson.toJson(result);
			sendOpenAIResponse(ctx, modelId, content, isStream);
		} catch (Exception e) {
			LOGGER.error("加载slot缓存时发生错误", e);
			Map<String, Object> err = new HashMap<>();
			err.put("success", false);
			err.put("modelId", modelId);
			err.put("error", e.getMessage());
			String content = "/SLOTLOAD" + gson.toJson(err);
			sendOpenAIResponse(ctx, modelId, content, isStream);
		}
	}

	private static Map<String, Object> callSlotsEndpoint(String modelId, int slotId, String action) {
		Map<String, Object> data = new HashMap<>();
		data.put("modelId", modelId);
		data.put("slotId", slotId);
		data.put("action", action);

		String filename = (modelId == null ? "unknown" : modelId) + "_" + slotId + ".bin";
		data.put("filename", filename);

		try {
			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (modelId == null || modelId.trim().isEmpty()) {
				data.put("success", false);
				data.put("error", "Missing modelId");
				return data;
			}
			if (!manager.getLoadedProcesses().containsKey(modelId)) {
				data.put("success", false);
				data.put("error", "Model not loaded: " + modelId);
				return data;
			}
			Integer port = manager.getModelPort(modelId);
			if (port == null) {
				data.put("success", false);
				data.put("error", "Model port not found: " + modelId);
				return data;
			}

			String endpoint = String.format("/slots/%d?action=%s", slotId, action);
			String targetUrl = String.format("http://localhost:%d%s", port.intValue(), endpoint);
			data.put("targetUrl", targetUrl);

			URL url = URI.create(targetUrl).toURL();
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			connection.setConnectTimeout(36000 * 1000);
			connection.setReadTimeout(36000 * 1000);

			JsonObject body = new JsonObject();
			body.addProperty("filename", filename);
			byte[] input = body.toString().getBytes(StandardCharsets.UTF_8);
			try (OutputStream os = connection.getOutputStream()) {
				os.write(input, 0, input.length);
			}

			int responseCode = connection.getResponseCode();
			data.put("status", responseCode);

			String responseBody = "";
			if (responseCode >= 200 && responseCode < 300) {
				try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				}
				Object parsed = null;
				try {
					parsed = gson.fromJson(responseBody, Object.class);
				} catch (Exception ignore) {
				}
				data.put("success", true);
				data.put("result", parsed != null ? parsed : responseBody);
			} else {
				try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
					StringBuilder sb = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						sb.append(line);
					}
					responseBody = sb.toString();
				} catch (Exception ignore) {
				}
				data.put("success", false);
				data.put("error", responseBody);
			}
			connection.disconnect();
			return data;
		} catch (Exception e) {
			data.put("success", false);
			data.put("error", e.getMessage());
			return data;
		}
	}

	private static void sendOpenAIResponse(ChannelHandlerContext ctx, String modelId, String content, boolean isStream) {
		try {
			if (isStream) {
				sendOpenAIStreamResponse(ctx, modelId, content);
			} else {
				sendOpenAINonStreamResponse(ctx, modelId, content);
			}
		} catch (Exception e) {
			LOGGER.error("发送OpenAI响应时发生错误", e);
			try {
				sendOpenAINonStreamResponse(ctx, modelId, content);
			} catch (Exception ignore) {
			}
		}
	}

	private static void sendOpenAINonStreamResponse(ChannelHandlerContext ctx, String modelId, String content) {
		JsonObject responseJson = new JsonObject();
		responseJson.addProperty("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""));
		responseJson.addProperty("object", "chat.completion");
		responseJson.addProperty("created", System.currentTimeMillis() / 1000);
		responseJson.addProperty("model", modelId);

		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		message.addProperty("content", content);

		JsonObject choice = new JsonObject();
		choice.addProperty("index", 0);
		choice.add("message", message);
		choice.addProperty("finish_reason", "stop");

		JsonArray choices = new JsonArray();
		choices.add(choice);
		responseJson.add("choices", choices);

		JsonObject usage = new JsonObject();
		usage.addProperty("prompt_tokens", 0);
		usage.addProperty("completion_tokens", 0);
		usage.addProperty("total_tokens", 0);
		responseJson.add("usage", usage);

		byte[] outBytes = gson.toJson(responseJson).getBytes(StandardCharsets.UTF_8);

		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, outBytes.length);
		response.content().writeBytes(outBytes);

		ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

	private static void sendOpenAIStreamResponse(ChannelHandlerContext ctx, String modelId, String content) {
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		ctx.write(response);
		ctx.flush();

		String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
		long created = System.currentTimeMillis() / 1000;

		JsonObject chunk1 = new JsonObject();
		chunk1.addProperty("id", id);
		chunk1.addProperty("object", "chat.completion.chunk");
		chunk1.addProperty("created", created);
		chunk1.addProperty("model", modelId);
		JsonArray choices1 = new JsonArray();
		JsonObject c1 = new JsonObject();
		c1.addProperty("index", 0);
		JsonObject delta1 = new JsonObject();
		delta1.addProperty("role", "assistant");
		delta1.addProperty("content", content);
		c1.add("delta", delta1);
		c1.add("finish_reason", null);
		choices1.add(c1);
		chunk1.add("choices", choices1);

		writeSseData(ctx, gson.toJson(chunk1));

		JsonObject chunk2 = new JsonObject();
		chunk2.addProperty("id", id);
		chunk2.addProperty("object", "chat.completion.chunk");
		chunk2.addProperty("created", created);
		chunk2.addProperty("model", modelId);
		JsonArray choices2 = new JsonArray();
		JsonObject c2 = new JsonObject();
		c2.addProperty("index", 0);
		c2.add("delta", new JsonObject());
		c2.addProperty("finish_reason", "stop");
		choices2.add(c2);
		chunk2.add("choices", choices2);

		writeSseData(ctx, gson.toJson(chunk2));

		writeSseData(ctx, "[DONE]");

		LastHttpContent lastContent = LastHttpContent.EMPTY_LAST_CONTENT;
		ctx.writeAndFlush(lastContent).addListener(new ChannelFutureListener() {
			@Override
			public void operationComplete(ChannelFuture future) {
				ctx.close();
			}
		});
	}

	private static void writeSseData(ChannelHandlerContext ctx, String payload) {
		ByteBuf buf = ctx.alloc().buffer();
		buf.writeBytes(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
		HttpContent httpContent = new DefaultHttpContent(buf);
		ctx.writeAndFlush(httpContent);
	}
}
