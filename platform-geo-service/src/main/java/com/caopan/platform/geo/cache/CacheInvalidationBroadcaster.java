package com.caopan.platform.geo.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 地理数据缓存 L1 失效广播（Redis pub/sub）。
 */
@Component
public class CacheInvalidationBroadcaster implements MessageListener, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationBroadcaster.class);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;
    private final String channel;
    private final String instanceId = UUID.randomUUID().toString();
    private volatile Consumer<CacheInvalidationMessage> onRemoteInvalidate;
    private volatile RedisMessageListenerContainer ownedContainer;

    public CacheInvalidationBroadcaster(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectMapper objectMapper,
            @Value("${platform.geo.cache.invalidate-channel:platform:geo:cache:invalidate}") String channel) {
        this.redisProvider = redisProvider;
        this.objectMapper = objectMapper;
        this.channel = channel;
    }

    public String instanceId() {
        return instanceId;
    }

    public boolean isBroadcastAvailable() {
        return redisProvider.getIfAvailable() != null;
    }

    public void setOnRemoteInvalidate(Consumer<CacheInvalidationMessage> onRemoteInvalidate) {
        this.onRemoteInvalidate = onRemoteInvalidate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        StringRedisTemplate redisTemplate = redisProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.info("cache invalidation broadcaster disabled (no redis)");
            return;
        }
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            log.info("cache invalidation broadcaster disabled (no redis connection factory)");
            return;
        }
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.afterPropertiesSet();
        container.addMessageListener(this, new ChannelTopic(channel));
        container.start();
        this.ownedContainer = container;
        log.info("subscribed cache invalidate channel={}, instanceId={}", channel, instanceId);
    }

    @PreDestroy
    public void destroy() {
        RedisMessageListenerContainer container = ownedContainer;
        if (container != null) {
            try {
                container.removeMessageListener(this);
                container.stop();
                container.destroy();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    public void publish(CacheInvalidationMessage message) {
        StringRedisTemplate redisTemplate = redisProvider.getIfAvailable();
        if (redisTemplate == null || message == null) {
            return;
        }
        try {
            redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            log.warn("publish cache invalidate failed: {}", e.getMessage());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            CacheInvalidationMessage msg = objectMapper.readValue(json, CacheInvalidationMessage.class);
            if (msg == null || instanceId.equals(msg.fromInstanceId())) {
                return;
            }
            Consumer<CacheInvalidationMessage> cb = onRemoteInvalidate;
            if (cb != null) {
                cb.accept(msg);
            }
        } catch (Exception e) {
            log.warn("handle cache invalidate message failed: {}", e.getMessage());
        }
    }
}
