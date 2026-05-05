package org.mark.llamacpp.lmstudio.websocket;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaCppProcessFix;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.tools.ParamTool;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.*;

/**
 * WebSocket服务器处理器
 */
public class LMStudioWebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    
    // WebSocket管理器
    private final LMStudioWebSocketManager wsManager = LMStudioWebSocketManager.getInstance();
    
    // 当前连接的ID
    private String connectionId;
    
    //
    private boolean connected = false;
    
    // JSON解析器
    private static final Gson gson = new Gson();
    
    private static final Pattern QUANT_BITS = Pattern.compile("(?i)^Q(\\d+).*$");
    private static final Pattern PARAMS_B = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)B");
    
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ConcurrentMap<String, String> INSTANCE_REFERENCE_BY_KEY = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Long> LAST_USED_TIME_BY_KEY = new ConcurrentHashMap<>();
    
    
    public LMStudioWebSocketHandler() {
    }
    
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        // 处理不同类型的WebSocket帧
        if (frame instanceof TextWebSocketFrame) {
            handleTextFrame(ctx, (TextWebSocketFrame) frame);
        } else if (frame instanceof PingWebSocketFrame) {
            handlePingFrame(ctx, (PingWebSocketFrame) frame);
        } else if (frame instanceof PongWebSocketFrame) {
            handlePongFrame(ctx, (PongWebSocketFrame) frame);
        } else if (frame instanceof CloseWebSocketFrame) {
            handleCloseFrame(ctx, (CloseWebSocketFrame) frame);
        } else {
        	this.connected = false;
            throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass().getName()));
        }
    }
    
    /**
     *  处理文本内容：{"authVersion":1,"clientIdentifier":"omYqsPM7sz9z9qCFOalzpwdK","clientPasskey":"5u4MoF5V7oQpqXuttGNqArVy"}
     * @param ctx
     * @param frame
     */
    private void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String request = frame.text();
        //logger.info("WebSocket收到消息[{} - {}]: {}", this.connectionId, this.requestPath, request);
        
        try {
            JsonObject obj = gson.fromJson(request, JsonObject.class);
            if (obj != null) {
                JsonElement type = obj.get("type");
                if (type != null) {
                    String typeStr = safeString(type);
                    if ("connect".equals(typeStr)) {
                        if (this.connectionId != null) {
                            this.wsManager.confirmConnection(this.connectionId);
                        }
                        ctx.channel().writeAndFlush(new TextWebSocketFrame("{\"success\": true}"));
                        return;
                    }
                    
                    if ("rpcCall".equals(typeStr)) {
                        String endpoint = safeString(obj.get("endpoint"));
                        Integer callId = safeInt(obj.get("callId"));
                        if ("listDownloadedModels".equals(endpoint) && callId != null) {
                            this.handleListDownloadedModelsRpc(ctx, callId.intValue());
                            return;
                        }
                        if ("listLoaded".equals(endpoint) && callId != null) {
                            this.handleListLoadedRpc(ctx, callId.intValue());
                            return;
                        }
                        if ("getModelInfo".equals(endpoint) && callId != null) {
                            this.handleGetModelInfoRpc(ctx, callId.intValue(), obj);
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }
        
        ctx.channel().writeAndFlush(new TextWebSocketFrame("{\"success\": true}"));
    }
    
    
    /**
     * 	获取所有可用模型，处理报文：{"type":"rpcCall","endpoint":"listDownloadedModels","callId":0}
     * @param ctx
     * @param callId
     */
    private void handleListDownloadedModelsRpc(ChannelHandlerContext ctx, int callId) {
        try {
            LlamaServerManager manager = LlamaServerManager.getInstance();
            List<GGUFModel> models = manager.listModel(false);
            List<Map<String, Object>> result = new ArrayList<>();
            for (GGUFModel model : models) {
                if (model == null) continue;
                GGUFMetaData md = model.getPrimaryModel();
                if (md == null) continue;

                String modelId = model.getModelId();
                
                JsonObject caps = manager.getModelCapabilities(modelId);
                
                String architecture = md.getArchitecture();
                boolean vision = model.getMmproj() != null;
                String type = ParamTool.parseJsonBoolean(caps, "embedding", false) ? "embedding" : "llm";
                String quantName = model.getQuantizationType();
                Integer bits = resolveQuantBits(quantName);

                String baseName = fileNameToBaseName(md.getFileName());
                String modelKey = modelId;

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", type);
                item.put("modelKey", modelKey);
                item.put("format", "gguf");
                item.put("publisher", "GGUF");
                item.put("displayName", baseNameToDisplayName(baseName));
                item.put("path", modelId);
                item.put("sizeBytes", model.getSize());
                item.put("architecture", architecture);

                Map<String, Object> quant = new LinkedHashMap<>();
                quant.put("bits", bits);
                quant.put("name", quantName);
                item.put("quantization", quant);
                // 这个功能中，只有非嵌入模型才会显示这些东西。
                if (!"embedding".equals(type)) {
                    item.put("paramsString", resolveParamsString(baseName));
                    item.put("vision", vision);
                    item.put("trainedForToolUse", ParamTool.parseJsonBoolean(caps, "tools", false));
                }
                item.put("maxContextLength", md.getContextLength());
                result.add(item);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("type", "rpcResult");
            resp.put("callId", callId);
            resp.put("result", result);
            ctx.channel().writeAndFlush(new TextWebSocketFrame(gson.toJson(resp)));
        } catch (Exception e) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("type", "rpcResult");
            resp.put("callId", callId);
            resp.put("result", List.of());
            ctx.channel().writeAndFlush(new TextWebSocketFrame(gson.toJson(resp)));
        }
    }
    
    
    /**
     * 	查询已加载的模型，处理报文：{"type":"rpcCall","endpoint":"listLoaded","callId":0}
     * @param ctx
     * @param callId
     */
    private void handleListLoadedRpc(ChannelHandlerContext ctx, int callId) {
        try {
            LlamaServerManager manager = LlamaServerManager.getInstance();
            manager.listModel(false);
            Map<String, LlamaCppProcessFix> loaded = manager.getLoadedProcesses();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map.Entry<String, LlamaCppProcessFix> e : loaded.entrySet()) {
                String modelId = e.getKey();
                LlamaCppProcessFix proc = e.getValue();
                if (modelId == null || modelId.isBlank() || proc == null) continue;
                
                JsonObject caps = manager.getModelCapabilities(modelId);
                if (ParamTool.parseJsonBoolean(caps, "embedding", false)) {
                    continue;
                }

                String instanceKey = modelId + ":" + proc.getPid();
                String instanceReference = INSTANCE_REFERENCE_BY_KEY.computeIfAbsent(instanceKey, k -> randomInstanceReference());
                Long lastUsedTime = LAST_USED_TIME_BY_KEY.computeIfAbsent(instanceKey, k -> System.currentTimeMillis());
                result.add(buildLoadedModelItem(manager, modelId, proc, instanceReference, lastUsedTime, caps));
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("type", "rpcResult");
            resp.put("callId", callId);
            resp.put("result", result);
            ctx.channel().writeAndFlush(new TextWebSocketFrame(gson.toJson(resp)));
        } catch (Exception e) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("type", "rpcResult");
            resp.put("callId", callId);
            resp.put("result", List.of());
            ctx.channel().writeAndFlush(new TextWebSocketFrame(gson.toJson(resp)));
        }
    }
    
    /**
     * 	获取模型信息，处理报文：{"type":"rpcCall","endpoint":"getModelInfo","callId":1,"parameter":{"specifier":{"type":"instanceReference","instanceReference":"qR88yCcuNjzLeNiUrpm628+Y"},"throwIfNotFound":false}}
     * @param ctx
     * @param callId
     * @param root
     */
    private void handleGetModelInfoRpc(ChannelHandlerContext ctx, int callId, JsonObject root) {
        String instanceReference = null;
        boolean throwIfNotFound = false;
        try {
            JsonObject parameter = safeObj(root == null ? null : root.get("parameter"));
            if (parameter != null) {
                JsonObject specifierWrapper = safeObj(parameter.get("specifier"));
                if (specifierWrapper != null) {
                    String specType = safeString(specifierWrapper.get("type"));
                    if ("instanceReference".equals(specType)) {
                        instanceReference = safeString(specifierWrapper.get("instanceReference"));
                    }
                }
                Boolean tif = safeBool(parameter.get("throwIfNotFound"));
                throwIfNotFound = Boolean.TRUE.equals(tif);
            }
        } catch (Exception ignore) {
        }
        
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("type", "rpcResult");
        resp.put("callId", callId);
        
        if (instanceReference == null || instanceReference.isBlank()) {
            resp.put("result", null);
            ctx.channel().writeAndFlush(new TextWebSocketFrame(gson.toJson(resp)));
            return;
        }
        
        try {
            LlamaServerManager manager = LlamaServerManager.getInstance();
            manager.listModel(false);
            Map<String, LlamaCppProcessFix> loaded = manager.getLoadedProcesses();
            Map<String, Object> found = null;
            for (Map.Entry<String, LlamaCppProcessFix> e : loaded.entrySet()) {
                String modelId = e.getKey();
                LlamaCppProcessFix proc = e.getValue();
                if (modelId == null || modelId.isBlank() || proc == null) continue;
                String instanceKey = modelId + ":" + proc.getPid();
                String ref = INSTANCE_REFERENCE_BY_KEY.computeIfAbsent(instanceKey, k -> randomInstanceReference());
                if (instanceReference.equals(ref)) {
                    long now = System.currentTimeMillis();
                    LAST_USED_TIME_BY_KEY.put(instanceKey, now);
                    JsonObject caps = manager.getModelCapabilities(modelId);
                    found = buildLoadedModelItem(manager, modelId, proc, ref, now, caps);
                    break;
                }
            }
            if (found == null && throwIfNotFound) {
                resp.put("result", null);
            } else {
                resp.put("result", found);
            }
        } catch (Exception ignore) {
            resp.put("result", null);
        }
        
        ctx.channel().writeAndFlush(new TextWebSocketFrame(gson.toJson(resp)));
    }
    
    /**
     * 	构建已经加载的模型的信息。
     * @param manager
     * @param modelId
     * @param proc
     * @param instanceReference
     * @param lastUsedTime
     * @param caps
     * @return
     */
    private static Map<String, Object> buildLoadedModelItem(
            LlamaServerManager manager,
            String modelId,
            LlamaCppProcessFix proc,
            String instanceReference,
            long lastUsedTime,
            JsonObject caps
    ) {
        GGUFModel model = manager.findModelById(modelId);
        GGUFMetaData md = model == null ? null : model.getPrimaryModel();
        String architecture = md == null ? null : md.getArchitecture();
        boolean vision = model != null && model.getMmproj() != null;
        String type = ParamTool.parseJsonBoolean(caps, "embedding", false) ? "embedding" : "llm";
        String quantName = model == null ? null : model.getQuantizationType();
        Integer bits = resolveQuantBits(quantName);
        long sizeBytes = model == null ? 0L : model.getSize();
        Integer maxContextLength = md == null ? null : md.getContextLength();

        String fileName = md == null ? "" : md.getFileName();
        String baseName = fileNameToBaseName(fileName);
        String baseOrId = baseName.isEmpty() ? modelId : baseName;
        String modelKey = modelId;

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("modelKey", modelKey);
        item.put("format", "gguf");
        item.put("publisher", "GGUF");
        item.put("displayName", baseNameToDisplayName(baseOrId));
        item.put("path", modelId);
        item.put("sizeBytes", sizeBytes);
        item.put("paramsString", resolveParamsString(baseName));
        item.put("architecture", architecture);

        Map<String, Object> quant = new LinkedHashMap<>();
        quant.put("bits", bits);
        quant.put("name", quantName);
        item.put("quantization", quant);

        item.put("identifier", modelId);
        item.put("instanceReference", instanceReference);
        item.put("ttlMs", null);
        item.put("lastUsedTime", lastUsedTime);
        item.put("vision", vision);
        item.put("trainedForToolUse", ParamTool.parseJsonBoolean(caps, "tools", false));
        item.put("maxContextLength", maxContextLength);
        item.put("contextLength", proc.getCtxSize());
        return item;
    }

    private static String safeString(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        try {
            return el.getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer safeInt(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        try {
            return el.getAsInt();
        } catch (Exception e) {
            return null;
        }
    }
    
    private static Boolean safeBool(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        try {
            return el.getAsBoolean();
        } catch (Exception e) {
            return null;
        }
    }
    
    private static JsonObject safeObj(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        try {
            return el.getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 	提取量化信息
     * @param quantName
     * @return
     */
    private static Integer resolveQuantBits(String quantName) {
        if (quantName == null || quantName.isEmpty()) return null;
        if ("F16".equalsIgnoreCase(quantName)) return 16;
        if ("F32".equalsIgnoreCase(quantName)) return 32;
        Matcher m = QUANT_BITS.matcher(quantName);
        if (m.matches()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private static String fileNameToBaseName(String fileName) {
        if (fileName == null) return "";
        String name = fileName;
        if (name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            name = name.substring(0, name.length() - 5);
        }
        name = name.replaceAll("(?i)([-\\.])Q\\d+.*$", "");
        return name;
    }

    private static String baseNameToDisplayName(String baseName) {
        if (baseName == null) return "";
        String normalized = baseName.replace('_', ' ').replace('-', ' ').replace('.', ' ').trim();
        if (normalized.isEmpty()) return baseName;
        String[] parts = normalized.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            if (p.length() == 1) {
                sb.append(p.toUpperCase(Locale.ROOT));
                continue;
            }
            char c0 = p.charAt(0);
            if (Character.isLetter(c0)) {
                sb.append(Character.toUpperCase(c0)).append(p.substring(1));
            } else {
                sb.append(p);
            }
        }
        return sb.toString();
    }

    private static String randomInstanceReference() {
        byte[] bytes = new byte[18];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }
    
    /**
     * 	从模型名字里提取参数信息。
     * @param baseName
     * @return
     */
    private static String resolveParamsString(String baseName) {
        if (baseName == null || baseName.isEmpty()) return null;
        Matcher m = PARAMS_B.matcher(baseName);
        if (m.find()) {
            return m.group(1) + "B";
        }
        return null;
    }
    
    /**
     * 处理Ping帧
     */
    private void handlePingFrame(ChannelHandlerContext ctx, PingWebSocketFrame frame) {
        
        ctx.channel().writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
    }
    
    /**
     * 处理Pong帧
     */
    private void handlePongFrame(ChannelHandlerContext ctx, PongWebSocketFrame frame) {
        
    }
    
    /**
     * 处理关闭帧
     */
    private void handleCloseFrame(ChannelHandlerContext ctx, CloseWebSocketFrame frame) {
        
        if (this.connectionId != null) {
            this.wsManager.removeConnection(this.connectionId);
        }
        ctx.close();
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        if (this.connected && this.connectionId != null) {
            this.wsManager.removeConnection(this.connectionId);
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (this.connected && this.connectionId != null) {
            this.wsManager.removeConnection(this.connectionId);
        }
        ctx.close();
    }

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
			if (!this.connected) {
				this.connected = true;
				this.connectionId = this.wsManager.addConnection(ctx);
			}
			return;
		}
		super.userEventTriggered(ctx, evt);
	}
    
    /**
     * 向所有连接的客户端广播消息
     */
    public static void broadcast(String message) {
        LMStudioWebSocketManager.getInstance().broadcast(message);
    }
    
    /**
     * 获取当前连接数
     */
    public static int getConnectionCount() {
        return LMStudioWebSocketManager.getInstance().getConnectionCount();
    }
}
