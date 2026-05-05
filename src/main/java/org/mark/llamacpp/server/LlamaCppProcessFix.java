package org.mark.llamacpp.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 	llamacpp进程 — Fix 版本。
 * 	对比 {@link LlamaCppProcess} 修复了以下问题：
 * 	
 * 	1. reader 线程改用虚拟线程 (Thread.ofVirtual())，不占用平台线程
 * 	2. stop() 中显式关闭 stdwriter + 子进程 stdout/stderr 流，避免 SIGPIPE + 让 readLine() 立即返回
 * 	3. outputHandler.accept() 包裹 try-catch，防止异常穿透杀死 reader 线程
 * 	4. error 线程和 output 线程的异常处理对称，不再吞没异常
 * 	5. send() 写入后 flush()，确保数据及时送达
 * 	6. 提取 closeQuietly() / joinThread() 辅助方法，消除重复样板
 */
public class LlamaCppProcessFix {

	private static final Logger logger = LoggerFactory.getLogger(LlamaCppProcessFix.class);
	private static final Logger RAW_PROCESS_LOGGER = LoggerFactory.getLogger("LLAMA_CPP_RAW");

	private final String name;
	private final String cmd;
	private final String llamaBinPath;
	private long pid;
	private Process process;
	private Thread outputThread;
	private Thread errorThread;
	private final AtomicBoolean isRunning = new AtomicBoolean(false);
	private Consumer<String> outputHandler;
	private BufferedWriter stdwriter;
	private int ctxSize;
	private int slotNum;

	public LlamaCppProcessFix(String name, String cmd, String llamaBinPath) {
		this.name = name;
		this.cmd = cmd;
		this.llamaBinPath = llamaBinPath;
	}

	public String getLlamaBinPath() {
		return this.llamaBinPath;
	}

	public void setOutputHandler(Consumer<String> outputHandler) {
		this.outputHandler = outputHandler;
	}

	public void setCtxSize(int ctxSize) {
		this.ctxSize = ctxSize;
	}

	public int getCtxSize() {
		return this.ctxSize;
	}

	public void setSlotNum(int slotNum) {
		this.slotNum = slotNum;
	}

	public int getSlotNum() {
		return this.slotNum;
	}

	/**
	 * 	写入输入内容
	 */
	public synchronized void send(String cmd) {
		if (this.stdwriter == null) return;
		try {
			this.stdwriter.write(cmd);
			this.stdwriter.flush();
		} catch (IOException e) {
			logger.warn("写入进程 stdin 失败: {}", e.getMessage());
		}
	}

