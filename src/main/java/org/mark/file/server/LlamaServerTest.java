package org.mark.file.server;

import java.util.List;

import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.tools.VramEstimator;

public class LlamaServerTest {

	public static void main(String[] args) {
		LlamaServerManager m = LlamaServerManager.getInstance();
		
		List<GGUFModel> list = m.listModel();
		
		System.err.println(list);
		
		
		VramEstimator.Result result = VramEstimator.estimate(list.get(1), 8192, 2048, true);
		
		System.err.println(result.getTotalBytes());
	}

}
