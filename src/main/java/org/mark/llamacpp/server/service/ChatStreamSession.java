package org.mark.llamacpp.server.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.io.BoundedQueueInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;

public class ChatStreamSession {

	private static final Logger logger = LoggerFactory.getLogger(ChatStreamSession.class);

	private static final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

	private static final int INPUT_QUEUE_CAPACITY = 32;
	private static final int MAX_SMALL_FIELD_BYTES = 1024 * 1024;
	private static final int MAX_BUFFERED_FIELD_BYTES = 4 * 1024 * 1024;
	private static final int DEFERRED_MEMORY_LIMIT = 1024 * 1024;

	private final ChannelHandlerContext ctx;
	private final OpenAIService openAIService;
	private final HttpMethod method;
	private final Map<String, String> headers;
	private final BoundedQueueInputStream requestBodyStream = new BoundedQueueInputStream(INPUT_QUEUE_CAPACITY);
	private final ChatRequestStreamingTransformer transformer =
			new ChatRequestStreamingTransformer(MAX_SMALL_FIELD_BYTES, MAX_BUFFERED_FIELD_BYTES);
	private final DeferredConnectionOutputStream deferredOutput = new DeferredConnectionOutputStream(DEFERRED_MEMORY_LIMIT);
	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AtomicBoolean completed = new AtomicBoolean(false);
	private final AtomicBoolean cancelled = new AtomicBoolean(false);

	private volatile HttpURLConnection connection;
	private volatile boolean receivedBody;

	public ChatStreamSession(ChannelHandlerContext ctx, OpenAIService openAIService, HttpMethod method, Map<String, String> headers) {
		this.ctx = ctx;
		this.openAIService = openAIService;
		this.method = method;
		this.headers = headers;
	}

	public void start() {
		if (started.compareAndSet(false, true)) {
			worker.execute(this::run);
		}
	}

	public void offer(ByteBuf content) throws IOException {
		if (content == null || !content.isReadable()) {
			return;
		}
		byte[] bytes = new byte[content.readableBytes()];
		content.getBytes(content.readerIndex(), bytes);
		receivedBody = true;
		requestBodyStream.offer(bytes);
	}

	public void complete() {
		if (completed.compareAndSet(false, true)) {
			requestBodyStream.complete();
		}
	}

	public void cancel() {
		if (cancelled.compareAndSet(false, true)) {
			requestBodyStream.fail(new IOException("client disconnected"));
			if (connection != null) {
				connection.disconnect();
			}
			try {
				deferredOutput.close();
			} catch (IOException e) {
			}
		}
	}

	private void run() {
		try {
			if (method != HttpMethod.POST) {
				openAIService.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			ChatRequestStreamingTransformer.TransformResult result = transformer.transform(
					requestBodyStream,
					deferredOutput,
					this::openConnectionForModel);

			if (!receivedBody) {
				openAIService.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "messages");
				return;
			}

			openConnectionForModel(result.getModelName());
			deferredOutput.close();
			if (connection == null) {
				throw new IOException("llama.cpp connection was not created");
			}

			int responseCode = connection.getResponseCode();
			openAIService.handleProxyResponse(ctx, connection, responseCode, result.isStream(), result.getModelName());
		} catch (ChatRequestStreamingTransformer.StreamingRequestException e) {
			if (!cancelled.get()) {
				openAIService.sendOpenAIErrorResponseWithCleanup(ctx, e.getHttpStatus(), null, e.getMessage(), e.getParam());
			}
		} catch (IOException e) {
			if (cancelled.get()) {
				logger.info("聊天流式会话已取消: {}", e.getMessage());
				return;
			}
			if (!receivedBody) {
				openAIService.sendOpenAIErrorResponseWithCleanup(ctx, 400, null, "Request body is empty", "messages");
				return;
			}
			logger.info("处理聊天流式请求时发生错误", e);
			openAIService.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		} catch (Exception e) {
			if (cancelled.get()) {
				logger.info("聊天流式会话已取消: {}", e.getMessage());
				return;
			}
			logger.info("处理聊天流式请求时发生错误", e);
			openAIService.sendOpenAIErrorResponseWithCleanup(ctx, 500, null, e.getMessage(), null);
		} finally {
			try {
				deferredOutput.close();
			} catch (IOException e) {
			}
			openAIService.cleanupTrackedConnection(ctx, connection);
		}
	}

