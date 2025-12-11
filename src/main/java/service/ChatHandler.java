package service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);
    private final RedisTemplate<String, String> redisTemplate;

    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ChatHandler(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 获取或创建一个当前对话ID
     *
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
        } else {
            logger.info("获取到用户 {} 的当前会话ID: {}", userId, conversationId);
        }
        return conversationId;
    }

    private List<Map<String, String>> getConversationHistory(String conversationId) {
        String key = "conversation:" + conversationId;
        String json = redisTemplate.opsForValue().get(key);
        try {
            if (json == null) {
                logger.debug("会话ID: {} 没有历史对话记录", conversationId);
                return new ArrayList<>();
            }
            // 使用Jackson库的read.Value()方法将JSON字符串反序列化为Java集合对象
            List<Map<String, String>> history = objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {
            });
            logger.debug("读取会话ID: {} 的 {} 条历史记录", conversationId, history.size());
            return history;
        } catch (JsonProcessingException e) {
            logger.error("解析对话历史错误: {}, 会话ID: {}", e.getMessage(), conversationId, e);
            return new ArrayList<>();
        }
    }

    private void updateConversationHistory(String conversationId, String userMessage, String response) {
        String key = "conversation:" + conversationId;
        List<Map<String, String>> history = getConversationHistory(conversationId);

//        获取当前时间戳
        String currentTimestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

//        添加用户信息(带时间戳)
        Map<String, String> userMsgMap = new HashMap<>();
//        当前身份是user
        userMsgMap.put("role", "user");
        userMsgMap.put("content", userMessage);
        userMsgMap.put("timestamp", currentTimestamp);
        history.add(userMsgMap);

//        添加GPT回复信息
        Map<String, String> assistMsgMap = new HashMap<>();
        assistMsgMap.put("role", "assist");
        assistMsgMap.put("content", response);
        assistMsgMap.put("timestamp", currentTimestamp);
        history.add(assistMsgMap);

//        限制对话历史长度，保留最近的20条消息
        if (history.size() > 20) {
            history = history.subList(history.size() - 20, history.size());
        }

//        序列化历史对话并保存入redis
        try {
            String json = objectMapper.writeValueAsString(history);
            redisTemplate.opsForValue().set(key, json, Duration.ofDays(7));
            logger.debug("更新对话历史，会话ID: {}, 总消息数: {}", conversationId, history.size());
        }catch (JsonProcessingException e) {
            logger.error("序列化对话历史错误: {}, 会话ID: {}", e.getMessage(), conversationId, e);
        }
    }
}
