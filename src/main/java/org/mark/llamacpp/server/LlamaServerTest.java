package org.mark.llamacpp.server;

import org.mark.llamacpp.server.service.ComputerService;

public class LlamaServerTest {

	public static void main(String[] args) {
		String cpuModel = ComputerService.getCPUModel();
		long ramKb = ComputerService.getPhysicalMemoryKB();
		double ramGb = ramKb > 0 ? ramKb / 1024.0 / 1024.0 : -1;
		int cpuCoreCount = ComputerService.getCPUCoreCount();
		String javaVersion = ComputerService.getJavaVersion();
		String javaVendor = ComputerService.getJavaVendor();
		String jvmName = ComputerService.getJvmName();
		String jvmVersion = ComputerService.getJvmVersion();
		String jvmVendor = ComputerService.getJvmVendor();
		String jvmInputArguments = ComputerService.getJvmInputArguments();
		long jvmStartTime = ComputerService.getJvmStartTime();
		long jvmMaxMemoryMb = ComputerService.getJvmMaxMemoryMB();
		long jvmTotalMemoryMb = ComputerService.getJvmTotalMemoryMB();
		long jvmFreeMemoryMb = ComputerService.getJvmFreeMemoryMB();
		long jvmUsedMemoryMb = ComputerService.getJvmUsedMemoryMB();
		int jvmAvailableProcessors = ComputerService.getJvmAvailableProcessors();
		System.out.println("CPU Model: " + cpuModel);
		System.out.println("CPU Cores: " + cpuCoreCount);
		System.out.println("RAM KB: " + ramKb);
		System.out.println("RAM GB: " + (ramGb > 0 ? String.format("%.2f", ramGb) : "无法获取"));
		System.out.println("Java Version: " + javaVersion);
		System.out.println("Java Vendor: " + javaVendor);
		System.out.println("JVM Name: " + jvmName);
		System.out.println("JVM Version: " + jvmVersion);
		System.out.println("JVM Vendor: " + jvmVendor);
		System.out.println("JVM Input Arguments: " + jvmInputArguments);
		System.out.println("JVM Start Time: " + jvmStartTime);
		System.out.println("JVM Max Memory MB: " + jvmMaxMemoryMb);
		System.out.println("JVM Total Memory MB: " + jvmTotalMemoryMb);
		System.out.println("JVM Free Memory MB: " + jvmFreeMemoryMb);
		System.out.println("JVM Used Memory MB: " + jvmUsedMemoryMb);
		System.out.println("JVM Available Processors: " + jvmAvailableProcessors);
	}

}