	/**
	 * 	异步启动进程
	 * @return 是否启动成功
	 */
	public synchronized boolean start() {
		if (isRunning.get()) {
			return false;
		}

		try {
			List<String> args = splitCommandLineArgs(cmd);
			ProcessBuilder pb = new ProcessBuilder(args);

			Map<String, String> env = pb.environment();
			String existingLdPath = env.get("LD_LIBRARY_PATH");

			StringBuilder ldPathBuilder = new StringBuilder();
			if (this.llamaBinPath != null && !this.llamaBinPath.isEmpty()) {
				ldPathBuilder.append(this.llamaBinPath);
			}

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
				if (ldPathBuilder.length() > 0) {
				ldPathBuilder.append(":");
				}
				ldPathBuilder.append(rocmPath);
			}

			if (existingLdPath != null && !existingLdPath.isEmpty()) {
				if (ldPathBuilder.length() > 0) {
					ldPathBuilder.append(":");
				}
				ldPathBuilder.append(existingLdPath);
			}

			env.put("LD_LIBRARY_PATH", ldPathBuilder.toString());

			this.process = pb.start();
			logger.info("llama-server 进程已启动");

			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			try {
				this.pid = this.process.pid();
				this.stdwriter = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream(), StandardCharsets.UTF_8));
			} catch (Exception e) {
				logger.error("获取进程 PID 或输出流失败", e);
				this.pid = -1;
			}

			this.isRunning.set(true);
			this.startOutputReaders();
			return true;

		} catch (IOException e) {
			logger.error("启动 llama-server 失败: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * 	停止进程 — Fix 版本。
	 * 	
	 * 	改进点：
	 * 	- 先关闭 stdwriter，防止 SIGPIPE
	 * 	- 显式关闭子进程的 stdout/stderr 流，让 reader 线程的 readLine() 立即退出
	 * 	- destroyForcibly 后追加 waitFor(1) 确保进程真正回收
	 */
	public synchronized boolean stop() {
		if (!this.isRunning.getAndSet(false)) {
			return false;
		}

		// 1. 关闭 stdin writer — 防止向已死进程写入时触发 SIGPIPE
		closeQuietly(this.stdwriter);
		this.stdwriter = null;

		if (this.process != null) {
			// 2. 主动关闭子进程的输出流（即 Java 端的输入流），
			//    让 reader 线程的 readLine() 立即收到 EOF / IOException，而非永久阻塞
			closeQuietly(this.process.getInputStream());
			closeQuietly(this.process.getErrorStream());

			// 3. 优雅终止
			this.process.destroy();

			try {
				if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
					this.process.destroyForcibly();
					this.process.waitFor(1, TimeUnit.SECONDS);
				}
			} catch (InterruptedException e) {
				this.process.destroyForcibly();
				Thread.currentThread().interrupt();
			}
		}

		// 4. 等待 reader 线程（流已关闭，readLine 应快速返回）
		joinThread(this.outputThread, 2000);
		joinThread(this.errorThread, 2000);

		return true;
	}

	// ========================================================================
	// Reader 线程 — 用虚拟线程
	// ========================================================================

	private void startOutputReaders() {
		Consumer<String> safeHandler = line -> {
			if (this.outputHandler != null) {
				try {
					this.outputHandler.accept(line);
				} catch (Exception e) {
					logger.error("outputHandler 抛出异常: {}", e.getMessage());
				}
			}
			if (!line.contains("update_slots") && !line.contains("log_server_r")) {
				RAW_PROCESS_LOGGER.info(line);
			}
		};

		this.outputThread = Thread.ofVirtual().name("llama-out-" + name).start(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.process.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null && this.isRunning.get()) {
					safeHandler.accept(line);
				}
			} catch (IOException e) {
				// 流关闭导致的异常，只在真正运行中时警告
				if (this.isRunning.get()) {
					logger.warn("读取进程输出流时发生错误: {}", e.getMessage());
				}
			}
		});

		this.errorThread = Thread.ofVirtual().name("llama-err-" + name).start(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.process.getErrorStream()))) {
				String line;
				while ((line = reader.readLine()) != null && this.isRunning.get()) {
					safeHandler.accept(line);
				}
			} catch (IOException e) {
				if (this.isRunning.get()) {
					logger.warn("读取进程错误流时发生错误: {}", e.getMessage());
				}
			}
		});
	}

	// ========================================================================
	// Getters
	// ========================================================================

	public String getName() {
		return this.name;
	}

	public String getCmd() {
		return this.cmd;
	}

	public long getPid() {
		return this.pid;
	}

	public boolean isRunning() {
		return this.isRunning.get() && this.process != null && this.process.isAlive();
	}

	public Process getProcess() {
		return this.process;
	}

	public Integer getExitCode() {
		if (this.process != null && !this.process.isAlive()) {
			return this.process.exitValue();
		}
		return null;
	}

	// ========================================================================
	// 静态辅助
	// ========================================================================

	private static void closeQuietly(java.io.Closeable closeable) {
		if (closeable != null) {
			try {
				closeable.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static void joinThread(Thread thread, long timeoutMillis) {
		if (thread == null) return;
		try {
			thread.join(timeoutMillis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
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
		boolean allowSingle = !isWindows();
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
			if (allowSingle && inSingle && c == '\\') {
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
			if (allowSingle && c == '\'' && !inDouble) {
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

	private static boolean isWindows() {
		String os = System.getProperty("os.name");
		return os != null && os.toLowerCase(Locale.ROOT).contains("win");
	}
}
