package org.mark.llamacpp.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * 	llamacpp进程
 */
public class LlamaCppProcess {
	
	/**
	 * 	日志打印机
	 */
	private static final Logger logger = LoggerFactory.getLogger(LlamaCppProcess.class);
	
	
	/**
	 * 	这个进程的名字。是唯一的。
	 */
	private final String name;
	
	/**
	 * 	这个进程的启动命令。参考：/home/mark/llama.cpp-master/llama-server -m path -fa 1 -c 65536
	 */
	private final String cmd;

	/**
	 * 	启动该进程后获得的pid号。
	 */
	private long pid;
	
	/**
	 * 	进程对象
	 */
	private Process process;
	
	/**
	 * 	异步执行线程对象
	 */
	private Thread outputThread;
	
	/**
	 * 	错误输出线程对象
	 */
	private Thread errorThread;
	
	/**
	 * 	进程是否正在运行
	 */
	private final AtomicBoolean isRunning = new AtomicBoolean(false);
	
	/**
	 * 	输出处理器
	 */
	private Consumer<String> outputHandler;
	
	/**
	 * 	程序启动后的输入流
	 */
	private BufferedWriter stdwriter;
	
	/**
	 * 	运行时设置的上下文。
	 */
	private int ctxSize;
	
	/**
	 * 	llamacpp进程的路径，没啥用途，主要为了定位。
	 */
	private final String llamaBinPath;
	
	/**
	 * 	构造器。
	 * @param name 进程名称
	 * @param cmd 启动命令
	 * @param llamaBinPath llamacpp路径
	 */
	public LlamaCppProcess(String name, String cmd, String llamaBinPath) {
		this.name = name;
		this.cmd = cmd;
		this.llamaBinPath = llamaBinPath;
	}
	
	/**
	 * 	获取llamacpp路径
	 * @return
	 */
	public String getLlamaBinPath() {
		return this.llamaBinPath;
	}
	
	/**
	 * 设置输出处理器
	 * @param outputHandler 输出处理器
	 */
	public void setOutputHandler(Consumer<String> outputHandler) {
		this.outputHandler = outputHandler;
	}
	
	/**
	 * 	在模型加载成功后调用。
	 * @param ctxSize
	 */
	public void setCtxSize(int ctxSize) {
		this.ctxSize = ctxSize;
	}
	
	/**
	 * 	获取模型加载后实际的上下文长度。
	 * @return
	 */
	public int getCtxSize() {
		return this.ctxSize;
	}
	
	/**
	 * 	写入输入内容
	 * @param cmd
	 */
	public synchronized void send(String cmd) {
		try {
			if(this.stdwriter != null) {
				this.stdwriter.write(cmd);
			}
		}catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * 异步启动进程
	 * @return 是否启动成功
	 */
	public synchronized boolean start() {
		if (isRunning.get()) {
			return false;
		}

		try {
			// 使用 ProcessBuilder 启动进程，可以继承环境变量并添加新的路径
			List<String> args = splitCommandLineArgs(cmd);
			ProcessBuilder pb = new ProcessBuilder(args);

			// 获取并修改环境变量
			Map<String, String> env = pb.environment();

			// 保留原有的 LD_LIBRARY_PATH
			String existingLdPath = env.get("LD_LIBRARY_PATH");

			// 追加 llama-server 目录和 ROCm 库路径
			StringBuilder ldPathBuilder = new StringBuilder();
			if (this.llamaBinPath != null && !this.llamaBinPath.isEmpty()) {
				ldPathBuilder.append(this.llamaBinPath);
			}

			// ROCm 7.2 库路径
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

			// 追加原有路径
			if (existingLdPath != null && !existingLdPath.isEmpty()) {
				if (ldPathBuilder.length() > 0) {
					ldPathBuilder.append(":");
				}
				ldPathBuilder.append(existingLdPath);
			}

			env.put("LD_LIBRARY_PATH", ldPathBuilder.toString());

			this.process = pb.start();
			logger.info("llama-server 进程已启动");

			// 等待进程初始化
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				// ignore
			}

			// 获取PID (Java 9+ 提供了getPid方法)
			// 获取输入流
			try {
				this.pid = this.process.pid();
				this.stdwriter = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream(), StandardCharsets.UTF_8));
			} catch (Exception e) {
				e.printStackTrace();
				// 如果获取不到PID，使用一个默认值
				this.pid = -1;
			}

