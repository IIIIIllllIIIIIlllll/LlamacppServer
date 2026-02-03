package org.mark.llamacpp.ollama;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFMetaDataReader;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaCppProcess;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.tools.ChatTemplateFileTool;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.mark.llamacpp.server.tools.ParamTool;

import com.google.gson.JsonObject;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * 	对应API：/api/show。但是阉割了大量的参数。
 */
public class OllamaShowService {
	
	
	public OllamaShowService() {
		
	}
	
	
	/**
	 * 	返回模型的详细信息。
	 * @param ctx
	 * @param request
	 */
	public void handleShow(ChannelHandlerContext ctx, FullHttpRequest request) {
		// 判断请求方式
		if (request.method() != HttpMethod.POST && request.method() != HttpMethod.GET) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST/GET method is supported");
			return;
		}
		// 两个参数
		String modelName = null; 
		boolean verbose = false;
		// 根据不同的请求方式，取出两个参数
		if (request.method() == HttpMethod.POST) {
			String content = request.content().toString(StandardCharsets.UTF_8);
			
			if (content == null || content.trim().isEmpty()) {
				Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body is empty");
				return;
			}
			JsonObject obj = null;
			try {
				obj = JsonUtil.fromJson(content, JsonObject.class);
			} catch (Exception e) {
				Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
				return;
			}
			if (obj == null) {
				Ollama.sendOllamaError(ctx, HttpResponseStatus.BAD_REQUEST, "Request body parse failed");
				return;
			}
			modelName = JsonUtil.getJsonString(obj, "name", null);
			if (modelName == null || modelName.isBlank()) {
				modelName = JsonUtil.getJsonString(obj, "model", null);
			}
			verbose = ParamTool.parseJsonBoolean(obj, "verbose", false);
		} else {
			Map<String, String> params = ParamTool.getQueryParam(request.uri());
			modelName = params.get("name");
			if (modelName == null || modelName.isBlank()) {
				modelName = params.get("model");
			}
			String v = params.get("verbose");
			if (v != null) {
				String t = v.trim().toLowerCase();
				verbose = "true".equals(t) || "1".equals(t) || "yes".equals(t) || "on".equals(t);
			}
		}
		
		//
		LlamaServerManager manager = LlamaServerManager.getInstance();
		// 从管理器里找出对应的模型。
		GGUFModel model = manager.findModelById(modelName);
		// 没有就返回错误
		if (model == null) {
			Ollama.sendOllamaError(ctx, HttpResponseStatus.NOT_FOUND, "Model not found: " + modelName);
			return;
		}
		//
		String modelId = model.getModelId();
		GGUFMetaData primary = model.getPrimaryModel();
		
		Map<String, Object> modelInfo = new HashMap<>();
		Instant modifiedAt = Instant.now();
		Instant lm = OllamaApiTool.safeModifiedAt(primary.getFilePath());
		if (lm != null) {
			modifiedAt = lm;
		}
		// tokenizer信息，目前返回空值，不影响使用。
		this.readModelTokenizer(modelInfo, primary, verbose);
		// 
		String template = null;
		// 
		try {
			template = ChatTemplateFileTool.readChatTemplateFromCacheFile(modelId);
		} catch (Exception ignore) {
			// 如果读取失败就读取失败
		}
		// 获取模型的架构信息和量化信息。
		String family = null;
		String quant = null;
		if (primary != null) {
			try {
				family = primary.getStringValue("general.architecture");
			} catch (Exception ignore) {
			}
			try {
				quant = primary.getQuantizationType();
			} catch (Exception ignore) {
			}
		}
		if (family == null || family.isBlank()) {
			Object fam = modelInfo.get("general.architecture");
			if (fam != null) {
				family = String.valueOf(fam);
			}
		}
		// 存入一些基本的内容
		File primaryFile = new File(primary.getFilePath());
		Map<String, Object> m = GGUFMetaDataReader.read(primaryFile);
		if (m != null) {
			if (!verbose) {
				m.remove("tokenizer.ggml.tokens.size");
				m.put("tokenizer.ggml.merges", null);
				m.put("tokenizer.ggml.token_type", null);
				m.put("tokenizer.ggml.tokens", null);
			} else {
				m.remove("tokenizer.ggml.tokens.size");
				if (!m.containsKey("tokenizer.ggml.merges")) {
					m.put("tokenizer.ggml.merges", new ArrayList<>());
				}
				if (!m.containsKey("tokenizer.ggml.token_type")) {
					m.put("tokenizer.ggml.token_type", new ArrayList<>());
				}
				if (!m.containsKey("tokenizer.ggml.tokens")) {
					m.put("tokenizer.ggml.tokens", new ArrayList<>());
				}
			}
			// 如果这个模型已经启动了，那就以实际设置的上下文为准
			LlamaCppProcess process = manager.getLoadedProcesses().get(modelId);
			if(process != null) {
				// 修改上下文长度，以实际设定为准
				String key = family + ".context_length";
				m.put(key, process.getCtxSize());
			}
			modelInfo.putAll(m);
		}
		
