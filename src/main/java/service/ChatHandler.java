package service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);
    private final RedisTemplate<String, String> redisTemplate;

    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();

    public ChatHandler(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 获取或创建一个当前对话ID
     * @param userId 用户ID
     * @return 当前对话ID
     */
    private String getOrCreateConversationId(String userId) {
        String key = "user:" + userId + ":current_conversation";
        String conversationId = redisTemplate.opsForValue().get(key);

        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
            redisTemplate.opsForValue().set(key, conversationId, Duration.ofDays(7));
            logger.info("为用户 {} 创建新的会话ID: {}", userId, conversationId);
        }else {
            logger.info("获取到用户 {} 的当前会话ID: {}", userId, conversationId);
        }
        return conversationId;
    }
}
