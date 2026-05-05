package org.mark.llamacpp.server.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mark.llamacpp.server.LlamaHubNode;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.NodeManager;
import org.mark.llamacpp.server.struct.ActiveRequest;
import org.mark.llamacpp.server.io.BoundedQueueInputStream;
import org.mark.llamacpp.server.tools.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;



/**
 * 	这东西挺重要的，用于把聊天补全的JSON流式处理给llamacpp进程。
 */
public class ChatStreamSession {

	private static final Logger logger = LoggerFactory.getLogger(ChatStreamSession.class);
	
	/**
	 * 	依然是虚拟线程。
	 */
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
	
	
	/**
	 * 	流式解析请求体
	 */
	private final ChatRequestStreamingTransformer transformer =
			new ChatRequestStreamingTransformer(MAX_SMALL_FIELD_BYTES, MAX_BUFFERED_FIELD_BYTES);
	
	
	private final DeferredConnectionOutputStream deferredOutput = new DeferredConnectionOutputStream(DEFERRED_MEMORY_LIMIT);
	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AtomicBoolean completed = new AtomicBoolean(false);
	private final AtomicBoolean cancelled = new AtomicBoolean(false);

	private volatile HttpURLConnection connection;
	private volatile String requestId;
	private volatile boolean receivedBody;
	private volatile String routingNodeId;
	
	
	
	/**
	 * 	初始化一个流式会话。
	 * @param ctx
	 * @param openAIService
	 * @param method
	 * @param headers
	 */
	public ChatStreamSession(ChannelHandlerContext ctx, OpenAIService openAIService, HttpMethod method, Map<String, String> headers) {
		this.ctx = ctx;
		this.openAIService = openAIService;
		this.method = method;
		this.headers = headers;
	}
	
	
	/**
	 * 	哎，启动！
	 */
	public void start() {
		if (this.started.compareAndSet(false, true)) {
			worker.execute(this::run);
		}
	}
	
	/**
	 * 	传入数据块。
	 * @param content
	 * @throws IOException
	 */
	public void offer(ByteBuf content) throws IOException {
		if (content == null || !content.isReadable()) {
			return;
		}
		// 这一步涉及到数据拷贝，无可避免需要吃一些内容。
		byte[] bytes = new byte[content.readableBytes()];
		content.getBytes(content.readerIndex(), bytes);
		this.receivedBody = true;
		this.requestBodyStream.offer(bytes);
	}
	
	/**
	 * 	完成了，需要显式地调用这个表明任务正常结束了。
	 */
	public void complete() {
		if (this.completed.compareAndSet(false, true)) {
			this.requestBodyStream.complete();
		}
	}
	
	/**
	 * 	取消，取他妈的消。
	 */
	public void cancel() {
		if (this.cancelled.compareAndSet(false, true)) {
			ModelRequestTracker.getInstance().removeRequest(this.requestId);
			this.requestBodyStream.fail(new IOException("client disconnected"));
			if (this.connection != null) {
				this.connection.disconnect();
			}
			try {
				this.deferredOutput.close();
			} catch (IOException e) {
			}
		}
	}
	
	/**
	 * 	run run run
	 */
	private void run() {
		try {
			if (this.method != HttpMethod.POST) {
				this.openAIService.sendOpenAIErrorResponseWithCleanup(ctx, 405, null, "Only POST method is supported", "method");
				return;
			}
			// 这里等待解析出model的名称，一旦拿到了名称，就去建立和llamacpp的连接。
			ChatRequestStreamingTransformer.TransformResult result = this.transformer.transform(
					this.requestBodyStream,
					this.deferredOutput,
					modelName -> logger.info("聊天流式请求已解析到模型字段，等待完整解析后路由: {}", modelName));

			if (!this.receivedBody) {
				this.openAIService.sendOpenAIErrorResponseWithCleanup(this.ctx, 400, null, "Request body is empty", "messages");
				return;
			}

			this.openConnectionForModel(result.getModelName(), result.getNodeId());
			this.deferredOutput.close();
			if (this.connection == null) {
				throw new IOException("llama.cpp connection was not created");
			}

			this.requestId = ModelRequestTracker.getInstance().createRequest(result.getModelName(), "/v1/chat/completions");
			int responseCode = this.connection.getResponseCode();
			ModelRequestTracker.getInstance().updatePhase(this.requestId, ActiveRequest.Phase.GENERATION);
			this.openAIService.handleProxyResponse(this.ctx, this.connection, responseCode, result.isStream(), result.getModelName(), this.requestId, this.routingNodeId);
		} catch (ChatRequestStreamingTransformer.StreamingRequestException e) {
			if (!this.cancelled.get()) {
				this.openAIService.sendOpenAIErrorResponseWithCleanup(this.ctx, e.getHttpStatus(), null, e.getMessage(), e.getParam());
			}
		} catch (IOException e) {
			if (this.cancelled.get()) {
				logger.info("聊天流式会话已取消: {}", e.getMessage());
				return;
			}
			if (!this.receivedBody) {
				this.openAIService.sendOpenAIErrorResponseWithCleanup(this.ctx, 400, null, "Request body is empty", "messages");
				return;
			}
			logger.info("处理聊天流式请求时发生错误 [{}]", this.resolveNodeName(this.routingNodeId), e);
			this.openAIService.sendOpenAIErrorResponseWithCleanup(this.ctx, 500, null, e.getMessage(), null);
		} catch (Exception e) {
			if (this.cancelled.get()) {
				logger.info("聊天流式会话已取消: {}", e.getMessage());
				return;
			}
			logger.info("处理聊天流式请求时发生错误 [{}]", this.resolveNodeName(this.routingNodeId), e);
			this.openAIService.sendOpenAIErrorResponseWithCleanup(this.ctx, 500, null, e.getMessage(), null);
		} catch (Throwable t) {
			logger.error("虚拟线程异常已兜底: {}", t.getMessage(), t);
		} finally {
			ModelRequestTracker.getInstance().removeRequest(this.requestId);
			try {
				this.deferredOutput.close();
			} catch (IOException e) {
			}
			this.openAIService.cleanupTrackedConnection(this.ctx, this.connection);
		}
	}
	
