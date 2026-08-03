package com.caopan.platform.geo.access;

/**
 * 管理会话解析结果。
 */
public record AdminSessionCaller(Long sessionId, Long userId, String username, String displayName) {
}