	private synchronized void openConnectionForModel(String modelName) throws IOException {
		if (connection != null) {
			return;
		}
		if (modelName == null) {
			return;
		}

		LlamaServerManager manager = LlamaServerManager.getInstance();
		if (!manager.getLoadedProcesses().containsKey(modelName)) {
			throw new ChatRequestStreamingTransformer.StreamingRequestException(404, "Model not found: " + modelName, "model");
		}

		Integer modelPort = manager.getModelPort(modelName);
		if (modelPort == null) {
			throw new ChatRequestStreamingTransformer.StreamingRequestException(500, "Model port not found: " + modelName, null);
		}

		String targetUrl = String.format("http://localhost:%d/v1/chat/completions", modelPort.intValue());
		connection = openAIService.openTrackedConnection(ctx, targetUrl, method, headers, true);
		deferredOutput.attach(connection.getOutputStream());
		logger.info("聊天流式请求已连接到 llama.cpp: {}", targetUrl);
	}

	private static class DeferredConnectionOutputStream extends OutputStream {

		private final int memoryLimit;
		private final ByteArrayOutputStream memoryBuffer = new ByteArrayOutputStream();
		private OutputStream target;
		private OutputStream spoolOutput;
		private Path spoolFile;
		private boolean closed;

		private DeferredConnectionOutputStream(int memoryLimit) {
			this.memoryLimit = memoryLimit;
		}

		public synchronized void attach(OutputStream outputStream) throws IOException {
			if (closed) {
				throw new IOException("stream already closed");
			}
			if (target != null) {
				return;
			}
			target = outputStream;
			if (spoolOutput != null) {
				spoolOutput.flush();
				spoolOutput.close();
				spoolOutput = null;
				Files.copy(spoolFile, target);
				Files.deleteIfExists(spoolFile);
				spoolFile = null;
			} else if (memoryBuffer.size() > 0) {
				memoryBuffer.writeTo(target);
				memoryBuffer.reset();
			}
			target.flush();
		}

		@Override
		public synchronized void write(int b) throws IOException {
			write(new byte[] { (byte) b }, 0, 1);
		}

		@Override
		public synchronized void write(byte[] b, int off, int len) throws IOException {
			if (closed) {
				throw new IOException("stream already closed");
			}
			if (len <= 0) {
				return;
			}
			if (target != null) {
				target.write(b, off, len);
				return;
			}
			if (spoolOutput != null) {
				spoolOutput.write(b, off, len);
				return;
			}
			if (memoryBuffer.size() + len <= memoryLimit) {
				memoryBuffer.write(b, off, len);
				return;
			}
			spoolToFile();
			spoolOutput.write(b, off, len);
		}

		@Override
		public synchronized void flush() throws IOException {
			if (target != null) {
				target.flush();
				return;
			}
			if (spoolOutput != null) {
				spoolOutput.flush();
			}
		}

		@Override
		public synchronized void close() throws IOException {
			if (closed) {
				return;
			}
			closed = true;
			IOException failure = null;
			try {
				if (spoolOutput != null) {
					spoolOutput.close();
				}
			} catch (IOException e) {
				failure = e;
			}
			try {
				if (target != null) {
					target.flush();
					target.close();
				}
			} catch (IOException e) {
				if (failure == null) {
					failure = e;
				}
			} finally {
				if (spoolFile != null) {
					Files.deleteIfExists(spoolFile);
				}
			}
			if (failure != null) {
				throw failure;
			}
		}

		private void spoolToFile() throws IOException {
			spoolFile = Files.createTempFile("llama-chat-stream-", ".json");
			spoolOutput = Files.newOutputStream(spoolFile);
			memoryBuffer.writeTo(spoolOutput);
			memoryBuffer.reset();
		}
	}
}
