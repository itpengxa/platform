package com.caopan.platform.geo.config.runtime;

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

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 多实例配置变更广播（Redis pub/sub）。
 * <p>在全部单例就绪后订阅，避免与 Redis 自动配置的 Bean 顺序竞态。</p>
 */
@Component
public class ConfigChangeBroadcaster implements MessageListener, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ConfigChangeBroadcaster.class);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final String channel;
    private final String instanceId = UUID.randomUUID().toString();
    private volatile Consumer<String> onRemoteChange;
    private volatile RedisMessageListenerContainer ownedContainer;

    public ConfigChangeBroadcaster(
            ObjectProvider<StringRedisTemplate> redisProvider,
            @Value("${platform.config.change-channel:platform:config:changed}") String channel) {
        this.redisProvider = redisProvider;
        this.channel = channel;
    }

    @Override
    public void afterSingletonsInstantiated() {
        StringRedisTemplate redisTemplate = redisProvider.getIfAvailable();
        if (redisTemplate == null) {
            log.info("config change broadcaster disabled (no redis)");
            return;
        }
        RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
        if (factory == null) {
            log.info("config change broadcaster disabled (no redis connection factory)");
            return;
        }
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.afterPropertiesSet();
        container.addMessageListener(this, new ChannelTopic(channel));
        container.start();
        this.ownedContainer = container;
        log.info("subscribed config change channel={}, instanceId={}", channel, instanceId);
    }

    @PreDestroy
    public void unsubscribe() {
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

    public void setOnRemoteChange(Consumer<String> onRemoteChange) {
        this.onRemoteChange = onRemoteChange;
    }

    public void publish() {
        StringRedisTemplate redisTemplate = redisProvider.getIfAvailable();
        if (redisTemplate == null) {
            return;
        }
        try {
            redisTemplate.convertAndSend(channel, instanceId);
        } catch (Exception e) {
            log.warn("publish config change failed: {}", e.getMessage());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String from = new String(message.getBody(), StandardCharsets.UTF_8);
        if (instanceId.equals(from)) {
            return;
        }
        Consumer<String> cb = onRemoteChange;
        if (cb != null) {
            try {
                cb.accept(from);
            } catch (Exception e) {
                log.warn("handle remote config change failed: {}", e.getMessage());
            }
        }
    }
}
