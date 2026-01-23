package org.mark.llamacpp.server.struct;



/**
 * 	聊天角色的结构。
 */
public class CharactorDataStruct {
	
	/**
	 * 	唯一编号
	 */
	private long id;
	
	/**
	 * 	标题（名字）
	 */
	private String title;
	
	/**
	 * 	
	 */
	private String prompt;
	
	/**
	 * 	系统提示词
	 */
	private String systemPrompt;
	
	/**
	 * 	参数
	 */
	private String paramsJson;
	private String timingsJson;

	/**
	 * 	使用哪个 OpenAI 兼容端点：1=/v1/chat/completions, 0=/v1/completions
	 */
	private int apiModel = 1;
	
	/**
	 * 	创建时间
	 */
	private long createdAt;
	
	/**
	 * 	最后修改时间
	 */
	private long updatedAt;
	
	
	
	public CharactorDataStruct() {
		
	}
	
	
	public long getId() {
		return id;
	}
	public void setId(long id) {
		this.id = id;
	}
	public String getTitle() {
		return title;
	}
	public void setTitle(String title) {
		this.title = title;
	}
	public String getPrompt() {
		return prompt;
	}
	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}
	public String getSystemPrompt() {
		return systemPrompt;
	}
	public void setSystemPrompt(String systemPrompt) {
		this.systemPrompt = systemPrompt;
	}
	public String getParamsJson() {
		return paramsJson;
	}
	public void setParamsJson(String paramsJson) {
		this.paramsJson = paramsJson;
	}
	public String getTimingsJson() {
		return timingsJson;
	}
	public void setTimingsJson(String timingsJson) {
		this.timingsJson = timingsJson;
	}
	public int getApiModel() {
		return apiModel;
	}
	public void setApiModel(int apiModel) {
		this.apiModel = apiModel;
	}
	public long getCreatedAt() {
		return createdAt;
	}
	public void setCreatedAt(long createdAt) {
		this.createdAt = createdAt;
	}
	public long getUpdatedAt() {
		return updatedAt;
	}
	public void setUpdatedAt(long updatedAt) {
		this.updatedAt = updatedAt;
	}
}
