package org.mark.file.server;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.struct.VramEstimation;
import org.mark.llamacpp.server.tools.VramEstimator;

public class LlamaServerTest {

	public static void main(String[] args) {
		//VramEstimator.Result result = VramEstimator.estimate(list.get(1), 8192, 2048, true);
		
		//System.err.println(result.getTotalBytes());
		
		try {
			//VramEstimator.estimateVram(new File("D:\\Modesl\\GGUF\\Qwen3-0.6\\Qwen3-0.6B-Q8_0.gguf"), 8192);
			
			VramEstimation result = VramEstimator.estimateVram(new File("D:\\Modesl\\GGUF\\Qwen3-0.6\\Qwen3-0.6B-Q8_0.gguf"), 8192, 16, 2048, 2048);
			System.err.println(result.getTotalVramRequired());
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		long a = System.currentTimeMillis();
		LlamaServerManager.getInstance().listModel();
		long b = System.currentTimeMillis();
		
		System.err.println("耗时：" + (b - a));
	}

}
