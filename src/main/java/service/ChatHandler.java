package service;

import client.DeepSeekClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatHandler {

    private static final Logger logger = LoggerFactory.getLogger(ChatHandler.class);
    private final RedisTemplate<String, String> redisTemplate;
    private final HybridSearchService searchService;
    private final ObjectMapper objectMapper;
    private final DeepSeekClient deepSeekClient;

    //    存储每个会话的完整响应
    private final Map<String, StringBuilder> responseBuilders = new ConcurrentHashMap<>();
    //    跟踪每个会话的响应完成状态
    private final Map<String, CompletableFuture<String>> responseFutures = new ConcurrentHashMap<>();

    public ChatHandler(RedisTemplate<String, String> redisTemplate,
                       HybridSearchService searchService, DeepSeekClient deepSeekClient) {
        this.redisTemplate = redisTemplate;
        this.searchService = searchService;
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = new ObjectMapper();
    }

    public void processMessage(String userId, String userMessage, WebSocketSession session) {
        logger.info("开始处理消息,用户ID: {}, WebSocket会话ID: {}", userId, session.getId());
        try {
//            获取或创建会话ID
            String conversationId = getOrCreateConversationId(userId);
            logger.info("会话ID: {}, 用户ID: {}", conversationId, userId);
//            为当前会话创建响应构造器
            responseBuilders.put(session.getId(), new StringBuilder());
//            创建一个CompletableFuture跟踪响应完成状态
            CompletableFuture<String> responseFuture = new CompletableFuture<>();
            responseFutures.put(session.getId(), responseFuture);

//            获取历史对话
            List<Map<String, String>> history = getConversationHistory(conversationId);
            logger.debug("对话ID: {} 获取到 {} 条历史对话", conversationId, history.size());

//            执行混合检索（带权限）
            List<SearchResult> searchResults = searchService.searchWithPermission(userMessage, userId, 5);
            logger.debug("搜索结果数量: {}", searchResults.size());

//            根据检索的结果构建prompt上下文
            String context = buildContext(searchResults);

//            调用 大模型API 处理流式响应
            logger.info("调用API生成回复");
            deepSeekClient.streamResponse(
//                    用户提问
                    userMessage,
//                    上下文
                    context,
//                    历史对话记录
                    history,
//                    回调函数，处理AI返回的每个响应块chunk
                    chunk -> {
//                        追加响应内容
                        StringBuilder responseBuilder = responseBuilders.get(session.getId());
                        if (responseBuilder != null) {
                            responseBuilder.append(chunk);
                        }
//                        实时向前端发送当前chunk
                        sendResponseChunk(session, chunk);
                    },
//                    回调函数，处理请求过程中发生的错误
                    error -> {
//                        处理错误并完成future
                        handleError(session, error);
//                        发送相应完成通知，此时时错误情况
                        sendCompletionNotification(session);
//                        标记异步任务异常完成
                        responseFuture.completeExceptionally(error);
//                        清理会话响应构造器
                        responseBuilders.remove(session.getId());
                        responseFutures.remove(session.getId());
                    });
//            启动一个后台任务检查并标记响应完成
            new Thread(() -> {
                try {
//                    等待3s
                    Thread.sleep(3000);
//                    获取当前积累的响应内容
                    StringBuilder responseBuilder = responseBuilders.get(session.getId());
//                    如果相应构造器存在并且已有内容，认为响应已完成
                    if (responseBuilder != null) {
//                        获取响应变化
                        String lastResponse = responseBuilder.toString();
                        int lastLength = lastResponse.length();
//                        等待2s
                        Thread.sleep(2000);

                        if (responseBuilder.length() == lastLength) {
//                            没有新内容，可以认为响应已完成
                            responseFuture.complete(responseBuilder.toString());
                            logger.info("大模型响应已完成, 长度: {}", responseBuilder.length());

//                            发送响应完成通知
                            sendCompletionNotification(session);

//                            更新历史对话
                            String completedResponse = responseBuilder.toString();
                            updateConversationHistory(conversationId, userMessage, completedResponse);

//                            输出对话存储信息
                            String redisKey = "user:" + userId + ":current_conversation";
                            logger.info("对话存储信息, Redis Key: {}, value: {}", redisKey, conversationId);

//                            清理会话响应构造器
                            responseBuilders.remove(session.getId());
                            responseFutures.remove(session.getId());
                            logger.info("消息处理完成，用户ID: {}", userId);
                        } else {
//                            仍然后新内容,继续等待
                            logger.debug("响应继续，等待完成...");
                            for (int i = 0; i < 5; i++) {
                                Thread.sleep(5000);
                                if (responseBuilder != null) {
                                    lastLength = responseBuilder.length();
                                    Thread.sleep(2000);

                                    if (responseBuilder.length() == lastLength) {
                                        //                            没有新内容，可以认为响应已完成
                                        responseFuture.complete(responseBuilder.toString());
                                        logger.info("大模型响应已完成, 长度: {}", responseBuilder.length());

//                            发送响应完成通知
                                        sendCompletionNotification(session);

//                            更新历史对话
                                        String completedResponse = responseBuilder.toString();
                                        updateConversationHistory(conversationId, userMessage, completedResponse);

//                            输出对话存储信息
                                        String redisKey = "user:" + userId + ":current_conversation";
                                        logger.info("对话存储信息, Redis Key: {}, value: {}", redisKey, conversationId);

//                            清理会话响应构造器
                                        responseBuilders.remove(session.getId());
                                        responseFutures.remove(session.getId());
                                        logger.info("消息处理完成，用户ID: {}", userId);
                                        return;
                                    }
                                }
                            }

//                            多次检查仍未完成，强制完成
                            if (!responseFuture.isDone()) {
                                responseFuture.complete(responseBuilder.toString());

                                // 发送响应完成通知
                                sendCompletionNotification(session);

                                // 更新对话历史
                                String completeResponse = responseBuilder.toString();
                                updateConversationHistory(conversationId, userMessage, completeResponse);

                                // 输出对话存储信息以便调试
                                String redisKey = "user:" + userId + ":current_conversation";
                                logger.info("对话存储信息 - Redis键: {}, 值: {}", redisKey, conversationId);

                                // 清理会话响应构建器
                                responseBuilders.remove(session.getId());
                                responseFutures.remove(session.getId());
                                logger.info("消息处理强制完成，用户ID: {}", userId);
                            }
                        }
                    } else {
                        logger.warn("响应构造器为空，可能出现错误，WebSocket ID: {}", session.getId());
                        RuntimeException exception = new RuntimeException("响应构造器为空");
                        responseFuture.completeExceptionally(exception);
//                        发送错误消息
                        handleError(session, exception);
                    }
                } catch (Exception e) {
                    logger.error("检查相应完成时出现错误: {}", e.getMessage(), e);
                    responseFuture.completeExceptionally(e);

//                    清理会话响应构造器
                    responseBuilders.remove(session.getId());
                    responseFutures.remove(session.getId());
                }
            }).start();
        } catch (Exception e) {
            logger.error("处理消息出现错误: {}", e.getMessage(), e);
            handlerError(session, e);

//                    清理会话响应构造器
            responseBuilders.remove(session.getId());
//            清理响应future
            CompletableFuture<String> future = responseFutures.remove(session.getId());
            if (future != null && !future.isDone()) {
                future.completeExceptionally(e);
            }
        }
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

    /**
     * 根据对话ID查询历史对话记录
     *
     * @param conversationId 对话ID
     * @return 历史对话记录
     */
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
            logger.debug("读取到会话ID: {} 的 {} 条历史记录", conversationId, history.size());
            return history;
        } catch (JsonProcessingException e) {
            logger.error("解析对话历史错误: {}, 会话ID: {}", e.getMessage(), conversationId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 更新历史对话记录
     *
     * @param conversationId 历史对话ID
     * @param userMessage    用户发送的消息
     * @param response       GPT回答的消息
     */
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
        } catch (JsonProcessingException e) {
            logger.error("序列化对话历史错误: {}, 会话ID: {}", e.getMessage(), conversationId, e);
        }
    }

    /**
     * 根据搜索结果构建 prompt 上下文
     *
     * @param searchResults 检索结果
     * @return 返回上下文
     */
    private String buildContext(List<SearchResult> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
//            返回空字符串，DeepSeekClient 按“无检索结果”逻辑处理
            return "";
        }
//        单段最长长度为300, 超出要截断
        final int MAX_SNIPPET_LEN = 300;
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < searchResults.size(); i++) {
            SearchResult result = searchResults.get(i);
            String result_context = result.getTextContent();
            if (result_context.length() > MAX_SNIPPET_LEN) {
                result_context = result_context.substring(0, MAX_SNIPPET_LEN) + "...";
            }
            String fileName = result.getFileName() != null ? result.getFileName() : "unknown";
            context.append(String.format("[%d] (%s) %s\n", i + 1, fileName, result_context));
        }
        return context.toString();
    }
}
