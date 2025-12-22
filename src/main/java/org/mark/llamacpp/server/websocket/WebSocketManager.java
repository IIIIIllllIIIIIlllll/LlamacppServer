package org.mark.llamacpp.server.websocket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.mark.llamacpp.server.LlamaServerManager;

/**
 * WebSocket连接管理器
 */
public class WebSocketManager {
    
    // 单例实例
    private static volatile WebSocketManager instance;
    
    // 存储所有活跃的WebSocket连接
    private final ConcurrentMap<String, ChannelHandlerContext> connections = new ConcurrentHashMap<>();
    
    // 存储连接的确认状态
    private final ConcurrentMap<String, Boolean> connectionStatus = new ConcurrentHashMap<>();
    
    // 连接计数器
    private int connectionCounter = 0;
    
    // 定时任务执行器，用于发送心跳和定期消息
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    private WebSocketManager() {
        // 启动定时任务，每30秒发送一次心跳
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 30, 30, TimeUnit.SECONDS);
        
        // 启动定时任务，每60秒发送一次系统状态
        scheduler.scheduleAtFixedRate(this::sendSystemStatus, 60, 60, TimeUnit.SECONDS);
    }
    
    /**
     * 获取单例实例
     */
    public static WebSocketManager getInstance() {
        if (instance == null) {
            synchronized (WebSocketManager.class) {
                if (instance == null) {
                    instance = new WebSocketManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 添加新的WebSocket连接
     */
    public String addConnection(ChannelHandlerContext ctx) {
        connectionCounter++;
        String connectionId = "conn-" + connectionCounter;
        connections.put(connectionId, ctx);
        connectionStatus.put(connectionId, false);
        return connectionId;
    }
    
    /**
     * 移除WebSocket连接
     */
    public void removeConnection(String connectionId) {
        connections.remove(connectionId);
        connectionStatus.remove(connectionId);
    }
    
    /**
     * 向指定连接发送消息
     */
    public void sendMessage(String connectionId, String message) {
        ChannelHandlerContext ctx = connections.get(connectionId);
        if (ctx != null && ctx.channel().isActive()) {
            ctx.writeAndFlush(new TextWebSocketFrame(message));
        } else {
            connections.remove(connectionId);
        }
    }
    
    /**
     * 向所有连接广播消息
     */
    public void broadcast(String message) {
        //logger.info("向所有WebSocket客户端广播消息: {}", message);
        
        // 使用迭代器安全地遍历并发集合
        connections.entrySet().removeIf(entry -> {
            //String connectionId = entry.getKey();
            ChannelHandlerContext ctx = entry.getValue();
            
            if (ctx.channel().isActive()) {
                ctx.writeAndFlush(new TextWebSocketFrame(message));
                return false;
            } else {
                return true;
            }
        });
    }
    
    /**
     * 获取当前连接数
     */
    public int getConnectionCount() {
        return connections.size();
    }
    
    /**
     * 确认WebSocket连接
     */
    public void confirmConnection(String connectionId) {
        if (connections.containsKey(connectionId)) {
            connectionStatus.put(connectionId, true);
        }
    }
    
    /**
     * 检查连接是否已确认
     */
    public boolean isConnectionConfirmed(String connectionId) {
        return connectionStatus.getOrDefault(connectionId, false);
    }
    
    /**
     * 获取已确认的连接数
     */
    public int getConfirmedConnectionCount() {
        return (int) connectionStatus.values().stream().mapToInt(status -> status ? 1 : 0).sum();
    }
    
    /**
     * 发送心跳消息
     */
    private void sendHeartbeat() {
        if (getConnectionCount() > 0) {
            long timestamp = System.currentTimeMillis();
            broadcast("{\"type\":\"heartbeat\",\"timestamp\":" + timestamp + "}");
        }
    }
    
    /**
     * 发送系统状态
     */
    private void sendSystemStatus() {
        if (getConnectionCount() > 0) {
            try {
                LlamaServerManager serverManager = LlamaServerManager.getInstance();
                int loadedModelsCount = serverManager.getLoadedProcesses().size();
                
                String statusMessage = String.format(
                    "{\"type\":\"systemStatus\",\"timestamp\":%d,\"loadedModels\":%d,\"connections\":%d,\"confirmedConnections\":%d}",
                    System.currentTimeMillis(),
                    loadedModelsCount,
                    getConnectionCount(),
                    getConfirmedConnectionCount()
                );
                
                broadcast(statusMessage);
            } catch (Exception e) {
            }
        }
    }
    
    /**
     * 发送模型加载事件
     */
    public void sendModelLoadEvent(String modelId, boolean success, String message) {
        String eventMessage = String.format(
            "{\"type\":\"modelLoad\",\"modelId\":\"%s\",\"success\":%s,\"message\":\"%s\",\"timestamp\":%d}",
            modelId,
            success,
            message != null ? message.replace("\"", "\\\"") : "",
            System.currentTimeMillis()
        );
        
        broadcast(eventMessage);
    }
    
    /**
     * 发送模型停止事件
     */
    public void sendModelStopEvent(String modelId, boolean success, String message) {
        String eventMessage = String.format(
            "{\"type\":\"modelStop\",\"modelId\":\"%s\",\"success\":%s,\"message\":\"%s\",\"timestamp\":%d}",
            modelId,
            success,
            message != null ? message.replace("\"", "\\\"") : "",
            System.currentTimeMillis()
        );
        
        broadcast(eventMessage);
    }
    
    public void sendConsoleLineEvent(String modelId, String line) {
        byte[] bytes = line == null ? new byte[0] : line.getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(bytes);
        String eventMessage = String.format(
            "{\"type\":\"console\",\"modelId\":\"%s\",\"line64\":\"%s\",\"timestamp\":%d}",
            modelId != null ? modelId.replace("\"", "\\\"") : "",
            b64,
            System.currentTimeMillis()
        );
        broadcast(eventMessage);
    }
    
    /**
     * 发送下载状态更新事件
     */
    public void sendDownloadStatusEvent(String taskId, String state, long downloadedBytes, long totalBytes,
                                      int partsCompleted, int partsTotal, String fileName, String errorMessage) {
        String eventMessage = String.format(
            "{\"type\":\"download_update\",\"taskId\":\"%s\",\"state\":\"%s\",\"downloadedBytes\":%d,\"totalBytes\":%d,\"partsCompleted\":%d,\"partsTotal\":%d,\"fileName\":\"%s\",\"errorMessage\":\"%s\",\"timestamp\":%d}",
            taskId != null ? taskId.replace("\"", "\\\"") : "",
            state != null ? state.replace("\"", "\\\"") : "",
            downloadedBytes,
            totalBytes,
            partsCompleted,
            partsTotal,
            fileName != null ? fileName.replace("\"", "\\\"") : "",
            errorMessage != null ? errorMessage.replace("\"", "\\\"") : "",
            System.currentTimeMillis()
        );
        
        broadcast(eventMessage);
    }
    
    /**
     * 发送下载进度更新事件（仅针对正在下载的任务）
     */
    public void sendDownloadProgressEvent(String taskId, long downloadedBytes, long totalBytes,
                                        int partsCompleted, int partsTotal, double progressRatio) {
        String eventMessage = String.format(
            "{\"type\":\"download_progress\",\"taskId\":\"%s\",\"downloadedBytes\":%d,\"totalBytes\":%d,\"partsCompleted\":%d,\"partsTotal\":%d,\"progressRatio\":%f,\"timestamp\":%d}",
            taskId != null ? taskId.replace("\"", "\\\"") : "",
            downloadedBytes,
            totalBytes,
            partsCompleted,
            partsTotal,
            progressRatio,
            System.currentTimeMillis()
        );
        
        broadcast(eventMessage);
    }
    
    /**
     * 关闭管理器，释放资源
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // 关闭所有连接
        connections.values().forEach(ctx -> {
            if (ctx.channel().isActive()) {
                ctx.close();
            }
        });
        connections.clear();
        connectionStatus.clear();
    }
}
