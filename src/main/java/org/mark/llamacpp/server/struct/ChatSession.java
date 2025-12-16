package org.mark.llamacpp.server.struct;

public class ChatSession {
	
	
	private final long id;
	private final long createdAt;
	
	public ChatSession(long id, long createdAt) {
		this.id = id;
		this.createdAt = createdAt;
	}

	public long getId() {
		return id;
	}

	public long getCreatedAt() {
		return createdAt;
	}
}
