package org.mark.llamacpp.server.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 	执行命令返回结果
 */
public class CommandLineRunner {

	/**
	 * 命令执行结果
	 */
	public static class CommandResult {
		/** 标准输出内容 */
		private final String output;
		/** 错误信息（失败时） */
		private final String error;
		/** 退出码 */
		private final Integer exitCode;

		public CommandResult(String output, String error, Integer exitCode) {
			this.output = output;
			this.error = error;
			this.exitCode = exitCode;
		}

		public String getOutput() {
			return output;
		}

		public String getError() {
			return error;
		}

		public Integer getExitCode() {
			return exitCode;
		}

		@Override
		public String toString() {
			return "CommandResult{" +
					"output='" + output + '\'' +
					", error='" + error + '\'' +
					", exitCode=" + exitCode +
					'}';
		}
	}

	/**
	 * 默认超时时间（秒）
	 */
	private static final int DEFAULT_TIMEOUT_SECONDS = 5;
	private static final long WINDOWS_ENV_CACHE_TTL_MS = 30_000L;
	private static volatile Map<String, String> windowsEnvCache;
	private static volatile long windowsEnvCacheAtMs;

	/**
	 * 执行命令行命令并获取返回结果（使用默认5秒超时）
	 *
	 * @param command 要执行的命令
	 * @return 执行结果
	 */
	public static CommandResult execute(String command) {
		return execute(command, DEFAULT_TIMEOUT_SECONDS);
	}

	/**
	 * 执行命令行命令并获取返回结果
	 *
	 * @param command 要执行的命令
	 * @param timeoutSeconds 超时时间（秒）
	 * @return 执行结果
	 */
	public static CommandResult execute(String command, int timeoutSeconds) {
		return executeInternal(splitCommandLineArgs(command).toArray(new String[0]), command, timeoutSeconds);
	}

	/**
	 * 执行命令行命令并获取返回结果（使用默认5秒超时）
	 *
	 * @param commandArray 命令数组
	 * @return 执行结果
	 */
	public static CommandResult execute(String[] commandArray) {
		return execute(commandArray, DEFAULT_TIMEOUT_SECONDS);
	}

	/**
	 * 执行命令行命令并获取返回结果
	 *
	 * @param commandArray 命令数组
	 * @param timeoutSeconds 超时时间（秒）
	 * @return 执行结果
	 */
	public static CommandResult execute(String[] commandArray, int timeoutSeconds) {
		return executeInternal(commandArray, null, timeoutSeconds);
	}