	/**
	 * 解析节点名称
	 */
	private String resolveNodeName(String nodeId) {
		if (nodeId == null || nodeId.isBlank()) return "本机";
		try {
			LlamaHubNode node = NodeManager.getInstance().getNode(nodeId);
			if (node != null && node.getName() != null && !node.getName().isBlank()) {
				return node.getName();
			}
		} catch (Exception e) {
		}
		return nodeId;
	}

	/**
	 * 	连接到指定的llamacpp进程。
	 * @param modelName
	 * @throws IOException
	 */
	private synchronized void openConnectionForModel(String modelName, String nodeIdFromBody) throws IOException {
		if (this.connection != null) {
			return;
		}
		if (modelName == null) {
			return;
		}

		logger.info("[Node路由] 开始解析模型: model={}", modelName);

		String targetUrl;

		if (nodeIdFromBody != null && !nodeIdFromBody.isBlank()) {
			logger.info("[Node路由] 请求体指定 nodeId，直接路由远程节点: nodeId={}, model={}", nodeIdFromBody, modelName);
			targetUrl = this.resolveRemoteModelUrl(nodeIdFromBody, modelName);
			this.routingNodeId = nodeIdFromBody;
		} else {
			logger.info("[Node路由] 无 nodeId，先查本地模型");
			targetUrl = this.resolveLocalModelUrl(modelName);
			if (targetUrl == null) {
				logger.info("[Node路由] 本地未找到模型，开始搜索远程节点: model={}", modelName);
				String[] remote = this.resolveFromRemoteNodes(modelName);
				if (remote != null) {
					targetUrl = remote[0];
					this.routingNodeId = remote[1];
				}
			}
		}

		if (targetUrl == null) {
			logger.warn("[Node路由] 模型未找到: model={}", modelName);
			throw new ChatRequestStreamingTransformer.StreamingRequestException(404, "Model not found: " + modelName, "model");
		}

		logger.info("[Node路由] 路由成功: model={}, target={}", modelName, targetUrl);
		this.connection = this.openAIService.openTrackedConnection(this.ctx, targetUrl, this.method, this.headers, true);
		this.deferredOutput.attach(this.connection.getOutputStream());
		logger.info("[Node路由] 聊天流式请求已连接到模型: {}, target: {}", modelName, targetUrl);
	}

	/**
	 * 解析本地模型 URL
	 */
	private String resolveLocalModelUrl(String modelName) {
		try {
			LlamaServerManager manager = LlamaServerManager.getInstance();
			if (!manager.getLoadedProcesses().containsKey(modelName)) {
				logger.info("[Node路由] 本地模型未加载: model={}, loadedModels={}", modelName, manager.getLoadedProcesses().keySet());
				return null;
			}
			Integer modelPort = manager.getModelPort(modelName);
			if (modelPort == null) {
				logger.warn("[Node路由] 本地模型端口未找到: model={}", modelName);
				return null;
			}
			return String.format("http://localhost:%d/v1/chat/completions", modelPort.intValue());
		} catch (Exception e) {
			logger.warn("[Node路由] 解析本地模型异常: model={}, error={}", modelName, e.getMessage());
			return null;
		}
	}

