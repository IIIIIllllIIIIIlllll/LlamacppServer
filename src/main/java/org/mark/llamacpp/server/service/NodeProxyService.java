package org.mark.llamacpp.server.service;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.mark.llamacpp.server.LlamaHubNode;
import org.mark.llamacpp.server.LlamaServer;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.tools.JsonUtil;
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
import java.util.concurrent.Executors;

/**
 * 请求代理转发服务：将客户端请求代理转发到远程节点
 */
public class NodeProxyService {

    private static final Logger logger = LoggerFactory.getLogger(NodeProxyService.class);

    private static final NodeProxyService INSTANCE = new NodeProxyService();

    private final java.util.concurrent.ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    private NodeProxyService() {
    }

    public static NodeProxyService getInstance() {
        return INSTANCE;
    }

    /**
     * 非流式代理：转发请求到远程节点，将响应原样返回
     */
    public void proxyRequest(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path, JsonObject body) {
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                NodeManager.HttpResult result = NodeManager.getInstance().callRemoteApi(
                        nodeId, request.method().name(), path, body);

                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(result.getStatusCode()));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
                byte[] responseBytes = result.getBody().getBytes(StandardCharsets.UTF_8);
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseBytes.length);
                response.content().writeBytes(responseBytes);

                response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
                response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception e) {
                logger.warn("代理请求失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
                LlamaServer.sendJsonResponse(ctx,
                        org.mark.llamacpp.server.struct.ApiResponse.error("代理请求失败: " + e.getMessage()));
            } catch (Throwable t) {
                logger.error("虚拟线程异常已兜底: {}", t.getMessage(), t);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    /**
     * 流式代理（SSE）：转发请求到远程节点，逐 chunk 转发 SSE 响应
     */
    public void proxyStreamRequest(ChannelHandlerContext ctx, FullHttpRequest request, String nodeId, String path, JsonObject body) {
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                LlamaHubNode node = NodeManager.getInstance().getNode(nodeId);
                if (node == null) {
                    LlamaServer.sendJsonResponse(ctx,
                            org.mark.llamacpp.server.struct.ApiResponse.error("节点不存在: " + nodeId));
                    return;
                }

                String targetUrl = node.getBaseUrl() + "/" + path.replaceFirst("^/", "");
                URL url = URI.create(targetUrl).toURL();
                connection = (HttpURLConnection) url.openConnection();
                if (connection instanceof javax.net.ssl.HttpsURLConnection) {
                    try {
                        NodeManager.trustAllCerts((javax.net.ssl.HttpsURLConnection) connection);
                    } catch (Exception e) {
                        logger.warn("配置HTTPS证书信任失败: {}", e.getMessage());
                    }
                }
                connection.setRequestMethod(request.method().name());
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(36000 * 1000);

                for (java.util.Map.Entry<String, String> entry : request.headers()) {
                    String key = entry.getKey();
                    if (!key.equalsIgnoreCase("Connection") &&
                        !key.equalsIgnoreCase("Content-Length") &&
                        !key.equalsIgnoreCase("Transfer-Encoding")) {
                        connection.setRequestProperty(key, entry.getValue());
                    }
                }

                if (node.getApiKey() != null && !node.getApiKey().isBlank()) {
                    connection.setRequestProperty("Authorization", "Bearer " + node.getApiKey());
                }

                if ((request.method() == HttpMethod.POST || request.method() == HttpMethod.PUT) && body != null) {
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    try (OutputStream os = connection.getOutputStream()) {
                        String jsonStr = JsonUtil.toJson(body);
                        os.write(jsonStr.getBytes(StandardCharsets.UTF_8));
                    }
                }

                int responseCode = connection.getResponseCode();

                HttpResponse response = new DefaultHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(responseCode));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
                response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "*");

                ctx.write(response);
                ctx.flush();

                java.io.InputStream inputStream = (responseCode >= 200 && responseCode < 300)
                        ? connection.getInputStream() : connection.getErrorStream();

                try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (!ctx.channel().isActive() || !ctx.channel().isWritable()) {
                            break;
                        }

                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if (data.equals("[DONE]")) break;

                            String outLine = line;
                            JsonObject parsed = JsonUtil.tryParseObject(data);
                            if (parsed != null) {
                                java.util.Map<Integer, String> toolCallIds = new java.util.HashMap<>();
                                boolean changed = JsonUtil.ensureToolCallIds(parsed, toolCallIds);
                                if (changed) {
                                    outLine = "data: " + JsonUtil.toJson(parsed);
                                }
                            }

                            ByteBuf content = ctx.alloc().buffer();
                            content.writeBytes(outLine.getBytes(StandardCharsets.UTF_8));
                            content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
                            ctx.writeAndFlush(new DefaultHttpContent(content));
                        } else if (line.startsWith("event: ")) {
                            ByteBuf content = ctx.alloc().buffer();
                            content.writeBytes(line.getBytes(StandardCharsets.UTF_8));
                            content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
                            ctx.writeAndFlush(new DefaultHttpContent(content));
                        } else if (line.isEmpty()) {
                            ByteBuf content = ctx.alloc().buffer();
                            content.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
                            ctx.writeAndFlush(new DefaultHttpContent(content));
                        }
                    }
                }

                LastHttpContent lastContent = LastHttpContent.EMPTY_LAST_CONTENT;
                ctx.writeAndFlush(lastContent).addListener(f -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                    }
                    ctx.close();
                });
            } catch (IOException e) {
                logger.warn("流式代理失败: nodeId={}, path={}, error={}", nodeId, path, e.getMessage());
                LlamaServer.sendJsonResponse(ctx,
                        org.mark.llamacpp.server.struct.ApiResponse.error("流式代理失败: " + e.getMessage()));
            } catch (Throwable t) {
                logger.error("虚拟线程异常已兜底: {}", t.getMessage(), t);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    /**
     * 关闭代理服务
     */
    public void shutdown() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