	/**
	 * 内部执行命令的方法
	 *
	 * @param commandArray 命令数组
	 * @param originalCommandLine 原始命令字符串（可为空，用于 Windows shell fallback）
	 * @param timeoutSeconds 超时时间（秒）
	 * @return 执行结果
	 */
	private static CommandResult executeInternal(String[] commandArray, String originalCommandLine, int timeoutSeconds) {
		if (commandArray == null || commandArray.length == 0) {
			return new CommandResult("", "命令不能为空", null);
		}

		Process process;
		try {
			process = startProcess(commandArray, originalCommandLine);
		} catch (IOException e) {
			return new CommandResult("", "启动进程失败: " + e.getMessage(), null);
		}

		try {
			process.getOutputStream().close();
		} catch (IOException ignored) {
		}

		// 读取标准输出
		StringBuilder outputBuilder = new StringBuilder();
		Thread outputThread = Thread.ofVirtual().start(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					outputBuilder.append(line).append(System.lineSeparator());
				}
			} catch (IOException e) {
				// 读取输出时发生错误
			}
		});

		// 读取错误输出
		StringBuilder errorBuilder = new StringBuilder();
		Thread errorThread = Thread.ofVirtual().start(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					errorBuilder.append(line).append(System.lineSeparator());
				}
			} catch (IOException e) {
				// 读取错误输出时发生错误
			}
		});

		try {
			boolean finished = waitFor(process, timeoutSeconds);
			if (!finished) {
				destroyProcessTree(process);
				waitFor(process, 2);
			}

			joinQuietly(outputThread, 2, TimeUnit.SECONDS);
			joinQuietly(errorThread, 2, TimeUnit.SECONDS);

			String output = outputBuilder.toString().trim();
			String error = errorBuilder.toString().trim();

			if (!finished) {
				String timeoutMsg = "命令执行超时（" + timeoutSeconds + "秒）";
				if (error == null || error.isBlank()) {
					error = timeoutMsg;
				} else {
					error = error + System.lineSeparator() + timeoutMsg;
				}
				return new CommandResult(output, error, null);
			}

			Integer exitCode = null;
			try {
				exitCode = process.exitValue();
			} catch (IllegalThreadStateException ignored) {
			}
			return new CommandResult(output, error, exitCode);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			destroyProcessTree(process);
			return new CommandResult("", "命令执行被中断: " + e.getMessage(), null);
		} finally {
			if (process.isAlive()) {
				destroyProcessTree(process);
			}
		}
	}

	private static Process startProcess(String[] commandArray, String originalCommandLine) throws IOException {
		List<String> cmd = new ArrayList<>(List.of(commandArray));
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.redirectErrorStream(false);
		applyFreshWindowsEnvironment(pb);
		String resolvedExe = resolveExecutablePath(cmd.getFirst(), pb.environment());
		if (resolvedExe != null && !resolvedExe.isBlank()) {
			cmd.set(0, resolvedExe);
			pb.command(cmd);
		}
		applyExecutableDirEnv(pb, pb.command().toArray(new String[0]));
		try {
			if (isWindows() && isWindowsShellScript(cmd.getFirst())) {
				String line = originalCommandLine;
				if (line == null || line.isBlank()) {
					line = toWindowsCmdCommandLine(cmd);
				}
				ProcessBuilder shellPb = new ProcessBuilder("cmd.exe", "/s", "/c", line);
				shellPb.redirectErrorStream(false);
				applyFreshWindowsEnvironment(shellPb);
				return shellPb.start();
			}
			return pb.start();
		} catch (IOException e) {
			if (!isWindows() || originalCommandLine == null || originalCommandLine.isBlank()) {
				throw e;
			}
			ProcessBuilder shellPb = new ProcessBuilder("cmd.exe", "/s", "/c", originalCommandLine);
			shellPb.redirectErrorStream(false);
			applyFreshWindowsEnvironment(shellPb);
			return shellPb.start();
		}
	}

	private static boolean waitFor(Process process, int timeoutSeconds) throws InterruptedException {
		if (timeoutSeconds <= 0) {
			process.waitFor();
			return true;
		}
		return process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
	}

	private static void joinQuietly(Thread t, long timeout, TimeUnit unit) throws InterruptedException {
		if (t == null) {
			return;
		}
		if (timeout <= 0) {
			t.join();
			return;
		}
		t.join(unit.toMillis(timeout));
	}

	private static void destroyProcessTree(Process process) {
		if (process == null) {
			return;
		}
		ProcessHandle handle = process.toHandle();
		List<ProcessHandle> descendants;
		try {
			descendants = handle.descendants().toList();
		} catch (Exception e) {
			descendants = List.of();
		}

		for (ProcessHandle ph : descendants) {
			try {
				ph.destroy();
			} catch (Exception ignored) {
			}
		}
		try {
			handle.destroy();
		} catch (Exception ignored) {
		}

		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}

		for (ProcessHandle ph : descendants) {
			try {
				if (ph.isAlive()) {
					ph.destroyForcibly();
				}
			} catch (Exception ignored) {
			}
		}
		try {
			if (handle.isAlive()) {
				handle.destroyForcibly();
			}
		} catch (Exception ignored) {
		}
	}

	private static void applyExecutableDirEnv(ProcessBuilder pb, String[] commandArray) {
		if (pb == null || commandArray == null || commandArray.length == 0) {
			return;
		}
		String exe = commandArray[0];
		if (exe == null || exe.isBlank()) {
			return;
		}
		File exeFile = new File(exe);
		File exeDir = exeFile.getParentFile();
		try {
			Path real = exeFile.toPath().toRealPath();
			if (real.getParent() != null) {
				exeDir = real.getParent().toFile();
			}
		} catch (Exception ignored) {
		}
		if (exeDir == null) {
			return;
		}

		pb.directory(exeDir);

		String osName = System.getProperty("os.name");
		String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
		Map<String, String> env = pb.environment();

		if (os.contains("win")) {
			String currentPath = env.get("PATH");
			String dir = exeDir.getAbsolutePath();
			if (currentPath == null || currentPath.isBlank()) {
				env.put("PATH", dir);
			} else if (!currentPath.contains(dir)) {
				env.put("PATH", dir + ";" + currentPath);
			}
			return;
		}

		String currentLdPath = env.get("LD_LIBRARY_PATH");
		String dir = exeDir.getAbsolutePath();
		StringBuilder newLdPath = new StringBuilder(dir);
		if (currentLdPath != null && !currentLdPath.isBlank() && !currentLdPath.contains(dir)) {
			newLdPath.append(":").append(currentLdPath);
		}

		// ROCm 7.2 库路径 - 支持多种可能的安装位置
		String[] rocmPaths = {
			"/opt/rocm-7.2.0/lib",
			"/opt/rocm-7.2.0/lib64",
			"/opt/rocm/lib",
			"/opt/rocm/lib64",
			"/usr/local/rocm/lib",
			"/usr/local/rocm/lib64",
			"/usr/local/lib64",
			"/usr/local/lib"
		};
		for (String rocmPath : rocmPaths) {
			if (!newLdPath.toString().contains(rocmPath)) {
				newLdPath.append(":").append(rocmPath);
			}
		}

		env.put("LD_LIBRARY_PATH", newLdPath.toString());

		if (os.contains("mac") || os.contains("darwin")) {
			String currentDyldPath = env.get("DYLD_LIBRARY_PATH");
			if (currentDyldPath == null || currentDyldPath.isBlank()) {
				env.put("DYLD_LIBRARY_PATH", dir);
			} else if (!currentDyldPath.contains(dir)) {
				env.put("DYLD_LIBRARY_PATH", dir + ":" + currentDyldPath);
			}

			String currentFallback = env.get("DYLD_FALLBACK_LIBRARY_PATH");
			if (currentFallback == null || currentFallback.isBlank()) {
				env.put("DYLD_FALLBACK_LIBRARY_PATH", dir);
			} else if (!currentFallback.contains(dir)) {
				env.put("DYLD_FALLBACK_LIBRARY_PATH", dir + ":" + currentFallback);
			}
		}
	}

	private static boolean isWindows() {
		String osName = System.getProperty("os.name");
		String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
		return os.contains("win");
	}

	private static void applyFreshWindowsEnvironment(ProcessBuilder pb) {
		if (pb == null || !isWindows()) {
			return;
		}
		Map<String, String> fresh = getFreshWindowsEnvironment();
		if (fresh.isEmpty()) {
			return;
		}
		Map<String, String> env = pb.environment();

		for (Map.Entry<String, String> e : fresh.entrySet()) {
			String k = e.getKey();
			String v = e.getValue();
			if (k == null || k.isBlank() || v == null) {
				continue;
			}
			String existingKey = findKeyIgnoreCase(env, k);
			if (existingKey == null) {
				env.put(k, v);
				continue;
			}
			String existingValue = env.get(existingKey);
			if (existingValue == null || existingValue.isBlank()) {
				env.put(existingKey, v);
			}
		}

		String freshPath = getIgnoreCase(fresh, "PATH");
		if (freshPath != null && !freshPath.isBlank()) {
			String currentPath = getIgnoreCase(env, "PATH");
			String merged = mergeWindowsPath(freshPath, currentPath);
			merged = expandWindowsVars(merged, env);
			putIgnoreCase(env, "PATH", merged);
		}
	}

	private static Map<String, String> getFreshWindowsEnvironment() {
		if (!isWindows()) {
			return Map.of();
		}
		long now = System.currentTimeMillis();
		Map<String, String> cached = windowsEnvCache;
		if (cached != null && (now - windowsEnvCacheAtMs) < WINDOWS_ENV_CACHE_TTL_MS) {
			return cached;
		}
		synchronized (CommandLineRunner.class) {
			now = System.currentTimeMillis();
			cached = windowsEnvCache;
			if (cached != null && (now - windowsEnvCacheAtMs) < WINDOWS_ENV_CACHE_TTL_MS) {
				return cached;
			}
			Map<String, String> loaded = loadWindowsEnvironmentViaCmdSet();
			if (loaded.isEmpty()) {
				loaded = loadWindowsEnvironmentViaPowerShell();
			}
			windowsEnvCache = loaded;
			windowsEnvCacheAtMs = now;
			return loaded;
		}
	}

	private static Map<String, String> loadWindowsEnvironmentViaCmdSet() {
		ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/u", "/s", "/c", "set");
		pb.redirectErrorStream(true);

		Process p;
		try {
			p = pb.start();
		} catch (IOException e) {
			return Map.of();
		}

		Map<String, String> out = new HashMap<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_16LE))) {
			String line;
			while ((line = reader.readLine()) != null) {
				int idx = line.indexOf('=');
				if (idx <= 0) {
					continue;
				}
				String k = line.substring(0, idx).trim();
				if (k.isEmpty() || k.startsWith("=") || k.indexOf(' ') >= 0) {
					continue;
				}
				String v = line.substring(idx + 1);
				out.put(k, v);
			}
		} catch (IOException ignored) {
			return Map.of();
		} finally {
			try {
				p.waitFor(2, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (p.isAlive()) {
				p.destroyForcibly();
			}
		}

		return out;
	}

	private static Map<String, String> loadWindowsEnvironmentViaPowerShell() {
		String script = "$OutputEncoding=[Console]::OutputEncoding=[Text.UTF8Encoding]::new();" +
				"$m=[Environment]::GetEnvironmentVariables('Machine');" +
				"$u=[Environment]::GetEnvironmentVariables('User');" +
				"foreach($k in $u.Keys){$m[$k]=$u[$k]};" +
				"$m.GetEnumerator()|ForEach-Object{[Console]::WriteLine($_.Key+'='+$_.Value)}";

		ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
		pb.redirectErrorStream(true);

		Process p;
		try {
			p = pb.start();
		} catch (IOException e) {
			return Map.of();
		}

		Map<String, String> out = new HashMap<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				int idx = line.indexOf('=');
				if (idx <= 0) {
					continue;
				}
				String k = line.substring(0, idx).trim();
				String v = line.substring(idx + 1);
				if (!k.isEmpty()) {
					out.put(k, v);
				}
			}
		} catch (IOException ignored) {
			return Map.of();
		}

		try {
			p.waitFor(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			if (p.isAlive()) {
				p.destroyForcibly();
			}
		}
		return out;
	}

	private static String mergeWindowsPath(String preferred, String fallback) {
		Set<String> seenLower = new LinkedHashSet<>();
		List<String> merged = new ArrayList<>();
		addWindowsPathEntries(merged, seenLower, preferred);
		addWindowsPathEntries(merged, seenLower, fallback);
		return String.join(";", merged);
	}

	private static void addWindowsPathEntries(List<String> out, Set<String> seenLower, String path) {
		if (path == null || path.isBlank()) {
			return;
		}
		String[] parts = path.split(";");
		for (String raw : parts) {
			if (raw == null) {
				continue;
			}
			String p = raw.trim();
			if (p.isEmpty()) {
				continue;
			}
			String key = p.toLowerCase(Locale.ROOT);
			if (seenLower.add(key)) {
				out.add(p);
			}
		}
	}

	private static String expandWindowsVars(String value, Map<String, String> env) {
		if (value == null || value.isEmpty() || env == null || env.isEmpty()) {
			return value;
		}
		String cur = value;
		for (int i = 0; i < 5; i++) {
			String next = expandWindowsVarsOnce(cur, env);
			if (next.equals(cur)) {
				return next;
			}
			cur = next;
		}
		return cur;
	}

	private static String expandWindowsVarsOnce(String value, Map<String, String> env) {
		int first = value.indexOf('%');
		if (first < 0) {
			return value;
		}
		StringBuilder sb = new StringBuilder(value.length());
		int i = 0;
		while (i < value.length()) {
			int s = value.indexOf('%', i);
			if (s < 0 || s + 1 >= value.length()) {
				sb.append(value, i, value.length());
				break;
			}
			int e = value.indexOf('%', s + 1);
			if (e < 0) {
				sb.append(value, i, value.length());
				break;
			}
			sb.append(value, i, s);
			String var = value.substring(s + 1, e);
			String repl = getIgnoreCase(env, var);
			if (repl == null) {
				sb.append('%').append(var).append('%');
			} else {
				sb.append(repl);
			}
			i = e + 1;
		}
		return sb.toString();
	}

	private static String resolveExecutablePath(String exe, Map<String, String> env) {
		if (exe == null || exe.isBlank()) {
			return null;
		}
		if (exe.contains("\\") || exe.contains("/") || exe.contains(":")) {
			File f = new File(exe);
			if (f.exists()) {
				return f.getAbsolutePath();
			}
			if (isWindows() && !hasExtension(exe)) {
				String ext = resolveByPathext(exe, env, null);
				if (ext != null) {
					return ext;
				}
			}
			return null;
		}

		String path = env == null ? null : getIgnoreCase(env, "PATH");
		if (path == null || path.isBlank()) {
			return null;
		}
		String[] dirs = path.split(";");
		for (String dirRaw : dirs) {
			if (dirRaw == null) {
				continue;
			}
			String dir = dirRaw.trim();
			if (dir.isEmpty()) {
				continue;
			}
			String candidate = resolveByPathext(exe, env, dir);
			if (candidate != null) {
				return candidate;
			}
		}
		return null;
	}

	private static String resolveByPathext(String exe, Map<String, String> env, String dir) {
		String pathext = env == null ? null : getIgnoreCase(env, "PATHEXT");
		if (pathext == null || pathext.isBlank()) {
			pathext = ".COM;.EXE;.BAT;.CMD";
		}
		List<String> exts = new ArrayList<>();
		for (String raw : pathext.split(";")) {
			if (raw == null) {
				continue;
			}
			String ext = raw.trim();
			if (!ext.isEmpty()) {
				exts.add(ext);
			}
		}

		if (hasExtension(exe)) {
			File f = dir == null ? new File(exe) : new File(dir, exe);
			if (f.exists()) {
				return f.getAbsolutePath();
			}
			return null;
		}

		for (String ext : exts) {
			File f = dir == null ? new File(exe + ext) : new File(dir, exe + ext);
			if (f.exists()) {
				return f.getAbsolutePath();
			}
		}
		return null;
	}

	private static boolean hasExtension(String exe) {
		int slash = Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\'));
		int dot = exe.lastIndexOf('.');
		return dot > slash;
	}

	private static boolean isWindowsShellScript(String exePath) {
		if (exePath == null) {
			return false;
		}
		String lower = exePath.toLowerCase(Locale.ROOT);
		return lower.endsWith(".cmd") || lower.endsWith(".bat");
	}

	private static String toWindowsCmdCommandLine(List<String> args) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < args.size(); i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(quoteWindowsCmdArg(args.get(i)));
		}
		return sb.toString();
	}

	private static String quoteWindowsCmdArg(String arg) {
		if (arg == null || arg.isEmpty()) {
			return "\"\"";
		}
		boolean need = false;
		for (int i = 0; i < arg.length(); i++) {
			char c = arg.charAt(i);
			if (Character.isWhitespace(c) || c == '"' || c == '&' || c == '|' || c == '<' || c == '>' || c == '^') {
				need = true;
				break;
			}
		}
		if (!need) {
			return arg;
		}
		return "\"" + arg.replace("\"", "\"\"") + "\"";
	}

	private static String findKeyIgnoreCase(Map<String, String> env, String key) {
		if (env == null || key == null) {
			return null;
		}
		for (String k : env.keySet()) {
			if (k != null && k.equalsIgnoreCase(key)) {
				return k;
			}
		}
		return null;
	}

	private static String getIgnoreCase(Map<String, String> env, String key) {
		String k = findKeyIgnoreCase(env, key);
		return k == null ? null : env.get(k);
	}

	private static void putIgnoreCase(Map<String, String> env, String key, String value) {
		if (env == null || key == null) {
			return;
		}
		String k = findKeyIgnoreCase(env, key);
		if (k == null) {
			env.put(key, value);
		} else {
			env.put(k, value);
		}
	}

	private static List<String> splitCommandLineArgs(String commandLine) {
		List<String> out = new ArrayList<>();
		if (commandLine == null) {
			return out;
		}
		String s = commandLine.trim();
		if (s.isEmpty()) {
			return out;
		}

		StringBuilder cur = new StringBuilder();
		boolean inSingle = false;
		boolean inDouble = false;

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);

			if (inDouble && c == '\\') {
				if (i + 1 < s.length()) {
					char n = s.charAt(i + 1);
					if (n == '"') {
						cur.append(n);
						i++;
						continue;
					}
				}
				cur.append(c);
				continue;
			}
			if (inSingle && c == '\\') {
				if (i + 1 < s.length()) {
					char n = s.charAt(i + 1);
					if (n == '\'') {
						cur.append(n);
						i++;
						continue;
					}
				}
				cur.append(c);
				continue;
			}

			if (c == '"' && !inSingle) {
				inDouble = !inDouble;
				continue;
			}
			if (c == '\'' && !inDouble) {
				inSingle = !inSingle;
				continue;
			}

			if (!inSingle && !inDouble && Character.isWhitespace(c)) {
				if (cur.length() > 0) {
					out.add(cur.toString());
					cur.setLength(0);
				}
				continue;
			}

			cur.append(c);
		}
		if (cur.length() > 0) {
			out.add(cur.toString());
		}
		return out;
	}

}
