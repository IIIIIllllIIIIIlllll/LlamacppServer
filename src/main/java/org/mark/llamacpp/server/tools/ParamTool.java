package org.mark.llamacpp.server.tools;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.mark.llamacpp.gguf.GGUFMetaData;
import org.mark.llamacpp.gguf.GGUFModel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;


/**
 * 	从URL中提取参数。
 */
public class ParamTool {
	
	private static final SimpleDateFormat SDF = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);
	
	
	//private static final Pattern CTX_SIZE = Pattern.compile("(?:(?:--ctx-size)|(?:-c))\\s+(\\d+)");
	
	/**
	 * 安全解析 JSON 对象中的布尔值，支持多种格式的布尔值输入。
	 * 
	 * 该方法尝试从 JsonObject 中根据指定 key 获取布尔值，支持以下几种格式：
	 * - 原生 JSON 布尔值（true/false）
	 * - 字符串形式的布尔表示（如 "true", "false", "1", "0", "yes", "no", "on", "off"，忽略大小写）
	 * - 空字符串、null、非布尔类型值等异常情况时返回默认值（fallback）
	 * 
	 * @param obj  要解析的 JsonObject，若为 null 则直接返回 fallback
	 * @param key  要查找的键名，若为 null 或空字符串，或键不存在，则返回 fallback
	 * @param fallback  当解析失败或值无效时的默认返回值
	 * @return 成功解析时返回对应的布尔值，否则返回 fallback
	 */
	public static boolean parseJsonBoolean(JsonObject obj, String key, boolean fallback) {
		if (obj == null || key == null || key.isEmpty() || !obj.has(key) || obj.get(key) == null || obj.get(key).isJsonNull()) {
			return fallback;
		}
		try {
			return obj.get(key).getAsBoolean();
		} catch (Exception e) {
			try {
				String s = obj.get(key).getAsString();
				if (s == null) return fallback;
				String t = s.trim().toLowerCase();
				if (t.isEmpty()) return fallback;
				if ("true".equals(t) || "1".equals(t) || "yes".equals(t) || "on".equals(t)) return true;
				if ("false".equals(t) || "0".equals(t) || "no".equals(t) || "off".equals(t)) return false;
				return fallback;
			} catch (Exception e2) {
				return fallback;
			}
		}
	}
	
