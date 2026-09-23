package com.example.salesagent.history;

import com.example.salesagent.model.ChatResponse;
import java.util.List;

/**
 * 聊天记录存储边界。会话列表供页面展示；完整轮次供历史回放；最近轮次供模型恢复上下文。
 */
public interface ChatHistoryStore {
    List<ChatSession> listSessions();
    boolean exists(String sessionId);
    List<ChatTurn> turns(String sessionId);
    /** 返回按时间正序排列的最近 limit 轮，供 Agent 按用户、助手顺序重建记忆。 */
    List<ChatTurn> recentTurns(String sessionId, int limit);
    /** 保存完整问答；实现需将轮次写入和会话元信息更新视为同一事务。 */
    void append(String sessionId, String question, ChatResponse answer);
}
