package org.mark.file.server;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;

import org.mark.llamacpp.download.BasicDownloader;
import org.mark.llamacpp.gguf.GGUFBundle;
import org.mark.llamacpp.gguf.GGUFModel;
import org.mark.llamacpp.server.LlamaServerManager;
import org.mark.llamacpp.server.struct.VramEstimation;
import org.mark.llamacpp.server.tools.VramEstimator;

public class LlamaServerTest {

	public static void main(String[] args) {
		/*
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
		List<GGUFModel> list = LlamaServerManager.getInstance().listModel();
		System.err.println(list.get(0).getSize());
		long b = System.currentTimeMillis();
		
		System.err.println("耗时：" + (b - a));
		
		
		GGUFBundle g = new GGUFBundle(new File("D:\\Modesl\\GGUF\\aaaaaaaaaaa\\Qwen3-235B-A22B-Instruct-2507-UD-Q2_K_XL-00001-of-00002.gguf"));
		
		System.err.println(g.getTotalFileSize());
		*/
		
		
		BasicDownloader basicDownloader = new BasicDownloader("https://hf-mirror.com/unsloth/GLM-4.6-GGUF/resolve/main/README.md?download=true");
		try {
			basicDownloader.download();
		} catch (IOException | URISyntaxException | InterruptedException e) {
			e.printStackTrace();
		}
	}

}
