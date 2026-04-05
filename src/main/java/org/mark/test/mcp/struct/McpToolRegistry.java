package org.mark.test.mcp.struct;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.mark.test.mcp.IMCPTool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;


/**
 * 	工具注册器。
 */
public class McpToolRegistry {

	private final Map<String, List<IMCPTool>> toolsByService = new ConcurrentHashMap<>();
	
	
	/**
	 * 	注册工具到指定的serviceKey下。比如注册：register("default_tools", tool1); register("default_tools", tool2); 后，
	 * 	可以通过该 serviceKey 访问对应的 MCP 服务。旧版 SSE 使用 `/mcp/{serviceKey}/sse`，Streamable HTTP 使用 `/mcp/{serviceKey}`。
	 * @param serviceKey 可以理解这个 MCP 服务的 key
	 * @param tool	加入的工具
	 */
	public void register(String serviceKey, IMCPTool tool) {
		if (serviceKey == null || serviceKey.isBlank() || tool == null) {
			return;
		}
		String key = serviceKey.trim();
		List<IMCPTool> list = this.toolsByService.computeIfAbsent(key, k -> new ArrayList<>());
		list.add(tool);
	}
	
	
	/**
	 * 	获取指定serviceKey下的全部可用工具。
	 * @param serviceKey
	 * @return
	 */
	public List<IMCPTool> resolve(String serviceKey) {
		if (serviceKey == null || serviceKey.isBlank()) {
			return List.of();
		}
		List<IMCPTool> direct = toolsByService.get(serviceKey.trim());
		if (direct != null && !direct.isEmpty()) {
			return direct;
		}
		List<IMCPTool> fallback = toolsByService.get("default");
		if (fallback == null) {
			return List.of();
		}
		return fallback;
	}
	
	/**
	 * 	查找指定serviceKey下的指定工具
	 * @param serviceKey
	 * @param toolName
	 * @return
	 */
	public IMCPTool findTool(String serviceKey, String toolName) {
		if (toolName == null || toolName.isBlank()) {
			return null;
		}
		for (IMCPTool tool : resolve(serviceKey)) {
			if (tool == null) {
				continue;
			}
			if (toolName.equals(tool.getMcpName())) {
				return tool;
			}
		}
		return null;
	}
	
	/**
	 * 	查询指定serviceKey下的全部工具，但是以JsonArray格式返回。
	 * @param serviceKey
	 * @return
	 */
	public JsonArray toToolJsonArray(String serviceKey) {
		JsonArray tools = new JsonArray();
		for (IMCPTool tool : resolve(serviceKey)) {
			if (tool == null) {
				continue;
			}
			JsonObject item = new JsonObject();
			item.addProperty("name", tool.getMcpName());
			item.addProperty("title", tool.getMcpTitle());
			item.addProperty("description", tool.getMcpDescription());
			item.add("inputSchema", tool.getInputSchema().toJsonObject());
			tools.add(item);
		}
		return tools;
	}
}
