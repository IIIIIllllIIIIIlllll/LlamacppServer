package org.mark.llamacpp.server.struct;

import java.util.List;

/**
 * 	定义参数的结构体。由于要搭配UI使用，因此不要用-c、--no-mmap、mlock等参数
 */
public class ParamStruct implements Comparable<ParamStruct> {
	
	
	public enum ParamType {
		STRING,
		BOOLEAN,
		INTEGER,
		FLOAT,
		LOGIC
	}
	
	/**
	 * 	展示的名字。
	 */
	private String name;
	
	/**
	 * 	参数的全名，如--ctx-size
	 */
	private String fullName;
	
	/**
	 * 	参数的简称，如-c
	 */
	private String abbreviation;
	
	/**
	 * 	这个参数的类型：字符串、布尔值、整数、浮点数、逻辑
	 */
	private ParamType type = ParamType.STRING;
	
	/**
	 * 	默认值，非字符串需要转换成type指定的类型哦。
	 */
	private String defaultValue;
	
	/**
	 * 	描述
	 */
	private String description;
	
	/**
	 * 	有些参数是枚举类型的，只能在有限的值里选一个。
	 */
	private List<String> values; 
	
	/**
	 * 	在UI界面上的排序。
	 */
	private int sort;
	
	
	public ParamStruct(String name) {
		this.name = name;
	}
	
	
	public String getName() {
		return this.name;
	}
	
	
	public String getFullName() {
		return fullName;
	}


	public ParamStruct setFullName(String fullName) {
		this.fullName = fullName;
		return this;
	}


	public String getAbbreviation() {
		return abbreviation;
	}


	public ParamStruct setAbbreviation(String abbreviation) {
		this.abbreviation = abbreviation;
		return this;
	}


	public ParamType getType() {
		return type;
	}


	public ParamStruct setType(ParamType type) {
		this.type = type;
		return this;
	}


	public String getDefaultValue() {
		return defaultValue;
	}


	public ParamStruct setDefaultValue(String defaultValue) {
		this.defaultValue = defaultValue;
		return this;
	}


	public String getDescription() {
		return description;
	}


	public ParamStruct setDescription(String description) {
		this.description = description;
		return this;
	}


	public List<String> getValues() {
		return values;
	}


	public ParamStruct setValues(List<String> values) {
		this.values = values;
		return this;
	}


	public int getSort() {
		return sort;
	}


	public ParamStruct setSort(int sort) {
		this.sort = sort;
		return this;
	}

	@Override
	public int compareTo(ParamStruct o) {
		return Integer.compare(this.getSort(), o.getSort());
	}
}
