package org.mark.test.mcp;

/**
 * 	一个测试用的启动入口。
 */
public class NettySseMcpTestServer {

	private final DefaultMcpServiceImpl service;

	public NettySseMcpTestServer(int port) {
		this.service = new DefaultMcpServiceImpl(port);
	}

	public void start() throws Exception {
		this.service.start();
	}

	public void stop() {
		this.service.stop();
	}

	public void awaitClose() throws InterruptedException {
		this.service.awaitClose();
	}

	public void registerDefaultTools(String serviceKey, IMCPTool tool) {
		this.service.registerTool(serviceKey, tool);
	}

	public static void main(String[] args) throws Exception {
		int port = 8075;
		NettySseMcpTestServer server = new NettySseMcpTestServer(port);
		server.start();
		server.awaitClose();
	}
}
