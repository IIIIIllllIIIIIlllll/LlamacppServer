package org.mark.llamacpp.gguf;

import java.util.Base64;
import java.util.List;



public class GGUFModel {

	/**
	 * 模型的名字
	 */
	private String name;

	/**
	 * 忘了是啥
	 */
	private String architecture;

	/**
	 * 全部的文件信息。
	 */
	private long totalSize;

	/**
	 * 全部的元信息
	 */
	private List<GGUFMetaData> metaDataList;

	/**
	 * 	
	 */
	private GGUFMetaData primaryModel;

	/**
	 * 	
	 */
	private GGUFMetaData mmproj;

	/**
	 * 	路径。
	 */
	private String path;

	/**
	 * 模型别名（用于显示）
	 */
	private String alias;
	
	/**
	 * 	模型ID
	 */
	private String modelId;
	
	
	public GGUFModel(String name, String path) {
		this.path = path;
		this.name = name;
		this.metaDataList = new java.util.ArrayList<>();
		this.modelId = this.name + "_" + new String(Base64.getEncoder().encode(this.path.getBytes()));
	}

	public void addMetaData(GGUFMetaData metaData) {
		this.metaDataList.add(metaData);
	}

	public void setPrimaryModel(GGUFMetaData primaryModel) {
		this.primaryModel = primaryModel;
		// 可以在这里更新模型的其他属性
		if (this.architecture == null) {
			this.architecture = primaryModel.getStringValue("general.architecture");
		}
	}
	
	/**
	 * 	模型的ID，但是直接使用{@link #getName()}
	 * @return
	 */
	public String getModelId() {
		return this.modelId;
	}

	public String getName() {
		return this.name;
	}

	public String getAlias() {
		return this.alias;
	}

	public void setAlias(String alias) {
		this.alias = alias;
	}

	/**
	 * 	
	 * @return
	 */
	public String getPath() {
		return this.path;
	}
	
	
	public GGUFMetaData getPrimaryModel() {
		return this.primaryModel;
	}

	public void setMmproj(GGUFMetaData mmproj) {
		this.mmproj = mmproj;
	}

	public GGUFMetaData getMmproj() {
		return this.mmproj;
	}

	public void setMetaDataList(List<GGUFMetaData> metaDataList) {
		this.metaDataList = metaDataList;
	}

	public List<GGUFMetaData> getMetaDataList() {
		return this.metaDataList;
	}

	public long getSize() {
		return this.totalSize;
	}

	public void setSize(long totalSize) {
		this.totalSize = totalSize;
	}

	@Override
	public String toString() {
		return "Model{" + "name='" + name + '\'' + ", architecture='" + architecture + '\'' + ", parts="
				+ metaDataList.size() + '}';
	}
}
