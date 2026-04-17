package org.mark.llamacpp.server.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.mark.llamacpp.server.LlamaCppProcess;

/**
 * 	Anthropic API
 * 	实际上基本没有用过
 */
public class AnthropicService {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicService.class);
    private static final Gson gson = new Gson();
    private static final String ANTHROPIC_API_KEY = "123456";
	/**
	 * 	线程池。
	 */
	private static final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    
	/**
	 * 	存储当前通道正在处理的模型链接，用于在连接关闭时停止对应的模型进程
	 */
	private final Map<ChannelHandlerContext, HttpURLConnection> channelConnectionMap = new HashMap<>();

	public AnthropicService() {
		
	}
	
	
	/**
	 * 	判断API KEY，true表明通过。做个样子
	 * @param request
	 * @return
	 */
	private boolean checkApiKey(FullHttpRequest request) {
		String apiKey = request.headers().get("x-api-key");
		if (apiKey == null || !ANTHROPIC_API_KEY.equals(apiKey)) {
			// return false;
		}
		return true;
	}
	
    /**
     * Handles GET /v1/models
     */
    public void handleModelsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (request.method() != HttpMethod.GET) {
            this.sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only GET method is supported");
            return;
        }
        
        if (!this.checkApiKey(request)) {
            this.sendError(ctx, HttpResponseStatus.UNAUTHORIZED, "invalid api key");
            return;
        }
        
        LlamaServerManager manager = LlamaServerManager.getInstance();
        JsonObject response = new JsonObject();
        JsonArray data = new JsonArray();
        
        Map<String, LlamaCppProcess> processes = manager.getLoadedProcesses();
        for (String modelId : processes.keySet()) {
            JsonObject model = new JsonObject();
            model.addProperty("type", "model");
            model.addProperty("id", modelId);
            model.addProperty("display_name", modelId);
            model.addProperty("created_at", System.currentTimeMillis() / 1000);
            data.add(model);
        }
        
        response.add("data", data);
        response.addProperty("has_more", false);
        if (data.size() > 0) {
            response.addProperty("first_id", data.get(0).getAsJsonObject().get("id").getAsString());
            response.addProperty("last_id", data.get(data.size()-1).getAsJsonObject().get("id").getAsString());
        } else {
            response.add("first_id", null);
            response.add("last_id", null);
        }
        
        sendJsonResponse(ctx, response, HttpResponseStatus.OK);
    }

    /**
     * 	Handles POST /v1/complete (Legacy Text Completions)
     * @param ctx
     * @param request
     */
    public void handleCompleteRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (request.method() != HttpMethod.POST) {
            this.sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST method is supported");
            return;
        }
        
        if (!this.checkApiKey(request)) {
            this.sendError(ctx, HttpResponseStatus.UNAUTHORIZED, "invalid api key");
            return;
        }

        String content = request.content().toString(CharsetUtil.UTF_8);
        if (content == null || content.trim().isEmpty()) {
            this.sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body is empty");
            return;
        }

        JsonObject anthropicReq;
        try {
            anthropicReq = gson.fromJson(content, JsonObject.class);
        } catch (Exception e) {
            this.sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Invalid JSON body");
            return;
        }

        String modelName;
        LlamaServerManager manager = LlamaServerManager.getInstance();
        if (anthropicReq.has("model")) {
            modelName = anthropicReq.get("model").getAsString();
        } else {
            modelName = manager.getFirstModelName();
            if (modelName == null) {
                this.sendError(ctx, HttpResponseStatus.NOT_FOUND, "No models loaded");
                return;
            }
        }
        
        if (!manager.getLoadedProcesses().containsKey(modelName)) {
            if (manager.getLoadedProcesses().size() == 1) {
                modelName = manager.getFirstModelName();
            } else {
                this.sendError(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
                return;
            }
        }
        
        Integer port = manager.getModelPort(modelName);
        if (port == null) {
            this.sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Model port not found for " + modelName);
            return;
        }

        boolean isStream = false;
        if (anthropicReq.has("stream") && anthropicReq.get("stream").isJsonPrimitive()) {
            isStream = anthropicReq.get("stream").getAsBoolean();
        }
        // 开始转发
        this.forwardRequestToLlamaCpp(ctx, request, content, port, "/v1/complete", isStream);
    }
    
    /**
     * 	对应：v1/messages
     * @param ctx
     * @param request
     */
    public void handleMessagesRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (request.method() != HttpMethod.POST) {
        	this.sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST method is supported");
            return;
        }
        
        if (!this.checkApiKey(request)) {
        	this.sendError(ctx, HttpResponseStatus.UNAUTHORIZED, "invalid api key");
            return;
        }

        String content = request.content().toString(CharsetUtil.UTF_8);
        JsonObject anthropicReq;
        try {
            anthropicReq = gson.fromJson(content, JsonObject.class);
        } catch (Exception e) {
        	this.sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Invalid JSON body");
            return;
        }
        JsonObject oaiReq = this.convertAnthropicToOai(anthropicReq);
        // 处理一下think
        ParamTool.handleThinking(oaiReq);
        //
        ChatTemplateKwargsService.getInstance().handleOpenAI(oaiReq);
        // 处理采样覆盖
        ModelSamplingService.getInstance().handleOpenAI(oaiReq);
        
        String modelName;
        LlamaServerManager manager = LlamaServerManager.getInstance();
        if (oaiReq.has("model")) {
            modelName = oaiReq.get("model").getAsString();
        } else {
            modelName = manager.getFirstModelName();
            if (modelName == null) {
                this.sendError(ctx, HttpResponseStatus.NOT_FOUND, "No models loaded");
                return;
            }
        }

        if (!manager.getLoadedProcesses().containsKey(modelName)) {
            if (manager.getLoadedProcesses().size() == 1) {
                modelName = manager.getFirstModelName();
            } else {
                this.sendError(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
                return;
            }
        }

        Integer port = manager.getModelPort(modelName);
        if (port == null) {
            this.sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Model port not found for " + modelName);
            return;
        }

        boolean isStream = false;
        if (oaiReq.has("stream") && oaiReq.get("stream").isJsonPrimitive()) {
            try {
                isStream = oaiReq.get("stream").getAsBoolean();
            } catch (Exception ignore) {
            }
        }

        this.forwardMessagesToChatCompletions(ctx, request, JsonUtil.toJson(oaiReq), port, isStream, modelName);
    }
    
    
    /**
     * 	对应 v1/messages/count_tokens
     * @param ctx
     * @param request
     */
    public void handleMessagesCountTokensRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (request.method() != HttpMethod.POST) {
        	this.sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST method is supported");
            return;
        }

        if (!this.checkApiKey(request)) {
        	this.sendError(ctx, HttpResponseStatus.UNAUTHORIZED, "invalid api key");
            return;
        }

        String content = request.content().toString(CharsetUtil.UTF_8);
        JsonObject anthropicReq;
        try {
            anthropicReq = gson.fromJson(content, JsonObject.class);
        } catch (Exception e) {
        	this.sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Invalid JSON body");
            return;
        }

        String modelName;
        LlamaServerManager manager = LlamaServerManager.getInstance();

        if (anthropicReq.has("model")) {
            modelName = anthropicReq.get("model").getAsString();
        } else {
            modelName = manager.getFirstModelName();
            if (modelName == null) {
            	this.sendError(ctx, HttpResponseStatus.NOT_FOUND, "No models loaded");
                return;
            }
        }

        if (!manager.getLoadedProcesses().containsKey(modelName)) {
            if (manager.getLoadedProcesses().size() == 1) {
                modelName = manager.getFirstModelName();
            } else {
            	this.sendError(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
                return;
            }
        }

        Integer port = manager.getModelPort(modelName);
        if (port == null) {
        	this.sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Model port not found for " + modelName);
            return;
        }

        forwardRequestToLlamaCpp(ctx, request, content, port, "/v1/messages/count_tokens", false);
    }
    
    
    /**
     * 	转发操作。
     * @param ctx
     * @param request
     * @param requestBody
     * @param port
     * @param endpoint
     * @param isStream
     */
    private void forwardRequestToLlamaCpp(ChannelHandlerContext ctx, FullHttpRequest request, String requestBody, int port, String endpoint, boolean isStream) {
        HttpMethod method = request.method();
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, String> entry : request.headers()) {
            headers.put(entry.getKey(), entry.getValue());
        }

        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String targetUrl = String.format("http://localhost:%d%s", port, endpoint);
                URL url = URI.create(targetUrl).toURL();
                connection = (HttpURLConnection) url.openConnection();

                synchronized (this.channelConnectionMap) {
                    this.channelConnectionMap.put(ctx, connection);
                }

                connection.setRequestMethod(method.name());

                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (!entry.getKey().equalsIgnoreCase("Connection") &&
                        !entry.getKey().equalsIgnoreCase("Content-Length") &&
                        !entry.getKey().equalsIgnoreCase("Transfer-Encoding")) {
                        connection.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }

                connection.setConnectTimeout(36000 * 1000);
                connection.setReadTimeout(36000 * 1000);

                if (method == HttpMethod.POST && requestBody != null && !requestBody.isEmpty()) {
                    connection.setDoOutput(true);
                    try (OutputStream os = connection.getOutputStream()) {
                        byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                        os.write(input, 0, input.length);
                    }
                }
                
                long t = System.currentTimeMillis();
                int responseCode = connection.getResponseCode();

                if (isStream) {
                	logger.info("llama.cpp进程响应码: {}，，等待时间：{}", responseCode, System.currentTimeMillis() - t);
                	this.handleStreamResponse(ctx, connection, responseCode);
                } else {
                	this.handleNonStreamResponse(ctx, connection, responseCode);
                }
            } catch (Exception e) {
                logger.info("Error forwarding Anthropic request to llama.cpp", e);
                this.sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
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

    private void forwardMessagesToChatCompletions(ChannelHandlerContext ctx, FullHttpRequest request, String requestBody, int port, boolean isStream, String modelName) {
        HttpMethod method = request.method();
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, String> entry : request.headers()) {
            headers.put(entry.getKey(), entry.getValue());
        }

        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String targetUrl = String.format("http://localhost:%d%s", port, "/v1/chat/completions");
                URL url = URI.create(targetUrl).toURL();
                connection = (HttpURLConnection) url.openConnection();

                synchronized (this.channelConnectionMap) {
                    this.channelConnectionMap.put(ctx, connection);
                }

                connection.setRequestMethod(method.name());
                connection.setRequestProperty(HttpHeaderNames.CONTENT_TYPE.toString(), "application/json");

                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (!entry.getKey().equalsIgnoreCase("Connection") &&
                        !entry.getKey().equalsIgnoreCase("Content-Length") &&
                        !entry.getKey().equalsIgnoreCase("Transfer-Encoding")) {
                        connection.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }

                connection.setConnectTimeout(36000 * 1000);
                connection.setReadTimeout(36000 * 1000);

                if (method == HttpMethod.POST && requestBody != null && !requestBody.isEmpty()) {
                    connection.setDoOutput(true);
                    try (OutputStream os = connection.getOutputStream()) {
                        byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                        os.write(input, 0, input.length);
                    }
                }

                int responseCode = connection.getResponseCode();
                if (isStream) {
                    this.handleAnthropicStreamFromOai(ctx, connection, responseCode, modelName);
                } else {
                    this.handleAnthropicNonStreamFromOai(ctx, connection, responseCode, modelName);
                }
            } catch (Exception e) {
                logger.info("Error forwarding Anthropic->OpenAI request to llama.cpp", e);
                this.sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
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

    private void handleNonStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode) throws IOException {
        String responseBody;
        if (responseCode >= 200 && responseCode < 300) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                responseBody = response.toString();
            }
        } else {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                responseBody = response.toString();
            }
        }

        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(responseCode)
        );

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseBody.getBytes(StandardCharsets.UTF_8).length);

        response.content().writeBytes(responseBody.getBytes(StandardCharsets.UTF_8));

        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                ctx.close();
            }
        });
    }

    private void handleAnthropicNonStreamFromOai(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName) throws IOException {
        String responseBody;
        if (responseCode >= 200 && responseCode < 300) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                responseBody = response.toString();
            }
        } else {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                responseBody = response.toString();
            }
        }

        if (responseCode >= 200 && responseCode < 300) {
            JsonElement root = JsonParser.parseString(responseBody);
            JsonObject oaiRes = root != null && root.isJsonObject() ? root.getAsJsonObject() : null;
            if (oaiRes == null) {
                this.sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Invalid OpenAI response");
                return;
            }
            JsonObject anthropicRes = convertOaiResponseToAnthropic(oaiRes);
            responseBody = JsonUtil.toJson(anthropicRes);
        }

        FullHttpResponse response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(responseCode)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseBytes.length);
        response.content().writeBytes(responseBytes);

        ctx.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                ctx.close();
            }
        });
        LlamaRecordService.getInstance().handleStream(modelName, responseBody);
    }

    private void handleStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode) throws IOException {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");

        ctx.write(response);
        ctx.flush();

        try (BufferedReader br = new BufferedReader(
            new InputStreamReader(
                responseCode >= 200 && responseCode < 300 ?
                    connection.getInputStream() : connection.getErrorStream(),
                StandardCharsets.UTF_8
            )
        )) {
            String line;
            int chunkCount = 0;
            while ((line = br.readLine()) != null) {
                if (!ctx.channel().isActive()) {
                    logger.info("检测到客户端连接已断开，停止流式响应处理");
                    if (connection != null) {
                        connection.disconnect();
                    }
                    break;
                }

                if (line.startsWith("data: ")) {
                    String data = line.substring(6);

                    if (data.equals("[DONE]")) {
                        logger.info("收到流式响应结束标记");
                        break;
                    }

                    ByteBuf content = ctx.alloc().buffer();
                    content.writeBytes(line.getBytes(StandardCharsets.UTF_8));
                    content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));

                    HttpContent httpContent = new DefaultHttpContent(content);

                    ChannelFuture future = ctx.writeAndFlush(httpContent);

                    future.addListener((ChannelFutureListener) channelFuture -> {
                        if (!channelFuture.isSuccess()) {
                            logger.info("写入流式数据失败，可能是客户端断开连接: {}", channelFuture.cause().getMessage());
                            ctx.close();
                        }
                    });

                    chunkCount++;
                } else if (line.startsWith("event: ")) {
                    ByteBuf content = ctx.alloc().buffer();
                    content.writeBytes(line.getBytes(StandardCharsets.UTF_8));
                    content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));

                    HttpContent httpContent = new DefaultHttpContent(content);
                    ctx.writeAndFlush(httpContent);
                } else if (line.isEmpty()) {
                    ByteBuf content = ctx.alloc().buffer();
                    content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));

                    HttpContent httpContent = new DefaultHttpContent(content);
                    ctx.writeAndFlush(httpContent);
                }
            }

            logger.info("Anthropic 流式响应处理完成，共发送 {} 个数据块", chunkCount);
        } catch (Exception e) {
            logger.info("处理 Anthropic 流式响应时发生错误", e);
            if (e.getMessage() != null &&
                (e.getMessage().contains("Connection reset by peer") ||
                 e.getMessage().contains("Broken pipe") ||
                 e.getMessage().contains("Connection closed"))) {
                logger.info("检测到客户端断开连接，尝试断开与llama.cpp的连接");
                if (connection != null) {
                    connection.disconnect();
                }
            }
            throw e;
        }

        LastHttpContent lastContent = LastHttpContent.EMPTY_LAST_CONTENT;
        ctx.writeAndFlush(lastContent).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                ctx.close();
            }
        });
    }

    private void handleAnthropicStreamFromOai(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode, String modelName) throws IOException {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");

        ctx.write(response);
        ctx.flush();

        AnthropicStreamState streamState = new AnthropicStreamState();

        try (BufferedReader br = new BufferedReader(
            new InputStreamReader(
                responseCode >= 200 && responseCode < 300 ?
                    connection.getInputStream() : connection.getErrorStream(),
                StandardCharsets.UTF_8
            )
        )) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!ctx.channel().isActive()) {
                    if (connection != null) {
                        connection.disconnect();
                    }
                    break;
                }
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String data = line.substring(6).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    if (!streamState.finished) {
                        String tail = buildAnthropicStopEvents(streamState);
                        if (!tail.isEmpty()) {
                            writeSseChunk(ctx, tail);
                        }
                    }
                    break;
                }
				else 
				// 统计生成信息 — timings 只在最后一个 chunk 出现，天然作为结束标记
				if(data.contains("\"timings\"")) {
					LlamaRecordService.getInstance().handleStream(modelName, data);
				}

                JsonObject chunk;
                try {
                    JsonElement root = JsonParser.parseString(data);
                    if (!root.isJsonObject()) {
                        continue;
                    }
                    chunk = root.getAsJsonObject();
                } catch (Exception ignore) {
                    continue;
                }

                String out = convertOaiStreamChunkToAnthropicSse(chunk, streamState);
                if (!out.isEmpty()) {
                    writeSseChunk(ctx, out);
                }
            }
        }

        LastHttpContent lastContent = LastHttpContent.EMPTY_LAST_CONTENT;
        ctx.writeAndFlush(lastContent).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                ctx.close();
            }
        });
    }

    private void sendJsonResponse(ChannelHandlerContext ctx, JsonObject json, HttpResponseStatus status) {
        String jsonStr = gson.toJson(json);
        logger.info("Anthropic response status={} body={}", status.code(), jsonStr);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, 
                status,
                Unpooled.copiedBuffer(jsonStr, CharsetUtil.UTF_8)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        // Add CORS headers if needed, or rely on global handler
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        
        ctx.writeAndFlush(response);
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String msg) {
        JsonObject err = new JsonObject();
        JsonObject errorDetail = new JsonObject();
        errorDetail.addProperty("type", "error");
        errorDetail.addProperty("message", msg);
        err.add("error", errorDetail);
        
        sendJsonResponse(ctx, err, status);
    }
    
    /**
     * 	将/v1/messages请求转换为OpenAI的/v1/chat/completions请求
     * @param body
     * @return
     */
    private JsonObject convertAnthropicToOai(JsonObject body) {
        if (body == null || body.isJsonNull()) {
            throw new IllegalArgumentException("Request body cannot be null");
        }

        JsonObject oaiBody = new JsonObject();
        JsonArray oaiMessages = new JsonArray();

        JsonElement systemParam = body.get("system");
        if (systemParam != null && !systemParam.isJsonNull()) {
            StringBuilder systemContent = new StringBuilder();
            if (systemParam.isJsonPrimitive() && systemParam.getAsJsonPrimitive().isString()) {
                systemContent.append(systemParam.getAsString());
            } else if (systemParam.isJsonArray()) {
                JsonArray blocks = systemParam.getAsJsonArray();
                for (int i = 0; i < blocks.size(); i++) {
                    JsonElement blockEl = blocks.get(i);
                    if (blockEl == null || !blockEl.isJsonObject()) {
                        continue;
                    }
                    JsonObject block = blockEl.getAsJsonObject();
                    if ("text".equals(getString(block, "type")) && block.has("text") && block.get("text").isJsonPrimitive()) {
                        systemContent.append(getString(block, "text"));
                    }
                }
            }
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", systemContent.toString());
            oaiMessages.add(systemMessage);
        }

        if (!body.has("messages")) {
            throw new IllegalArgumentException("'messages' is required");
        }
        JsonElement messagesEl = body.get("messages");
        if (messagesEl != null && messagesEl.isJsonArray()) {
            JsonArray messages = messagesEl.getAsJsonArray();
            for (int i = 0; i < messages.size(); i++) {
                JsonElement msgEl = messages.get(i);
                if (msgEl == null || !msgEl.isJsonObject()) {
                    continue;
                }
                JsonObject msg = msgEl.getAsJsonObject();
                String role = getString(msg, "role");

                if (!msg.has("content")) {
                    if ("assistant".equals(role)) {
                        continue;
                    }
                    oaiMessages.add(msg.deepCopy());
                    continue;
                }

                JsonElement contentEl = msg.get("content");
                if (contentEl != null && contentEl.isJsonPrimitive() && contentEl.getAsJsonPrimitive().isString()) {
                    oaiMessages.add(msg.deepCopy());
                    continue;
                }

                if (contentEl == null || !contentEl.isJsonArray()) {
                    oaiMessages.add(msg.deepCopy());
                    continue;
                }

                JsonArray content = contentEl.getAsJsonArray();
                JsonArray toolCalls = new JsonArray();
                JsonArray convertedContent = new JsonArray();
                JsonArray toolResults = new JsonArray();
                StringBuilder reasoningContent = new StringBuilder();
                boolean hasToolCalls = false;

                for (int j = 0; j < content.size(); j++) {
                    JsonElement blockEl = content.get(j);
                    if (blockEl == null || !blockEl.isJsonObject()) {
                        continue;
                    }
                    JsonObject block = blockEl.getAsJsonObject();
                    String type = getString(block, "type");

                    if ("text".equals(type)) {
                        convertedContent.add(block.deepCopy());
                    } else if ("thinking".equals(type)) {
                        reasoningContent.append(getString(block, "thinking"));
                    } else if ("image".equals(type)) {
                        JsonObject source = getObject(block, "source");
                        String sourceType = getString(source, "type");
                        if ("base64".equals(sourceType)) {
                            String mediaType = getString(source, "media_type");
                            if (mediaType.isEmpty()) {
                                mediaType = "image/jpeg";
                            }
                            String data = getString(source, "data");
                            JsonObject imageUrl = new JsonObject();
                            imageUrl.addProperty("url", "data:" + mediaType + ";base64," + data);
                            JsonObject imageBlock = new JsonObject();
                            imageBlock.addProperty("type", "image_url");
                            imageBlock.add("image_url", imageUrl);
                            convertedContent.add(imageBlock);
                        } else if ("url".equals(sourceType)) {
                            JsonObject imageUrl = new JsonObject();
                            imageUrl.addProperty("url", getString(source, "url"));
                            JsonObject imageBlock = new JsonObject();
                            imageBlock.addProperty("type", "image_url");
                            imageBlock.add("image_url", imageUrl);
                            convertedContent.add(imageBlock);
                        }
                    } else if ("tool_use".equals(type)) {
                        JsonObject function = new JsonObject();
                        function.addProperty("name", getString(block, "name"));
                        JsonObject inputObj = getObject(block, "input");
                        function.addProperty("arguments", inputObj.toString());

                        JsonObject toolCall = new JsonObject();
                        toolCall.addProperty("id", getString(block, "id"));
                        toolCall.addProperty("type", "function");
                        toolCall.add("function", function);
                        toolCalls.add(toolCall);
                        hasToolCalls = true;
                    } else if ("tool_result".equals(type)) {
                        String toolUseId = getString(block, "tool_use_id");
                        JsonElement resultContentEl = block.get("content");
                        StringBuilder resultText = new StringBuilder();
                        if (resultContentEl != null) {
                            if (resultContentEl.isJsonPrimitive() && resultContentEl.getAsJsonPrimitive().isString()) {
                                resultText.append(resultContentEl.getAsString());
                            } else if (resultContentEl.isJsonArray()) {
                                JsonArray resultArray = resultContentEl.getAsJsonArray();
                                for (int k = 0; k < resultArray.size(); k++) {
                                    JsonElement cEl = resultArray.get(k);
                                    if (cEl == null || !cEl.isJsonObject()) {
                                        continue;
                                    }
                                    JsonObject cObj = cEl.getAsJsonObject();
                                    if ("text".equals(getString(cObj, "type"))) {
                                        resultText.append(getString(cObj, "text"));
                                    }
                                }
                            }
                        }

                        JsonObject toolMsg = new JsonObject();
                        toolMsg.addProperty("role", "tool");
                        toolMsg.addProperty("tool_call_id", toolUseId);
                        toolMsg.addProperty("content", resultText.toString());
                        toolResults.add(toolMsg);
                    }
                }

                if (convertedContent.size() > 0 || hasToolCalls || reasoningContent.length() > 0) {
                    JsonObject newMsg = new JsonObject();
                    newMsg.addProperty("role", role);
                    if (convertedContent.size() > 0) {
                        newMsg.add("content", convertedContent);
                    } else if (hasToolCalls || reasoningContent.length() > 0) {
                        newMsg.addProperty("content", "");
                    }
                    if (toolCalls.size() > 0) {
                        newMsg.add("tool_calls", toolCalls);
                    }
                    if (reasoningContent.length() > 0) {
                        newMsg.addProperty("reasoning_content", reasoningContent.toString());
                    }
                    oaiMessages.add(newMsg);
                }

                for (int j = 0; j < toolResults.size(); j++) {
                    oaiMessages.add(toolResults.get(j));
                }
            }
        }

        oaiBody.add("messages", oaiMessages);

        if (body.has("tools") && body.get("tools").isJsonArray()) {
            JsonArray tools = body.getAsJsonArray("tools");
            JsonArray oaiTools = new JsonArray();
            for (int i = 0; i < tools.size(); i++) {
                JsonElement toolEl = tools.get(i);
                if (toolEl == null || !toolEl.isJsonObject()) {
                    continue;
                }
                JsonObject tool = toolEl.getAsJsonObject();

                JsonObject function = new JsonObject();
                function.addProperty("name", getString(tool, "name"));
                function.addProperty("description", getString(tool, "description"));
                if (tool.has("input_schema") && tool.get("input_schema").isJsonObject()) {
                    function.add("parameters", tool.getAsJsonObject("input_schema").deepCopy());
                } else {
                    function.add("parameters", new JsonObject());
                }

                JsonObject oaiTool = new JsonObject();
                oaiTool.addProperty("type", "function");
                oaiTool.add("function", function);
                oaiTools.add(oaiTool);
            }
            oaiBody.add("tools", oaiTools);
        }

        if (body.has("tool_choice") && body.get("tool_choice").isJsonObject()) {
            JsonObject tc = body.getAsJsonObject("tool_choice");
            String type = getString(tc, "type");
            if ("auto".equals(type)) {
                oaiBody.addProperty("tool_choice", "auto");
            } else if ("any".equals(type) || "tool".equals(type)) {
                oaiBody.addProperty("tool_choice", "required");
            }
        }

        if (body.has("stop_sequences")) {
            oaiBody.add("stop", body.get("stop_sequences").deepCopy());
        }

        if (body.has("max_tokens")) {
            oaiBody.add("max_tokens", body.get("max_tokens").deepCopy());
        } else {
            oaiBody.addProperty("max_tokens", 4096);
        }

        Set<String> passthroughKeys = Set.of("temperature", "top_p", "top_k", "stream");
        for (String key : passthroughKeys) {
            if (body.has(key)) {
                oaiBody.add(key, body.get(key).deepCopy());
            }
        }

        if (body.has("thinking") && body.get("thinking").isJsonObject()) {
            JsonObject thinking = body.getAsJsonObject("thinking");
            if ("enabled".equals(getString(thinking, "type"))) {
                Integer budgetTokens = JsonUtil.getJsonInt(thinking, "budget_tokens", 10000);
                oaiBody.addProperty("thinking_budget_tokens", budgetTokens == null ? 10000 : budgetTokens);
            }
        }

        if (body.has("metadata") && body.get("metadata").isJsonObject()) {
            JsonObject metadata = body.getAsJsonObject("metadata");
            String userId = getString(metadata, "user_id");
            if (!userId.isEmpty()) {
                oaiBody.addProperty("__metadata_user_id", userId);
            }
        }

        if (body.has("model")) {
            oaiBody.add("model", body.get("model").deepCopy());
        }

        return oaiBody;
    }

    public JsonObject convertAnthropicToOai(String body) {
        if (body == null || body.trim().isEmpty()) {
            throw new IllegalArgumentException("Request body cannot be empty");
        }
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("Request body must be a JSON object");
        }
        return convertAnthropicToOai(root.getAsJsonObject());
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return "";
        }
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return "";
        }
        try {
            return el.getAsString();
        } catch (Exception ignore) {
            return "";
        }
    }

    private JsonObject getObject(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key)) {
            return new JsonObject();
        }
        JsonElement el = obj.get(key);
        if (el == null || !el.isJsonObject()) {
            return new JsonObject();
        }
        return el.getAsJsonObject();
    }

    private JsonObject convertOaiResponseToAnthropic(JsonObject oaiRes) {
        JsonObject result = new JsonObject();
        result.addProperty("id", getString(oaiRes, "id"));
        result.addProperty("type", "message");
        result.addProperty("role", "assistant");
        result.addProperty("model", getString(oaiRes, "model"));

        JsonObject message = getChoiceMessage(oaiRes);
        JsonArray content = new JsonArray();
        String reasoningContent = getString(message, "reasoning_content");
        if (!reasoningContent.isEmpty()) {
            JsonObject thinkingBlock = new JsonObject();
            thinkingBlock.addProperty("type", "thinking");
            thinkingBlock.addProperty("thinking", reasoningContent);
            thinkingBlock.addProperty("signature", "");
            content.add(thinkingBlock);
        }

        String text = extractAssistantText(message.get("content"));
        if (!text.isEmpty()) {
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", text);
            content.add(textBlock);
        }

        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            JsonArray toolCalls = message.getAsJsonArray("tool_calls");
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonElement tcEl = toolCalls.get(i);
                if (tcEl == null || !tcEl.isJsonObject()) {
                    continue;
                }
                JsonObject tc = tcEl.getAsJsonObject();
                JsonObject function = getObject(tc, "function");
                JsonObject toolUse = new JsonObject();
                toolUse.addProperty("type", "tool_use");
                toolUse.addProperty("id", getString(tc, "id"));
                toolUse.addProperty("name", getString(function, "name"));
                String arguments = getString(function, "arguments");
                JsonElement input = tryParseJson(arguments);
                if (input != null && input.isJsonObject()) {
                    toolUse.add("input", input);
                } else {
                    toolUse.add("input", new JsonObject());
                }
                content.add(toolUse);
            }
        }
        result.add("content", content);

        JsonObject choice = getChoice(oaiRes);
        String finishReason = getString(choice, "finish_reason");
        result.addProperty("stop_reason", mapStopReason(finishReason, message));
        JsonElement stop = choice.get("stop_sequence");
        if (stop != null && !stop.isJsonNull()) {
            result.add("stop_sequence", stop.deepCopy());
        } else {
            result.add("stop_sequence", null);
        }

        JsonObject usage = getObject(oaiRes, "usage");
        int promptTokens = getInt(usage, "prompt_tokens", 0);
        int completionTokens = getInt(usage, "completion_tokens", 0);
        int cachedTokens = 0;
        JsonObject promptDetail = getObject(usage, "prompt_tokens_details");
        if (promptDetail.has("cached_tokens")) {
            cachedTokens = getInt(promptDetail, "cached_tokens", 0);
        }
        JsonObject outUsage = new JsonObject();
        outUsage.addProperty("cache_read_input_tokens", Math.max(0, cachedTokens));
        outUsage.addProperty("input_tokens", Math.max(0, promptTokens - cachedTokens));
        outUsage.addProperty("output_tokens", Math.max(0, completionTokens));
        result.add("usage", outUsage);
        return result;
    }

    private String convertOaiStreamChunkToAnthropicSse(JsonObject chunk, AnthropicStreamState state) {
        JsonObject choice = getChoice(chunk);
        JsonObject delta = getObject(choice, "delta");

        StringBuilder out = new StringBuilder();
        if (!state.messageStarted) {
            out.append(buildAnthropicEvent("message_start", buildMessageStartData(chunk)));
            state.messageStarted = true;
        }

        String reasoningDelta = getString(delta, "reasoning_content");
        if (!reasoningDelta.isEmpty()) {
            state.hasThinking = true;
            if (!state.thinkingStarted) {
                out.append(buildAnthropicEvent("content_block_start", buildContentBlockStart(0, "thinking", "", "")));
                state.thinkingStarted = true;
            }
            out.append(buildAnthropicEvent("content_block_delta", buildThinkingDelta(0, reasoningDelta)));
            state.outputTokens++;
        }

        String textDelta = getString(delta, "content");
        if (!textDelta.isEmpty()) {
            if (!state.textStarted) {
                state.hasText = true;
                out.append(buildAnthropicEvent("content_block_start", buildContentBlockStart(state.getTextIndex(), "text", "", "")));
                state.textStarted = true;
            }
            out.append(buildAnthropicEvent("content_block_delta", buildTextDelta(state.getTextIndex(), textDelta)));
            state.outputTokens++;
        }

        JsonElement toolCallsEl = delta.get("tool_calls");
        if (toolCallsEl != null && toolCallsEl.isJsonArray()) {
            JsonArray toolCalls = toolCallsEl.getAsJsonArray();
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonElement tcEl = toolCalls.get(i);
                if (tcEl == null || !tcEl.isJsonObject()) {
                    continue;
                }
                JsonObject tc = tcEl.getAsJsonObject();
                int idx = getInt(tc, "index", i);
                ToolCallState toolState = state.toolStates.computeIfAbsent(idx, k -> new ToolCallState());
                String id = getString(tc, "id");
                if (!id.isEmpty()) {
                    toolState.id = id;
                }
                JsonObject function = getObject(tc, "function");
                String name = getString(function, "name");
                if (!name.isEmpty()) {
                    toolState.name = name;
                }
                int blockIndex = state.getToolIndex(idx);
                if (!state.toolStartedIndexes.contains(idx) && !toolState.name.isEmpty()) {
                    out.append(buildAnthropicEvent("content_block_start", buildContentBlockStart(blockIndex, "tool_use", toolState.id, toolState.name)));
                    state.toolStartedIndexes.add(idx);
                }
                String argumentsDelta = getString(function, "arguments");
                if (!argumentsDelta.isEmpty()) {
                    out.append(buildAnthropicEvent("content_block_delta", buildInputJsonDelta(blockIndex, argumentsDelta)));
                    state.outputTokens++;
                }
            }
        }

        JsonObject usage = getObject(chunk, "usage");
        int completionTokens = getInt(usage, "completion_tokens", -1);
        if (completionTokens >= 0) {
            state.outputTokens = Math.max(state.outputTokens, completionTokens);
        }

        String finishReason = getString(choice, "finish_reason");
        if (!finishReason.isEmpty() && !"null".equalsIgnoreCase(finishReason)) {
            state.stopReason = mapStopReason(finishReason, null);
            if (!state.finished) {
                out.append(buildAnthropicStopEvents(state));
                state.finished = true;
            }
        }

        return out.toString();
    }

    private JsonObject buildMessageStartData(JsonObject chunk) {
        JsonObject message = new JsonObject();
        message.addProperty("id", getString(chunk, "id"));
        message.addProperty("type", "message");
        message.addProperty("role", "assistant");
        message.add("content", new JsonArray());
        message.addProperty("model", getString(chunk, "model"));
        message.add("stop_reason", null);
        message.add("stop_sequence", null);

        JsonObject usage = getObject(chunk, "usage");
        int promptTokens = getInt(usage, "prompt_tokens", 0);
        int cachedTokens = 0;
        JsonObject promptDetail = getObject(usage, "prompt_tokens_details");
        if (promptDetail.has("cached_tokens")) {
            cachedTokens = getInt(promptDetail, "cached_tokens", 0);
        }
        JsonObject msgUsage = new JsonObject();
        msgUsage.addProperty("cache_read_input_tokens", Math.max(0, cachedTokens));
        int inputTokens = Math.max(0, promptTokens - cachedTokens);
        if (inputTokens == 0) {
            inputTokens = 1;
        }
        msgUsage.addProperty("input_tokens", inputTokens);
        msgUsage.addProperty("output_tokens", 0);
        message.add("usage", msgUsage);

        JsonObject data = new JsonObject();
        data.addProperty("type", "message_start");
        data.add("message", message);
        return data;
    }

    private JsonObject buildContentBlockStart(int index, String type, String id, String name) {
        JsonObject contentBlock = new JsonObject();
        contentBlock.addProperty("type", type);
        if ("thinking".equals(type)) {
            contentBlock.addProperty("thinking", "");
        } else if ("text".equals(type)) {
            contentBlock.addProperty("text", "");
        } else if ("tool_use".equals(type)) {
            contentBlock.addProperty("id", id == null ? "" : id);
            contentBlock.addProperty("name", name == null ? "" : name);
        }
        JsonObject data = new JsonObject();
        data.addProperty("type", "content_block_start");
        data.addProperty("index", index);
        data.add("content_block", contentBlock);
        return data;
    }

    private JsonObject buildThinkingDelta(int index, String deltaText) {
        JsonObject delta = new JsonObject();
        delta.addProperty("type", "thinking_delta");
        delta.addProperty("thinking", deltaText);
        JsonObject data = new JsonObject();
        data.addProperty("type", "content_block_delta");
        data.addProperty("index", index);
        data.add("delta", delta);
        return data;
    }

    private JsonObject buildTextDelta(int index, String deltaText) {
        JsonObject delta = new JsonObject();
        delta.addProperty("type", "text_delta");
        delta.addProperty("text", deltaText);
        JsonObject data = new JsonObject();
        data.addProperty("type", "content_block_delta");
        data.addProperty("index", index);
        data.add("delta", delta);
        return data;
    }

    private JsonObject buildInputJsonDelta(int index, String partialJson) {
        JsonObject delta = new JsonObject();
        delta.addProperty("type", "input_json_delta");
        delta.addProperty("partial_json", partialJson);
        JsonObject data = new JsonObject();
        data.addProperty("type", "content_block_delta");
        data.addProperty("index", index);
        data.add("delta", delta);
        return data;
    }

    private String buildAnthropicStopEvents(AnthropicStreamState state) {
        StringBuilder out = new StringBuilder();
        if (state.thinkingStarted) {
            JsonObject signatureData = new JsonObject();
            signatureData.addProperty("type", "content_block_delta");
            signatureData.addProperty("index", 0);
            JsonObject delta = new JsonObject();
            delta.addProperty("type", "signature_delta");
            delta.addProperty("signature", "");
            signatureData.add("delta", delta);
            out.append(buildAnthropicEvent("content_block_delta", signatureData));

            JsonObject stopData = new JsonObject();
            stopData.addProperty("type", "content_block_stop");
            stopData.addProperty("index", 0);
            out.append(buildAnthropicEvent("content_block_stop", stopData));
        }
        if (state.textStarted) {
            JsonObject stopData = new JsonObject();
            stopData.addProperty("type", "content_block_stop");
            stopData.addProperty("index", state.getTextIndex());
            out.append(buildAnthropicEvent("content_block_stop", stopData));
        }
        for (Integer idx : state.toolStartedIndexes) {
            JsonObject stopData = new JsonObject();
            stopData.addProperty("type", "content_block_stop");
            stopData.addProperty("index", state.getToolIndex(idx));
            out.append(buildAnthropicEvent("content_block_stop", stopData));
        }

        JsonObject messageDelta = new JsonObject();
        messageDelta.addProperty("type", "message_delta");
        JsonObject deltaObj = new JsonObject();
        deltaObj.addProperty("stop_reason", state.stopReason == null ? "end_turn" : state.stopReason);
        deltaObj.add("stop_sequence", null);
        messageDelta.add("delta", deltaObj);
        JsonObject usage = new JsonObject();
        usage.addProperty("output_tokens", Math.max(1, state.outputTokens));
        messageDelta.add("usage", usage);
        out.append(buildAnthropicEvent("message_delta", messageDelta));

        JsonObject messageStop = new JsonObject();
        messageStop.addProperty("type", "message_stop");
        out.append(buildAnthropicEvent("message_stop", messageStop));
        return out.toString();
    }

    private void writeSseChunk(ChannelHandlerContext ctx, String sseData) {
        if (sseData == null || sseData.isEmpty()) {
            return;
        }
        ByteBuf content = ctx.alloc().buffer();
        content.writeBytes(sseData.getBytes(StandardCharsets.UTF_8));
        HttpContent httpContent = new DefaultHttpContent(content);
        ctx.writeAndFlush(httpContent);
    }

    private String buildAnthropicEvent(String event, JsonObject data) {
        return "event: " + event + "\n" + "data: " + JsonUtil.toJson(data) + "\n\n";
    }

    private JsonObject getChoice(JsonObject root) {
        if (root == null || !root.has("choices") || !root.get("choices").isJsonArray()) {
            return new JsonObject();
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices.size() == 0 || !choices.get(0).isJsonObject()) {
            return new JsonObject();
        }
        return choices.get(0).getAsJsonObject();
    }

    private JsonObject getChoiceMessage(JsonObject root) {
        JsonObject choice = getChoice(root);
        if (choice.has("message") && choice.get("message").isJsonObject()) {
            return choice.getAsJsonObject("message");
        }
        return new JsonObject();
    }

    private String extractAssistantText(JsonElement contentEl) {
        if (contentEl == null || contentEl.isJsonNull()) {
            return "";
        }
        if (contentEl.isJsonPrimitive()) {
            try {
                return contentEl.getAsString();
            } catch (Exception ignore) {
                return "";
            }
        }
        if (contentEl.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            JsonArray arr = contentEl.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement itemEl = arr.get(i);
                if (itemEl == null || !itemEl.isJsonObject()) {
                    continue;
                }
                JsonObject item = itemEl.getAsJsonObject();
                if ("text".equals(getString(item, "type"))) {
                    sb.append(getString(item, "text"));
                }
            }
            return sb.toString();
        }
        return "";
    }

    private String mapStopReason(String finishReason, JsonObject message) {
        if ("length".equals(finishReason) || "max_tokens".equals(finishReason)) {
            return "max_tokens";
        }
        boolean hasToolCalls = message != null && message.has("tool_calls") && message.get("tool_calls").isJsonArray() && message.getAsJsonArray("tool_calls").size() > 0;
        if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason) || hasToolCalls) {
            return "tool_use";
        }
        return "end_turn";
    }

    private JsonElement tryParseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(text);
        } catch (Exception ignore) {
            return null;
        }
    }

    private int getInt(JsonObject obj, String key, int fallback) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static class ToolCallState {
        private String id = "";
        private String name = "";
    }

    private static class AnthropicStreamState {
        private boolean messageStarted = false;
        private boolean hasThinking = false;
        private boolean thinkingStarted = false;
        private boolean hasText = false;
        private boolean textStarted = false;
        private boolean finished = false;
        private String stopReason = "end_turn";
        private int outputTokens = 0;
        private final Map<Integer, ToolCallState> toolStates = new HashMap<>();
        private final Set<Integer> toolStartedIndexes = new HashSet<>();

        private int getTextIndex() {
            return hasThinking ? 1 : 0;
        }

        private int getToolIndex(int toolCallIndex) {
            int base = (hasThinking ? 1 : 0) + (hasText ? 1 : 0);
            return base + toolCallIndex;
        }
    }
    
    
    
    
	/**
	 * 	当连接断开时调用，用于清理{@link #channelConnectionMap}
	 * 
	 * @param ctx
	 * @throws Exception
	 */
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
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
