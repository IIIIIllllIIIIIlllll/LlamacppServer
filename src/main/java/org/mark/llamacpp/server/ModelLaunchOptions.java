package org.mark.llamacpp.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mark.llamacpp.gguf.GGUFModel;

public class ModelLaunchOptions {
    public Integer ctxSize;
    public Integer batchSize;
    public Integer ubatchSize;
    public Boolean noMmap;
    public Boolean mlock;
    public String llamaBinPath;
    public Double temperature;
    public Double topP;
    public Integer topK;
    public Double minP;
    public Double presencePenalty;
    public Boolean embedding;
    public Boolean reranking;
    public Boolean flashAttention;
    public String extraParams;
    public String host = "0.0.0.0";

    public static ModelLaunchOptions fromLoadRequest(LoadModelRequest r) {
        ModelLaunchOptions o = new ModelLaunchOptions();
        o.ctxSize = r.getCtxSize();
        o.batchSize = r.getBatchSize();
        o.ubatchSize = r.getUbatchSize();
        o.noMmap = r.getNoMmap();
        o.mlock = r.getMlock();
        o.llamaBinPath = r.getLlamaBinPath();
        o.temperature = r.getTemperature();
        o.topP = r.getTopP();
        o.topK = r.getTopK();
        o.minP = r.getMinP();
        o.presencePenalty = r.getPresencePenalty();
        o.embedding = r.getEmbedding();
        o.reranking = r.getReranking();
        o.flashAttention = r.getFlashAttention();
        o.extraParams = r.getExtraParams();
        return o;
    }

    public Map<String, Object> toConfigMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("ctxSize", ctxSize);
        m.put("batchSize", batchSize);
        m.put("ubatchSize", ubatchSize);
        m.put("noMmap", noMmap);
        m.put("mlock", mlock);
        m.put("llamaBinPath", llamaBinPath);
        m.put("temperature", temperature);
        m.put("topP", topP);
        m.put("topK", topK);
        m.put("minP", minP);
        m.put("presencePenalty", presencePenalty);
        m.put("embedding", embedding != null ? embedding : false);
        m.put("reranking", reranking != null ? reranking : false);
        m.put("flashAttention", flashAttention != null ? flashAttention : true);
        m.put("extraParams", extraParams);
        return m;
    }

    public List<String> toCmdLine(GGUFModel targetModel, int port) {
    	List<String> command = new ArrayList<>();
    	String binBase = llamaBinPath != null ? llamaBinPath.trim() : "";
    	command.add(binBase + "/llama-server");
    	command.add("-m");
    	command.add(targetModel.getPath() + "/" + targetModel.getPrimaryModel().getFileName());
    	command.add("--port");
    	command.add(String.valueOf(port));
    	if (targetModel.getMmproj() != null) {
    		command.add("--mmproj");
    		command.add(targetModel.getPath() + "/" + targetModel.getMmproj().getFileName());
    	}
    	if (ctxSize != null) { command.add("-c"); command.add(ctxSize.toString()); }
    	if (batchSize != null) { command.add("-b"); command.add(batchSize.toString()); }
    	if (ubatchSize != null) { command.add("--ubatch-size"); command.add(ubatchSize.toString()); }
    	if (noMmap != null && noMmap) { command.add("--no-mmap"); }
    	if (mlock != null && mlock) { command.add("--mlock"); }
    	if (temperature != null) { command.add("--temp"); command.add(String.valueOf(temperature)); }
    	if (topP != null) { command.add("--top-p"); command.add(String.valueOf(topP)); }
    	if (topK != null) { command.add("--top-k"); command.add(String.valueOf(topK)); }
    	if (minP != null) { command.add("--min-p"); command.add(String.valueOf(minP)); }
    	if (presencePenalty != null) { command.add("--presence-penalty"); command.add(String.valueOf(presencePenalty)); }
    	if (embedding != null && embedding) { command.add("--embedding"); }
    	if (reranking != null && reranking) { command.add("--reranking"); }
    	if (host != null && !host.isEmpty()) { command.add("--host " + host); }
    	// Flash Attention
    	command.add("-fa");
    	if (flashAttention != null && !flashAttention) { command.add("0"); } else { command.add("1"); }
    	// Extra Params
    	if (extraParams != null && !extraParams.trim().isEmpty()) {
    		// Split by whitespace to add as separate arguments
    		String[] parts = extraParams.trim().split("\\s+");
    		for (String part : parts) {
    			if (!part.isEmpty()) {
    				command.add(part);
    			}
    		}
    	}
    	command.add("--no-webui");
        
        //command.add("-np");
        //command.add("4");
        
        //command.add("--jinja");
        
        return command;
    }
}

