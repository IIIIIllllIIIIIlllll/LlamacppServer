package org.mark.llamacpp.server.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;




@Deprecated
public class LlamaCppService {
	
	private static final Logger logger = LoggerFactory.getLogger(LlamaCppService.class);
	private static final Gson gson = new Gson();
	
	private final Map<ChannelHandlerContext, HttpURLConnection> channelConnectionMap = new HashMap<>();
	
	private Executor worker = Executors.newSingleThreadExecutor();
	
	
	
	
	
	
	
	
	
	public void forwardOpenAIChatToCompletion(ChannelHandlerContext ctx, FullHttpRequest request) {
		try {
			if (request.method() != HttpMethod.POST) {
				this.sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED.code(), "Only POST method is supported");
				return;
			}
			
			String content = request.content().toString(CharsetUtil.UTF_8);
			if (content == null || content.trim().isEmpty()) {
				this.sendError(ctx, HttpResponseStatus.BAD_REQUEST.code(), "Request body is empty");
				return;
			}
			
			JsonObject chatReq = gson.fromJson(content, JsonObject.class);
			if (chatReq == null || !chatReq.has("messages")) {
				this.sendError(ctx, HttpResponseStatus.BAD_REQUEST.code(), "Missing required parameter: messages");
				return;
			}
			
			String modelName = chatReq.has("model") && chatReq.get("model").isJsonPrimitive()
				? chatReq.get("model").getAsString()
				: "llama.cpp";
			
			boolean isStream = false;
			if (chatReq.has("stream")) {
				try {
					isStream = chatReq.get("stream").getAsBoolean();
				} catch (Exception ignore) {}
			}
			
			String prompt = null;
			try {
				
				JsonObject applyBody = new JsonObject();
				System.err.println(chatReq);
				applyBody.add("messages", chatReq.get("messages").getAsJsonArray());
				JsonObject applyResp = this.callRemoteJson("http://20.20.20.30:8104/apply-template", applyBody);
				if (applyResp != null && applyResp.has("prompt")) {
					prompt = applyResp.get("prompt").getAsString();
				}
			} catch (Exception e) {
				logger.warn("调用 /apply-template 失败，将回退到简单拼接模板: {}", e.getMessage());
			}
			if (prompt == null) {
				prompt = buildNaivePrompt(chatReq.get("messages").getAsJsonArray());
			}
			
			JsonObject completionBody = new JsonObject();
			completionBody.addProperty("prompt", prompt);
			completionBody.addProperty("id_slot", 0);
			completionBody.addProperty("cache_prompt", true);
			
			if (chatReq.has("max_tokens")) {
				try { completionBody.addProperty("n_predict", chatReq.get("max_tokens").getAsInt()); } catch (Exception ignore) {}
			}
			if (chatReq.has("temperature")) {
				try { completionBody.addProperty("temperature", chatReq.get("temperature").getAsDouble()); } catch (Exception ignore) {}
			}
			if (chatReq.has("top_p")) {
				try { completionBody.addProperty("top_p", chatReq.get("top_p").getAsDouble()); } catch (Exception ignore) {}
			}
			if (chatReq.has("stop") && chatReq.get("stop").isJsonArray()) {
				try { completionBody.add("stop", chatReq.get("stop").getAsJsonArray()); } catch (Exception ignore) {}
			}
			if (chatReq.has("presence_penalty")) {
				try { completionBody.addProperty("presence_penalty", chatReq.get("presence_penalty").getAsDouble()); } catch (Exception ignore) {}
			}
			if (chatReq.has("frequency_penalty")) {
				try { completionBody.addProperty("frequency_penalty", chatReq.get("frequency_penalty").getAsDouble()); } catch (Exception ignore) {}
			}
			if (chatReq.has("logit_bias")) {
				try { completionBody.add("logit_bias", chatReq.get("logit_bias")); } catch (Exception ignore) {}
			}
			if (chatReq.has("seed")) {
				try { completionBody.addProperty("seed", chatReq.get("seed").getAsInt()); } catch (Exception ignore) {}
			}
			if (chatReq.has("stream")) {
				try { completionBody.addProperty("stream", isStream); } catch (Exception ignore) {}
			}
			
			this.forwardToRemoteCompletion(ctx, request, completionBody, isStream, modelName);
		} catch (Exception e) {
			logger.error("转发 OpenAI Chat 到 /completion 时发生错误", e);
			this.sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), e.getMessage());
		}
	}
	
	private JsonObject callRemoteJson(String urlStr, JsonObject body) throws IOException {
		URL url = URI.create(urlStr).toURL();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
		conn.setConnectTimeout(36000 * 1000);
		conn.setReadTimeout(36000 * 1000);
		conn.setDoOutput(true);
		byte[] input = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
		try (OutputStream os = conn.getOutputStream()) {
			os.write(input, 0, input.length);
		}
		int code = conn.getResponseCode();
		String responseBody;
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
				StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line.trim());
			}
			responseBody = sb.toString();
		}
		conn.disconnect();
		try {
			return gson.fromJson(responseBody, JsonObject.class);
		} catch (Exception e) {
			return null;
		}
	}
	
	private String buildNaivePrompt(JsonArray messages) {
		StringBuilder sb = new StringBuilder();
		for (JsonElement el : messages) {
			if (!el.isJsonObject()) continue;
			JsonObject msg = el.getAsJsonObject();
			String role = msg.has("role") ? msg.get("role").getAsString() : "user";
			String content = msg.has("content") ? msg.get("content").getAsString() : "";
			if ("system".equals(role)) {
				sb.append("System:\n").append(content).append("\n\n");
			} else if ("user".equals(role)) {
				sb.append("User:\n").append(content).append("\n\n");
			} else if ("assistant".equals(role)) {
				sb.append("Assistant:\n").append(content).append("\n\n");
			} else {
				sb.append(role).append(":\n").append(content).append("\n\n");
			}
		}
		sb.append("Assistant:\n");
		return sb.toString();
	}
	
	private void forwardToRemoteCompletion(ChannelHandlerContext ctx, FullHttpRequest originalRequest, JsonObject completionBody, boolean isStream, String modelName) {
		Map<String, String> headers = new HashMap<>();
		for (Map.Entry<String, String> entry : originalRequest.headers()) {
			headers.put(entry.getKey(), entry.getValue());
		}
		
		this.worker.execute(() -> {
			HttpURLConnection connection = null;
			try {
				String targetUrl = "http://20.20.20.30:8104/completion";
				URL url = URI.create(targetUrl).toURL();
				connection = (HttpURLConnection) url.openConnection();
				
				synchronized (this.channelConnectionMap) {
					this.channelConnectionMap.put(ctx, connection);
				}
				
				connection.setRequestMethod("POST");
				for (Map.Entry<String, String> entry : headers.entrySet()) {
					if (!entry.getKey().equalsIgnoreCase("Connection") &&
						!entry.getKey().equalsIgnoreCase("Content-Length") &&
						!entry.getKey().equalsIgnoreCase("Transfer-Encoding")) {
						connection.setRequestProperty(entry.getKey(), entry.getValue());
					}
				}
				connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
				connection.setConnectTimeout(36000 * 1000);
				connection.setReadTimeout(36000 * 1000);
				
				connection.setDoOutput(true);
				byte[] input = gson.toJson(completionBody).getBytes(StandardCharsets.UTF_8);
				try (OutputStream os = connection.getOutputStream()) {
					os.write(input, 0, input.length);
				}
				
				int responseCode = connection.getResponseCode();
				
				if (isStream) {
					this.handleStreamResponse(ctx, connection, responseCode, modelName);
				} else {
					this.handleNonStreamResponse(ctx, connection, responseCode, modelName);
				}
			} catch (Exception e) {
				logger.error("转发到远程 /completion 时发生错误", e);
				this.sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), e.getMessage());
			} finally {
				if (connection != null) {
					connection.disconnect();
				}
				synchronized (this.channelConnectionMap) {
					this.channelConnectionMap.remove(ctx);
				}
			}
		});
	}
	
	private void handleNonStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName) throws IOException {
		String responseBody;
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream(),
				StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line.trim());
			}
			responseBody = sb.toString();
		}
		
		if (!(responseCode >= 200 && responseCode < 300)) {
			byte[] rawBytes = responseBody.getBytes(StandardCharsets.UTF_8);
			FullHttpResponse rawResp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
			rawResp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
			rawResp.headers().set(HttpHeaderNames.CONTENT_LENGTH, rawBytes.length);
			rawResp.content().writeBytes(rawBytes);
			ctx.writeAndFlush(rawResp).addListener(f -> ctx.close());
			return;
		}
		
		JsonObject llama = null;
		try { llama = gson.fromJson(responseBody, JsonObject.class); } catch (Exception ignore) {}
		
		JsonObject openai = new JsonObject();
		openai.addProperty("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""));
		openai.addProperty("object", "chat.completion");
		openai.addProperty("created", System.currentTimeMillis() / 1000);
		openai.addProperty("model", modelName);
		
		JsonObject choice = new JsonObject();
		choice.addProperty("index", 0);
		
		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		String contentText = llama != null && llama.has("content") ? safeString(llama.get("content")) : responseBody;
		String[] split = splitThink(contentText == null ? "" : contentText);
		message.addProperty("content", split[0]);
		if (split[1] != null && !split[1].isEmpty()) {
			message.addProperty("reasoning_content", split[1]);
		}
		choice.add("message", message);
		
		String finish = null;
		if (llama != null && llama.has("stop_type")) {
			String stopType = safeString(llama.get("stop_type"));
			if ("limit".equalsIgnoreCase(stopType)) finish = "length";
			else if ("eos".equalsIgnoreCase(stopType) || "word".equalsIgnoreCase(stopType)) finish = "stop";
		}
		if (finish == null) finish = "stop";
		choice.addProperty("finish_reason", finish);
		
		com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
		choices.add(choice);
		openai.add("choices", choices);
		
		JsonObject usage = new JsonObject();
		int promptTokens = 0;
		int completionTokens = 0;
		if (llama != null && llama.has("timings") && llama.get("timings").isJsonObject()) {
			JsonObject timings = llama.get("timings").getAsJsonObject();
			if (timings.has("prompt_n")) {
				try { promptTokens = timings.get("prompt_n").getAsInt(); } catch (Exception ignore) {}
			}
		}
		if (llama != null && llama.has("tokens") && llama.get("tokens").isJsonArray()) {
			try { completionTokens = llama.get("tokens").getAsJsonArray().size(); } catch (Exception ignore) {}
		} else if (llama != null && llama.has("generation_settings") && llama.get("generation_settings").isJsonObject()) {
			JsonObject gs = llama.get("generation_settings").getAsJsonObject();
			if (gs.has("n_predict")) {
				try { completionTokens = gs.get("n_predict").getAsInt(); } catch (Exception ignore) {}
			}
		}
		usage.addProperty("prompt_tokens", promptTokens);
		usage.addProperty("completion_tokens", completionTokens);
		usage.addProperty("total_tokens", promptTokens + completionTokens);
		openai.add("usage", usage);
		
		byte[] outBytes = gson.toJson(openai).getBytes(StandardCharsets.UTF_8);
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, outBytes.length);
		response.content().writeBytes(outBytes);
		
		ctx.writeAndFlush(response).addListener(f -> ctx.close());
	}
	
	private void handleStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName) throws IOException {
		HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
		
		ctx.write(response);
		ctx.flush();
		
		String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "");
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream(),
				StandardCharsets.UTF_8))) {
			String line;
			boolean inThink = false;
			while ((line = br.readLine()) != null) {
				if (!ctx.channel().isActive()) {
					if (connection != null) connection.disconnect();
					break;
				}
				
				String payload = line;
				if (payload.startsWith("data: ")) {
					payload = payload.substring(6);
				}
				if ("[DONE]".equals(payload.trim())) {
					io.netty.buffer.ByteBuf doneBuf = ctx.alloc().buffer();
					doneBuf.writeBytes("data: [DONE]\r\n\r\n".getBytes(StandardCharsets.UTF_8));
					HttpContent doneContent = new DefaultHttpContent(doneBuf);
					ctx.writeAndFlush(doneContent);
					break;
				}
				JsonObject chunkIn = null;
				try { chunkIn = gson.fromJson(payload, JsonObject.class); } catch (Exception ignore) {}
				
				boolean isStop = false;
				String deltaText = null;
				if (chunkIn != null) {
					if (chunkIn.has("content")) {
						deltaText = safeString(chunkIn.get("content"));
					}
					if (chunkIn.has("stop")) {
						try { isStop = chunkIn.get("stop").getAsBoolean(); } catch (Exception ignore) {}
					}
				} else {
					deltaText = line;
				}
				
				if (deltaText != null && !deltaText.isEmpty()) {
					String buf = deltaText;
					while (buf != null && !buf.isEmpty()) {
						if (!inThink) {
							int idx = buf.indexOf("<think>");
							if (idx >= 0) {
								String pre = buf.substring(0, idx);
								if (!pre.isEmpty()) {
									JsonObject out = new JsonObject();
									out.addProperty("id", id);
									out.addProperty("object", "chat.completion.chunk");
									out.addProperty("created", System.currentTimeMillis() / 1000);
									out.addProperty("model", modelName);
									JsonObject c = new JsonObject();
									c.addProperty("index", 0);
									JsonObject delta = new JsonObject();
									delta.addProperty("content", pre);
									c.add("delta", delta);
									c.add("logprobs", null);
									c.add("finish_reason", null);
									com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
									arr.add(c);
									out.add("choices", arr);
									String outLine = "data: " + gson.toJson(out) + "\r\n\r\n";
									io.netty.buffer.ByteBuf contentBuf = ctx.alloc().buffer();
									contentBuf.writeBytes(outLine.getBytes(StandardCharsets.UTF_8));
									HttpContent httpContent = new DefaultHttpContent(contentBuf);
									ctx.writeAndFlush(httpContent);
								}
								buf = buf.substring(idx + 7);
								inThink = true;
								continue;
							} else {
								JsonObject out = new JsonObject();
								out.addProperty("id", id);
								out.addProperty("object", "chat.completion.chunk");
								out.addProperty("created", System.currentTimeMillis() / 1000);
								out.addProperty("model", modelName);
								JsonObject c = new JsonObject();
								c.addProperty("index", 0);
								JsonObject delta = new JsonObject();
								delta.addProperty("content", buf);
								c.add("delta", delta);
								c.add("logprobs", null);
								c.add("finish_reason", null);
								com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
								arr.add(c);
								out.add("choices", arr);
								String outLine = "data: " + gson.toJson(out) + "\r\n\r\n";
								io.netty.buffer.ByteBuf contentBuf = ctx.alloc().buffer();
								contentBuf.writeBytes(outLine.getBytes(StandardCharsets.UTF_8));
								HttpContent httpContent = new DefaultHttpContent(contentBuf);
								ctx.writeAndFlush(httpContent);
								buf = "";
								continue;
							}
						} else {
							int idx2 = buf.indexOf("</think>");
							if (idx2 >= 0) {
								String pre2 = buf.substring(0, idx2);
								if (!pre2.isEmpty()) {
                                    JsonObject out = new JsonObject();
                                    out.addProperty("id", id);
                                    out.addProperty("object", "chat.completion.chunk");
                                    out.addProperty("created", System.currentTimeMillis() / 1000);
                                    out.addProperty("model", modelName);
                                    JsonObject c = new JsonObject();
                                    c.addProperty("index", 0);
                                    JsonObject delta = new JsonObject();
                                    delta.addProperty("reasoning_content", pre2);
                                    c.add("delta", delta);
                                    c.add("logprobs", null);
                                    c.add("finish_reason", null);
                                    com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                                    arr.add(c);
                                    out.add("choices", arr);
                                    String outLine = "data: " + gson.toJson(out) + "\r\n\r\n";
                                    io.netty.buffer.ByteBuf contentBuf = ctx.alloc().buffer();
                                    contentBuf.writeBytes(outLine.getBytes(StandardCharsets.UTF_8));
                                    HttpContent httpContent = new DefaultHttpContent(contentBuf);
                                    ctx.writeAndFlush(httpContent);
								}
								buf = buf.substring(idx2 + 8);
								inThink = false;
								continue;
							} else {
								JsonObject out = new JsonObject();
								out.addProperty("id", id);
								out.addProperty("object", "chat.completion.chunk");
								out.addProperty("created", System.currentTimeMillis() / 1000);
								out.addProperty("model", modelName);
								JsonObject c = new JsonObject();
								c.addProperty("index", 0);
								JsonObject delta = new JsonObject();
								delta.addProperty("reasoning_content", buf);
								c.add("delta", delta);
								c.add("logprobs", null);
								c.add("finish_reason", null);
								com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
								arr.add(c);
								out.add("choices", arr);
								String outLine = "data: " + gson.toJson(out) + "\r\n\r\n";
								io.netty.buffer.ByteBuf contentBuf = ctx.alloc().buffer();
								contentBuf.writeBytes(outLine.getBytes(StandardCharsets.UTF_8));
								HttpContent httpContent = new DefaultHttpContent(contentBuf);
								ctx.writeAndFlush(httpContent);
								buf = "";
								continue;
							}
						}
					}
				}
				
				if (isStop) {
					JsonObject finalChunk = new JsonObject();
					finalChunk.addProperty("id", id);
					finalChunk.addProperty("object", "chat.completion.chunk");
					finalChunk.addProperty("created", System.currentTimeMillis() / 1000);
					finalChunk.addProperty("model", modelName);
					JsonObject fc = new JsonObject();
					fc.addProperty("index", 0);
					JsonObject emptyDelta = new JsonObject();
					fc.add("delta", emptyDelta);
					fc.add("logprobs", null);
					fc.addProperty("finish_reason", "stop");
					com.google.gson.JsonArray farr = new com.google.gson.JsonArray();
					farr.add(fc);
					finalChunk.add("choices", farr);
					String finalLine = "data: " + gson.toJson(finalChunk) + "\r\n\r\n";
					io.netty.buffer.ByteBuf finalBuf = ctx.alloc().buffer();
					finalBuf.writeBytes(finalLine.getBytes(StandardCharsets.UTF_8));
					HttpContent finalContent = new DefaultHttpContent(finalBuf);
					ctx.writeAndFlush(finalContent);
					
					io.netty.buffer.ByteBuf doneBuf = ctx.alloc().buffer();
					doneBuf.writeBytes("data: [DONE]\r\n\r\n".getBytes(StandardCharsets.UTF_8));
					HttpContent doneContent = new DefaultHttpContent(doneBuf);
					ctx.writeAndFlush(doneContent);
					break;
				}
			}
		}
		
		LastHttpContent last = LastHttpContent.EMPTY_LAST_CONTENT;
		ctx.writeAndFlush(last).addListener(f -> ctx.close());
	}
	
	private String safeString(JsonElement el) {
		if (el == null) return null;
		if (el.isJsonPrimitive()) {
			try { return el.getAsString(); } catch (Exception ignore) {}
		}
		return el.toString();
	}
	
	private String[] splitThink(String text) {
		if (text == null) return new String[] { "", null };
		Pattern p = Pattern.compile("(?is)<think>(.*?)</think>");
		Matcher m = p.matcher(text);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String t = m.group(1);
			if (t != null) sb.append(t);
		}
		String answer = text.replaceAll("(?is)<think>(.*?)</think>", "");
		return new String[] { answer, sb.length() == 0 ? null : sb.toString() };
	}
	
	private void sendError(ChannelHandlerContext ctx, int httpStatus, String message) {
		JsonObject error = new JsonObject();
		error.addProperty("error", message == null ? "Unknown error" : message);
		byte[] content = gson.toJson(error).getBytes(StandardCharsets.UTF_8);
		FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(httpStatus));
		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
		response.content().writeBytes(content);
		ctx.writeAndFlush(response).addListener(f -> ctx.close());
	}
	
	
	/**
	 * 	断开连接时的操作。
	 * @param ctx
	 */
	public void channelInactive(ChannelHandlerContext ctx) {
		// 关闭正在进行的链接
		synchronized (this.channelConnectionMap) {
			HttpURLConnection conn = this.channelConnectionMap.remove(ctx);
			if (conn != null) {
				try {
					conn.disconnect();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}
	
}