		// 详情
		Map<String, Object> details = new HashMap<>();
		details.put("parent_model", "");
		details.put("format", "gguf");
		// 存入架构信息
		if (family != null && !family.isBlank()) {
			details.put("family", family);
			details.put("families", new String[] { family });
		}
		// 存入量化信息
		if (quant != null && !quant.isBlank()) {
			details.put("quantization_level", quant);
		}
		// 存入参数信息
		details.put("parameter_size", primary.getSizeLabel());
		
		// 张量信息
		List<Map<String, Object>> tensors = new ArrayList<>();
		// 能力信息
		List<String> capabilities = new ArrayList<>();
		JsonObject capinfo = manager.getModelCapabilities(modelId);
		// 能否工具
		if (ParamTool.parseJsonBoolean(capinfo, "tools", false)) {
			capabilities.add("tools");
		}
		// 能否对话
		if (!ParamTool.parseJsonBoolean(capinfo, "embedding", false)) {
			capabilities.add("completion");
		}
		// 
		if (ParamTool.parseJsonBoolean(capinfo, "thinking", false)) {
			capabilities.add("thinking");
		}
		// 文本嵌入
		if (ParamTool.parseJsonBoolean(capinfo, "embedding", false)) {
			capabilities.add("embedding");
		}
		//
		if (model.getMmproj() != null) {
			capabilities.add("vision");
		}
		// rerank，ollama不支持，但是故意写在这里
//		if (ParamTool.parseJsonBoolean(capinfo, "rerank", false)) {
//			capabilities.add("rerank");
//		}
		
		String license = "";
		
		Map<String, Object> out = new HashMap<>();
		out.put("license", license);
		out.put("modelfile", "");
		out.put("parameters", "");
		out.put("template", template == null ? "" : template);
		out.put("details", details);
		out.put("model_info", modelInfo);
		out.put("tensors", tensors);
		
		out.put("capabilities", capabilities);
		out.put("modified_at", OllamaApiTool.formatOllamaTime(modifiedAt));
		
		Ollama.sendOllamaJson(ctx, HttpResponseStatus.OK, out);
	}
	
	
	/**
	 * 	读取模型的tokenizer信息
	 * @param modelInfo
	 * @param primary
	 * @param verbose
	 */
	private void readModelTokenizer(Map<String, Object> modelInfo, GGUFMetaData primary, boolean verbose) {
		// 
//		File primaryFile = new File(primary.getFilePath());
//		Map<String, Object> m = GGUFMetaDataReader.read(primaryFile);
//		if (m != null) {
//			if (!verbose) {
//				m.remove("tokenizer.ggml.tokens.size");
//				m.put("tokenizer.ggml.merges", null);
//				m.put("tokenizer.ggml.token_type", null);
//				m.put("tokenizer.ggml.tokens", null);
//			} else {
//				m.remove("tokenizer.ggml.tokens.size");
//				if (!m.containsKey("tokenizer.ggml.merges")) {
//					m.put("tokenizer.ggml.merges", new ArrayList<>());
//				}
//				if (!m.containsKey("tokenizer.ggml.token_type")) {
//					m.put("tokenizer.ggml.token_type", new ArrayList<>());
//				}
//				if (!m.containsKey("tokenizer.ggml.tokens")) {
//					m.put("tokenizer.ggml.tokens", new ArrayList<>());
//				}
//			}
//			modelInfo.putAll(m);
//		}
		
		Map<String, Object> m = new HashMap<String, Object>();
		m.put("tokenizer.ggml.merges", new ArrayList<>());
		m.put("tokenizer.ggml.token_type", new ArrayList<>());
		m.put("tokenizer.ggml.tokens", new ArrayList<>());
	}
	
	
}