			this.isRunning.set(true);

			// 启动输出读取线程
			this.startOutputReaders();

			return true;
		} catch (IOException e) {
			logger.error("启动 llama-server 失败: {}", e.getMessage());
			e.printStackTrace();
			return false;
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

	private static boolean containsPathEntry(String pathList, String entry, boolean windows) {
		if (pathList == null || pathList.isEmpty() || entry == null || entry.isEmpty()) {
			return false;
		}
		String[] parts = pathList.split(windows ? ";" : ":");
		for (String p : parts) {
			String v = p == null ? "" : p.trim();
			if (windows) {
				if (v.equalsIgnoreCase(entry)) {
					return true;
				}
			} else if (v.equals(entry)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * 启动输出读取线程
	 */
	private void startOutputReaders() {
		// 标准输出读取线程
		this.outputThread = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.process.getInputStream()))) {
				String line;
				while ((line = reader.readLine()) != null && this.isRunning.get()) {
					// 将输出的内容转给处理器
					if (this.outputHandler != null) {
						this.outputHandler.accept(line);
					}
					if(!line.contains("update_slots") && !line.contains("log_server_r")) {
						logger.info(line);
					}
				}
			} catch (IOException e) {
				if (this.isRunning.get() && this.outputThread != null) {
					this.outputHandler.accept("读取输出时发生错误: " + e.getMessage());
				}
			}
		});
		this.outputThread.setDaemon(true);
		this.outputThread.start();

		// 错误输出读取线程 - llama-server 所有输出都在 stderr
		this.errorThread = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.process.getErrorStream()))) {
				String line;
				while ((line = reader.readLine()) != null && this.isRunning.get()) {
					// 将错误输出也传递给处理器
					if (this.outputHandler != null) {
						this.outputHandler.accept(line);
					}
					if(!line.contains("update_slots") && !line.contains("log_server_r")) {
						logger.info(line);
					}
				}
			} catch (IOException e) {
				// 忽略错误
			}
		});
		this.errorThread.setDaemon(true);
		this.errorThread.start();
	}
	
	
	
	/**
	 * 停止进程
	 * @return 是否停止成功
	 */
	public synchronized boolean stop() {
		if (!this.isRunning.get()) {
			return false;
		}
		
		this.isRunning.set(false);
		
		if (this.process != null) {
			this.process.destroy();
			
			try {
				// 等待进程结束
				if (!this.process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
					this.process.destroyForcibly();
				}
			} catch (InterruptedException e) {
				this.process.destroyForcibly();
				Thread.currentThread().interrupt();
			}
		}
		
		// 等待输出线程结束
		if (this.outputThread != null) {
			try {
				this.outputThread.interrupt();
				this.outputThread.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		
		if (this.errorThread != null) {
			try {
				this.errorThread.interrupt();
				this.errorThread.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		
		return true;
	}
	
	/**
	 * 获取进程名称
	 * @return 进程名称
	 */
	public String getName() {
		return this.name;
	}
	
	/**
	 * 获取启动命令
	 * @return 启动命令
	 */
	public String getCmd() {
		return this.cmd;
	}
	
	/**
	 * 获取进程PID
	 * @return 进程PID，如果获取失败返回-1
	 */
	public long getPid() {
		return this.pid;
	}
	
	/**
	 * 检查进程是否正在运行
	 * @return 是否正在运行
	 */
	public boolean isRunning() {
		return this.isRunning.get() && this.process != null && this.process.isAlive();
	}
	
	/**
	 * 	获取进程
	 * @return
	 */
	public Process getProcess() {
		return this.process;
	}
	
	/**
	 * 获取进程退出码
	 * @return 进程退出码，如果进程仍在运行返回null
	 */
	public Integer getExitCode() {
		if (this.process != null && !this.process.isAlive()) {
			return this.process.exitValue();
		}
		return null;
	}
}
