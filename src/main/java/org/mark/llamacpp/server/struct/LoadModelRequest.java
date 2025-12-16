package org.mark.llamacpp.server.struct;

/**
 * 加载模型请求参数
 */
public class LoadModelRequest {
    /**
     * 模型ID（必需）
     */
    private String modelId;
    
    /**
     * 上下文大小（可选）
     */
    private Integer ctxSize;
    
    /**
     * 批处理大小（可选）
     */
    private Integer batchSize;
    
    /**
     * 微批处理大小（可选）
     */
    private Integer ubatchSize;
    
    /**
     * 是否禁用内存映射（可选）
     */
    private Boolean noMmap;
    
    /**
     * 是否锁定内存（可选）
     */
    private Boolean mlock;
    private String llamaBinPath;
    private Double temperature;
    private Double topP;
    private Integer topK;
    private Double minP;
    private Double presencePenalty;
    private Double repeatPenalty;
    private Boolean embedding;
    private Boolean reranking;
    private Boolean flashAttention;
    private String extraParams;
    private String slotSavePath;
    
    public LoadModelRequest() {
    }
    
    public LoadModelRequest(String modelId) {
        this.modelId = modelId;
    }
    
    public String getModelId() {
        return modelId;
    }
    
    public void setModelId(String modelId) {
        this.modelId = modelId;
    }
    
    public Integer getCtxSize() {
        return ctxSize;
    }
    
    public void setCtxSize(Integer ctxSize) {
        this.ctxSize = ctxSize;
    }
    
    public Integer getBatchSize() {
        return batchSize;
    }
    
    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }
    
    public Integer getUbatchSize() {
        return ubatchSize;
    }
    
    public void setUbatchSize(Integer ubatchSize) {
        this.ubatchSize = ubatchSize;
    }
    
    public Boolean getNoMmap() {
        return noMmap;
    }
    
    public void setNoMmap(Boolean noMmap) {
        this.noMmap = noMmap;
    }
    
    public Boolean getMlock() {
        return mlock;
    }
    
    public void setMlock(Boolean mlock) {
        this.mlock = mlock;
    }
    public String getLlamaBinPath() {
        return llamaBinPath;
    }
    public void setLlamaBinPath(String llamaBinPath) {
        this.llamaBinPath = llamaBinPath;
    }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public Double getMinP() { return minP; }
    public void setMinP(Double minP) { this.minP = minP; }
    public Double getPresencePenalty() { return presencePenalty; }
    public void setPresencePenalty(Double presencePenalty) { this.presencePenalty = presencePenalty; }
    public Double getRepeatPenalty() { return repeatPenalty; }
    public void setRepeatPenalty(Double repeatPenalty) { this.repeatPenalty = repeatPenalty; }
    public Boolean getEmbedding() { return embedding; }
    public void setEmbedding(Boolean embedding) { this.embedding = embedding; }
    public Boolean getReranking() { return reranking; }
    public void setReranking(Boolean reranking) { this.reranking = reranking; }
    
    public Boolean getFlashAttention() { return flashAttention; }
    public void setFlashAttention(Boolean flashAttention) { this.flashAttention = flashAttention; }
    
    public String getExtraParams() { return extraParams; }
    public void setExtraParams(String extraParams) { this.extraParams = extraParams; }
    public String getSlotSavePath() { return slotSavePath; }
    public void setSlotSavePath(String slotSavePath) { this.slotSavePath = slotSavePath; }

    @Override
    public String toString() {
        return "LoadModelRequest{" +
                "modelId='" + modelId + '\'' +
                ", ctxSize=" + ctxSize +
                ", batchSize=" + batchSize +
                ", ubatchSize=" + ubatchSize +
                ", noMmap=" + noMmap +
                ", mlock=" + mlock +
                ", llamaBinPath='" + llamaBinPath + '\'' +
                ", temperature=" + temperature +
                ", topP=" + topP +
                ", topK=" + topK +
                ", minP=" + minP +
                ", presencePenalty=" + presencePenalty +
                ", repeatPenalty=" + repeatPenalty +
                ", embedding=" + embedding +
                ", reranking=" + reranking +
                ", flashAttention=" + flashAttention +
                ", extraParams='" + extraParams + '\'' +
                ", slotSavePath='" + slotSavePath + '\'' +
                '}';
    }
}
