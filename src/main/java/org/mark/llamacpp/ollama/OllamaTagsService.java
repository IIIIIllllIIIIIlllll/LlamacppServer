package org.mark.llamacpp.ollama;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaCppProcessFix;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.tools.ParamTool;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * 	对应API：/api/tags和/api/ps
 */
public class OllamaTagsService {
	
	
	
	public OllamaTagsService() {
		
	}
	
	/**
	 * 	处理模型列表
	 * @param ctx
	 * @param request
	 */
	public void handleModelList(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.GET) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only GET method is supported");
			return;
		}
		
		LlamaServerManager manager = LlamaServerManager.getInstance();
		Map<String, LlamaCppProcessFix> loaded = manager.getLoadedProcesses();
		manager.listModel();
		
		List<Map<String, Object>> models = new ArrayList<>();
		for (Map.Entry<String, LlamaCppProcessFix> entry : loaded.entrySet()) {
			String modelId = entry.getKey();
			GGUFModel model = manager.findModelById(modelId);
			
			Map<String, Object> item = new HashMap<>();
			item.put("name", modelId);
			item.put("model", modelId);
			
			long size = model == null ? 0L : model.getSize();
			Instant modifiedAt = OllamaApiTool.resolveModifiedAt(model);
			String family = ParamTool.readArchitecture(model);
			String quant = ParamTool.readQuantization(model);
			
			item.put("modified_at", OllamaApiTool.formatOllamaTime(modifiedAt));
			item.put("size", size);
			item.put("digest", OllamaApiTool.sha256Hex(modelId + ":" + item.get("size") + ":" + item.get("modified_at")));
			
			Map<String, Object> details = new HashMap<>();
			details.put("parent_model", "");
			details.put("format", "gguf");
			if (family != null && !family.isBlank()) {
				details.put("family", family);
				List<String> families = new ArrayList<>();
				families.add(family);
				details.put("families", families);
			}
			details.put("parameter_size", OllamaApiTool.guessParameterSize(modelId, size));
			if (quant != null && !quant.isBlank()) {
				details.put("quantization_level", quant);
			}
			item.put("details", details);
			
			models.add(item);
		}
		
		Map<String, Object> resp = new HashMap<>();
		resp.put("models", models);
		
		Ollama.sendOllamaJson(ctx, HttpResponseStatus.OK, resp);
	}
	
	
	/**
	 * 	处理已经加载的模型。
	 * @param ctx
	 * @param request
	 */
	public void handleLoadedModel(ChannelHandlerContext ctx, FullHttpRequest request) {
		if (request.method() != HttpMethod.GET) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only GET method is supported");
			return;
		}
		
		LlamaServerManager manager = LlamaServerManager.getInstance();
		Map<String, LlamaCppProcessFix> loaded = manager.getLoadedProcesses();
		manager.listModel();
		
		List<Map<String, Object>> models = new ArrayList<>();
		for (Map.Entry<String, LlamaCppProcessFix> entry : loaded.entrySet()) {
			String modelId = entry.getKey();
			GGUFModel model = manager.findModelById(modelId);
			
			Map<String, Object> item = new HashMap<>();
			item.put("name", modelId);
			item.put("model", modelId);
			
			long size = model == null ? 0L : model.getSize();
			Instant modifiedAt = OllamaApiTool.resolveModifiedAt(model);
			String family = ParamTool.readArchitecture(model);
			String quant = ParamTool.readQuantization(model);
			
			item.put("expires_at", OllamaApiTool.formatOllamaTime(modifiedAt));
			item.put("size", size);
			item.put("digest", OllamaApiTool.sha256Hex(modelId + ":" + item.get("size") + ":" + item.get("modified_at")));
			item.put("size_vram", 0);
			// 找到上下文参数
			Integer c = entry.getValue().getCtxSize();
			item.put("context_length", c);
			
			Map<String, Object> details = new HashMap<>();
			details.put("parent_model", "");
			details.put("format", "gguf");
			if (family != null && !family.isBlank()) {
				details.put("family", family);
				List<String> families = new ArrayList<>();
				families.add(family);
				details.put("families", families);
			}
			details.put("parameter_size", OllamaApiTool.guessParameterSize(modelId, size));
			if (quant != null && !quant.isBlank()) {
				details.put("quantization_level", quant);
			}
			item.put("details", details);
			
			models.add(item);
		}
		
		Map<String, Object> resp = new HashMap<>();
		resp.put("models", models);
		
		Ollama.sendOllamaJson(ctx, HttpResponseStatus.OK, resp);
	}
}