	/**
	 * 从远程节点中查找模型
	 */
	private String[] resolveFromRemoteNodes(String modelName) {
		NodeManager nodeManager = NodeManager.getInstance();
		List<LlamaHubNode> enabledNodes = nodeManager.listEnabledNodes();
		logger.info("[Node路由] 远程节点列表: count={}", enabledNodes.size());

		for (LlamaHubNode node : enabledNodes) {
			logger.info("[Node路由] 检查远程节点: nodeId={}, nodeName={}, baseUrl={}", node.getNodeId(), node.getName(), node.getBaseUrl());
			try {
				NodeManager.HttpResult result = nodeManager.callRemoteApi(node.getNodeId(), "GET", "/v1/models", null);
				logger.info("[Node路由] 远程节点响应: nodeId={}, code={}", node.getNodeId(), result.getStatusCode());
				if (!result.isSuccess()) {
					logger.warn("[Node路由] 远程节点请求失败: nodeId={}, body={}", node.getNodeId(), result.getBody());
					continue;
				}

				JsonObject root = JsonUtil.fromJson(result.getBody(), JsonObject.class);
				if (root == null) continue;

				if (root.has("models") && root.get("models").isJsonArray()) {
					JsonArray remoteModels = root.getAsJsonArray("models");
					for (com.google.gson.JsonElement el : remoteModels) {
						if (!el.isJsonObject()) continue;
						com.google.gson.JsonObject m = el.getAsJsonObject();
						String remoteModelKey = JsonUtil.getJsonString(m, "model");
						if (remoteModelKey.isEmpty()) remoteModelKey = JsonUtil.getJsonString(m, "name");
						logger.info("[Node路由] 远程模型条目: nodeId={}, remoteModelKey={}", node.getNodeId(), remoteModelKey);
						if (modelName.equals(remoteModelKey)) {
							logger.info("[Node路由] 远程节点匹配成功: model={}, nodeId={}", modelName, node.getNodeId());
							return new String[]{ node.getBaseUrl() + "/v1/chat/completions", node.getNodeId() };
						}
					}
				}
			} catch (Exception e) {
				logger.warn("[Node路由] 检查远程节点异常: nodeId={}, error={}", node.getNodeId(), e.getMessage());
			}
		}
		logger.warn("[Node路由] 所有远程节点均未找到模型: model={}", modelName);
		return null;
	}

	/**
	 * 解析远程节点模型 URL
	 */
	private String resolveRemoteModelUrl(String nodeId, String modelName) {
		NodeManager nodeManager = NodeManager.getInstance();
		LlamaHubNode node = nodeManager.getNode(nodeId);
		if (node == null || !node.isEnabled()) {
			logger.warn("[Node路由] 远程节点不存在或未启用: nodeId={}", nodeId);
			return null;
		}
		return node.getBaseUrl() + "/v1/chat/completions";
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
			if (this.closed) {
				throw new IOException("stream already closed");
			}
			if (this.target != null) {
				return;
			}
			this.target = outputStream;
			if (this.spoolOutput != null) {
				this.spoolOutput.flush();
				this.spoolOutput.close();
				this.spoolOutput = null;
				Files.copy(this.spoolFile, this.target);
				Files.deleteIfExists(this.spoolFile);
				this.spoolFile = null;
			} else if (this.memoryBuffer.size() > 0) {
				this.memoryBuffer.writeTo(this.target);
				this.memoryBuffer.reset();
			}
			this.target.flush();
		}

		@Override
		public synchronized void write(int b) throws IOException {
			write(new byte[] { (byte) b }, 0, 1);
		}

		@Override
		public synchronized void write(byte[] b, int off, int len) throws IOException {
			if (this.closed) {
				throw new IOException("stream already closed");
			}
			if (len <= 0) {
				return;
			}
			if (this.target != null) {
				this.target.write(b, off, len);
				return;
			}
			if (this.spoolOutput != null) {
				this.spoolOutput.write(b, off, len);
				return;
			}
			if (this.memoryBuffer.size() + len <= this.memoryLimit) {
				this.memoryBuffer.write(b, off, len);
				return;
			}
			spoolToFile();
			this.spoolOutput.write(b, off, len);
		}

		@Override
		public synchronized void flush() throws IOException {
			if (this.target != null) {
				this.target.flush();
				return;
			}
			if (this.spoolOutput != null) {
				this.spoolOutput.flush();
			}
		}

		@Override
		public synchronized void close() throws IOException {
			if (this.closed) {
				return;
			}
			this.closed = true;
			IOException failure = null;
			try {
				if (this.spoolOutput != null) {
					this.spoolOutput.close();
				}
			} catch (IOException e) {
				failure = e;
			}
			try {
				if (this.target != null) {
					this.target.flush();
					this.target.close();
				}
			} catch (IOException e) {
				if (failure == null) {
					failure = e;
				}
			} finally {
				if (this.spoolFile != null) {
					Files.deleteIfExists(this.spoolFile);
				}
			}
			if (failure != null) {
				throw failure;
			}
		}

		private void spoolToFile() throws IOException {
			this.spoolFile = Files.createTempFile("llama-chat-stream-", ".json");
			this.spoolOutput = Files.newOutputStream(this.spoolFile);
			this.memoryBuffer.writeTo(this.spoolOutput);
			this.memoryBuffer.reset();
		}
	}
}