//	/**
//	 * 	提取cmd命令中的上下文参数。
//	 * @param cmd
//	 * @return
//	 */
//    public static Integer parseContextLengthFromCmd(String cmd) {
//        if (cmd == null || cmd.isEmpty()) return null;
//        Matcher m = CTX_SIZE.matcher(cmd);
//        if (m.find()) {
//            try {
//                return Integer.parseInt(m.group(1));
//            } catch (Exception ignore) {
//            }
//        }
//        return 0;
//    }
	
	/**
	 * 	生成当前的时间（
	 * @return
	 */
	public static String getDate() {
		return SDF.format(new Date());
	}
	
	
	public static JsonObject tryParseObject(String s) {
		try {
			if (s == null || s.trim().isEmpty()) {
				return null;
			}
			JsonElement el = JsonUtil.fromJson(s, JsonElement.class);
			return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}
	
	
	/**
	 * 根据内容决定是否为字符串添加引号包裹，并对内部引号进行转义。
	 * 
	 * 该方法用于构建安全的命令行参数字符串，确保参数中包含空格或双引号时仍能被 shell 或系统正确解析。
	 * 
	 * 规则：
	 * - 若字符串包含任意空白字符（空格、制表符、换行等）或双引号（"），则必须用双引号包裹
	 * - 包裹时，字符串内部的所有双引号会被转义为 \"，防止破坏外层引号结构
	 * - 若无需引号（纯无空格、无引号的简单字符串），则原样返回
	 * - 输入为 null 时返回空字符串
	 * 
	 * 示例：
	 *   "hello"        → "hello"          （无需引号）
	 *   "hello world"  → "\"hello world\"" （含空格，需引号）
	 *   "a\"b"         → "\"a\\\"b\""     （含引号，需转义并包裹）
	 * 
	 * 注意：此方法仅处理最基础的命令行安全封装，不处理反斜杠、换行符等复杂转义。对于更复杂场景，建议使用 ProcessBuilder 传递 List<String>。
	 * 
	 * @param s 待处理的字符串，可能包含空格或引号
	 * @return 适当引号包裹并转义后的字符串，或原字符串（若无需包裹），null 输入返回空字符串
	 */
	public static String quoteIfNeeded(String s) {
		if (s == null) return "";
		boolean needs = false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isWhitespace(c) || c == '"') {
				needs = true;
				break;
			}
		}
		if (!needs) return s;
		return "\"" + s.replace("\"", "\\\"") + "\"";
	}
	
	
	public static List<String> splitCmdArgs(String cmd) {
		String s = cmd == null ? "" : cmd;
		List<String> tokens = new ArrayList<>();
		StringBuilder buf = new StringBuilder();
		boolean inQuotes = false;
		boolean escape = false;
		
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (escape) {
				buf.append(ch);
				escape = false;
				continue;
			}
			if (ch == '\\') {
				escape = true;
				continue;
			}
			if (ch == '"') {
				inQuotes = !inQuotes;
				continue;
			}
			if (!inQuotes && Character.isWhitespace(ch)) {
				if (buf.length() > 0) {
					tokens.add(buf.toString());
					buf.setLength(0);
				}
				continue;
			}
			buf.append(ch);
		}
		if (buf.length() > 0) tokens.add(buf.toString());
		return tokens;
	}

	/**
	 * 	取出URL中的参数。
	 * @param url
	 * @return
	 */
	public static Map<String, String> getQueryParam(String url) {
		if (url == null || url.isEmpty()) {
			return new HashMap<>();
		}
		try {
			// 解析 URL
			URI uri = new URI(url);
			String query = uri.getQuery(); // 获取 ? 后面的部分
			if (query == null || query.isEmpty()) {
				return new HashMap<>();
			}
			Map<String, String> params = new HashMap<>();
			for (String pair : query.split("&")) {
				int idx = pair.indexOf("=");
				if (idx > 0) {
					// 有 "="，拆分为 key 和 value
					String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
					String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
					params.put(key, value);
				} else if (idx == 0) {
					// 以 "=" 开头，如 "=value"（罕见），忽略 key
					String value = URLDecoder.decode(pair.substring(1), StandardCharsets.UTF_8);
					params.put("", value); // 可选：可跳过或记录为无名参数
				} else {
					// 没有 "="，只有 key，如 "a"
					String key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
					params.put(key, ""); // 值设为空字符串
				}
			}
			return params;
		} catch (Exception e) {
			// URL 格式错误、编码失败等，返回空 Map（可根据需求改为抛异常）
			return new HashMap<>();
		}
	}
	
	/**
	 * 	创建etag
	 * @param content
	 * @return
	 */
	public static String buildEtag(byte[] content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(content == null ? new byte[0] : content);
			StringBuilder sb = new StringBuilder(hash.length * 2 + 2);
			sb.append('"');
			for (byte b : hash) {
				sb.append(String.format("%02x", b));
			}
			sb.append('"');
			return sb.toString();
		} catch (Exception e) {
			return "\"" + UUID.randomUUID().toString().replace("-", "") + "\"";
		}
	}
	
	public static String readArchitecture(GGUFModel model) {
		if (model == null) {
			return null;
		}
		GGUFMetaData primary = model.getPrimaryModel();
		if (primary == null) {
			return null;
		}
		try {
			return primary.getStringValue("general.architecture");
		} catch (Exception ignore) {
			return null;
		}
	}
	
	
	public static void handleThinking(JsonObject requestJson) {
		boolean needInjection = false;
		boolean enableValueStr = true;
		
		// 1. 检查传统字段 "enable_thinking"
		if (requestJson.has("enable_thinking")) {
			try {
				JsonElement et = requestJson.get("enable_thinking");
				if (et != null && !et.isJsonNull() && et.isJsonPrimitive()) {
					if (et.getAsJsonPrimitive().isBoolean()) {
						needInjection = true;
						enableValueStr = et.getAsBoolean();
					} else if (et.getAsJsonPrimitive().isString()) {
						needInjection = true;
						enableValueStr = Boolean.parseBoolean(et.getAsString().trim());
					}
				}
			} catch (Exception ignore) {
			}
		}
		// 2. 检查额外的"thinking":{"type":"disabled"}
		if (!needInjection) {
			if (requestJson.has("thinking")) {
				try {
					JsonElement thinkingEl = requestJson.get("thinking");
					if (thinkingEl != null && !thinkingEl.isJsonNull() && thinkingEl.isJsonObject()) {
						JsonObject thinkingObj = thinkingEl.getAsJsonObject();
						String typeVal = "";
						if (thinkingObj.has("type")) {
							JsonElement typeEl = thinkingObj.get("type");
							if (typeEl != null && !typeEl.isJsonNull() && typeEl.isJsonPrimitive()
									&& typeEl.getAsJsonPrimitive().isString()) {
								typeVal = typeEl.getAsString().toLowerCase().trim();
							}
						}
						// 核心判断：如果 type 是 "disabled"，视为需要处理（通常映射为 enable_thinking: false）
						if ("disabled".equals(typeVal.toLowerCase())) {
							needInjection = true;
							enableValueStr = false;
						}
					}
				} catch (Exception ignore) {
				}
			}
		}
		// 3. 检查来自/v1/messages的参数：thinking_budget_tokens
		if(!needInjection) {
			if(requestJson.has("thinking_budget_tokens")) {
				try {
					JsonElement thinkingEl = requestJson.get("thinking_budget_tokens");
					if (thinkingEl != null && !thinkingEl.isJsonNull() && thinkingEl.isJsonObject()) {
						Integer thinkingObj = thinkingEl.getAsInt();
						if(thinkingObj != null) {
							needInjection = true;
							if(thinkingObj > 0) {
								enableValueStr = true;	
							}
						}
					}
				} catch (Exception ignore) {
				}
			}else {
				needInjection = true;
				enableValueStr = false;	
			}
		}
		
		if (needInjection) {
			// 拼接一个chat_template_kwargs进去： "chat_template_kwargs" : {"enable_thinking":
			// false},
			// 分两种情况
			// 没有这个模板注入，那就直接新建一个丢进去
			JsonObject chatTemplateKwargs = null;
			if (requestJson.has("chat_template_kwargs")) {
				try {
					JsonElement kwargsEl = requestJson.get("chat_template_kwargs");
					if (kwargsEl != null && !kwargsEl.isJsonNull()) {
						if (kwargsEl.isJsonObject()) {
							chatTemplateKwargs = kwargsEl.getAsJsonObject();
						} else if (kwargsEl.isJsonPrimitive() && kwargsEl.getAsJsonPrimitive().isString()) {
							chatTemplateKwargs = JsonUtil.tryParseObject(kwargsEl.getAsString());
						}
					}
				} catch (Exception ignore) {
				}
			}
			if (chatTemplateKwargs == null) {
				chatTemplateKwargs = new JsonObject();
			}
			chatTemplateKwargs.addProperty("enable_thinking", enableValueStr);
			requestJson.add("chat_template_kwargs", chatTemplateKwargs);
		}
	}
	
	
	
	public static String readQuantization(GGUFModel model) {
		if (model == null) {
			return null;
		}
		GGUFMetaData primary = model.getPrimaryModel();
		if (primary == null) {
			return null;
		}
		try {
			return primary.getQuantizationType();
		} catch (Exception ignore) {
			return null;
		}
	}
}
