package org.mark.llamacpp.server.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.mark.llamacpp.server.LlamaServerManager;
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
import java.util.HashMap;
import java.util.Map;
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

    /**
     * Handles GET /v1/models
     */
    public void handleModelsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (request.method() != HttpMethod.GET) {
            sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only GET method is supported");
            return;
        }
        
        String apiKey = request.headers().get("x-api-key");
        if (apiKey == null || !ANTHROPIC_API_KEY.equals(apiKey)) {
            sendError(ctx, HttpResponseStatus.UNAUTHORIZED, "invalid api key");
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
            sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST method is supported");
            return;
        }
        
        String apiKey = request.headers().get("x-api-key");
        if (apiKey == null || !ANTHROPIC_API_KEY.equals(apiKey)) {
            sendError(ctx, HttpResponseStatus.UNAUTHORIZED, "invalid api key");
            return;
        }

        String content = request.content().toString(CharsetUtil.UTF_8);
        if (content == null || content.trim().isEmpty()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body is empty");
            return;
        }

        JsonObject anthropicReq;
        try {
            anthropicReq = gson.fromJson(content, JsonObject.class);
        } catch (Exception e) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Invalid JSON body");
            return;
        }

        String modelName;
        LlamaServerManager manager = LlamaServerManager.getInstance();
        if (anthropicReq.has("model")) {
            modelName = anthropicReq.get("model").getAsString();
        } else {
            modelName = manager.getFirstModelName();
            if (modelName == null) {
                sendError(ctx, HttpResponseStatus.NOT_FOUND, "No models loaded");
                return;
            }
        }
        
        if (!manager.getLoadedProcesses().containsKey(modelName)) {
            if (manager.getLoadedProcesses().size() == 1) {
                modelName = manager.getFirstModelName();
            } else {
                sendError(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
                return;
            }
        }
        
        Integer port = manager.getModelPort(modelName);
        if (port == null) {
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Model port not found for " + modelName);
            return;
        }

        boolean isStream = false;
        if (anthropicReq.has("stream") && anthropicReq.get("stream").isJsonPrimitive()) {
            isStream = anthropicReq.get("stream").getAsBoolean();
        }

        forwardRequestToLlamaCpp(ctx, request, content, port, "/v1/complete", isStream);
    }
    
    /**
     * 	
     * @param ctx
     * @param request
     */
    public void handleMessagesRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (request.method() != HttpMethod.POST) {
            sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST method is supported");
            return;
        }
        
        String apiKey = request.headers().get("x-api-key");
        if (apiKey == null || !ANTHROPIC_API_KEY.equals(apiKey)) {
            sendError(ctx, HttpResponseStatus.UNAUTHORIZED, "invalid api key");
            return;
        }

        String content = request.content().toString(CharsetUtil.UTF_8);
        JsonObject anthropicReq;
        try {
            anthropicReq = gson.fromJson(content, JsonObject.class);
        } catch (Exception e) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "Invalid JSON body");
            return;
        }

        String modelName;
        LlamaServerManager manager = LlamaServerManager.getInstance();
        
        if (anthropicReq.has("model")) {
            modelName = anthropicReq.get("model").getAsString();
        } else {
            modelName = manager.getFirstModelName();
            if (modelName == null) {
                sendError(ctx, HttpResponseStatus.NOT_FOUND, "No models loaded");
                return;
            }
        }
        
        if (!manager.getLoadedProcesses().containsKey(modelName)) {
            if (manager.getLoadedProcesses().size() == 1) {
                modelName = manager.getFirstModelName();
            } else {
                sendError(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
                return;
            }
        }
        
        Integer port = manager.getModelPort(modelName);
        if (port == null) {
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Model port not found for " + modelName);
            return;
        }

        boolean isStream = false;
        if (anthropicReq.has("stream") && anthropicReq.get("stream").isJsonPrimitive()) {
            isStream = anthropicReq.get("stream").getAsBoolean();
        }

        forwardRequestToLlamaCpp(ctx, request, content, port, "/v1/messages", isStream);
    }

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

                int responseCode = connection.getResponseCode();

                if (isStream) {
                    handleStreamResponse(ctx, connection, responseCode);
                } else {
                    handleNonStreamResponse(ctx, connection, responseCode);
                }
            } catch (Exception e) {
                logger.error("Error forwarding Anthropic request to llama.cpp", e);
                sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
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

    private void handleStreamResponse(ChannelHandlerContext ctx, HttpURLConnection connection, int responseCode) throws IOException {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");

        ctx.write(response);
        ctx.flush();

        logger.info("开始处理 Anthropic 流式响应，响应码: {}", responseCode);

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
                            logger.warn("写入流式数据失败，可能是客户端断开连接: {}", channelFuture.cause().getMessage());
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
            logger.error("处理 Anthropic 流式响应时发生错误", e);
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
