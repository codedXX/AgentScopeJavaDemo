package com.example.salesagent.history;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.salesagent.model.ChatResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基于 MyBatis-Plus 的 PostgreSQL 聊天记录实现。会话保存标题与更新时间，轮次保存问题、回答、
 * 来源、处理步骤和耗时；来源与步骤以 JSON 编码，读取时还原成列表。
 */
@Service
@Profile("app")
public class PgChatHistoryStore implements ChatHistoryStore {
    @Autowired private ChatSessionMapper sessions;
    @Autowired private ChatTurnMapper turns;
    @Autowired private ObjectMapper json;

    /** 按最近更新时间列出会话。 */
    @Override public List<ChatSession> listSessions() {
        return sessions.selectList(new QueryWrapper<ChatSessionRow>().orderByDesc("updated_at", "id"))
                .stream().map(row -> new ChatSession(row.id, row.title, row.createdAt, row.updatedAt)).toList();
    }

    /** 检查会话编号是否存在。 */
    @Override public boolean exists(String sessionId) {
        return sessions.selectById(sessionId) != null;
    }

    @Override public List<ChatTurn> turns(String sessionId) {
        return turns.selectList(new QueryWrapper<ChatTurnRow>().eq("session_id", sessionId).orderByAsc("id"))
                .stream().map(this::map).toList();
    }

    /**
     * 按递增主键倒序查询最近 limit 条，减少长会话读取量；返回前反转为最早到最新的顺序。
     * limit 只允许 1 到 100，既约束读取规模，也保证拼接到 SQL 的值为受控整数。
     */
    @Override public List<ChatTurn> recentTurns(String sessionId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("历史轮数必须在 1 到 100 之间");
        List<ChatTurnRow> rows = turns.selectList(new QueryWrapper<ChatTurnRow>().eq("session_id", sessionId)
                .orderByDesc("id").last("LIMIT " + limit));
        List<ChatTurn> result = new ArrayList<>(rows.size());
        rows.forEach(row -> result.add(map(row)));
        Collections.reverse(result);
        return List.copyOf(result);
    }

    /**
     * 在一个事务内写入问答轮次并更新会话时间。第一次提问时创建会话，以问题前 120 字为标题。
     */
    @Override @Transactional public void append(String sessionId, String question, ChatResponse response) {
        Instant now = Instant.now();
        ChatSessionRow session = sessions.selectById(sessionId);
        if (session == null) {
            session = new ChatSessionRow();
            session.id = sessionId;
            session.title = question.length() <= 120 ? question : question.substring(0, 120);
            session.createdAt = now;
            session.updatedAt = now;
            sessions.insert(session);
        }
        ChatTurnRow turn = new ChatTurnRow();
        turn.sessionId = sessionId;
        turn.question = question;
        turn.answer = response.getAnswer();
        turn.sourcesJson = encode(response.getSources());
        turn.stepsJson = encode(response.getSteps());
        turn.retrievalMs = response.getRetrievalMs();
        turn.totalMs = response.getTotalMs();
        turn.createdAt = now;
        turns.insert(turn);
        session.updatedAt = now;
        sessions.updateById(session);
    }

    /** 把数据库行还原成前端使用的问答对象。 */
    private ChatTurn map(ChatTurnRow row) {
        return new ChatTurn(row.id, row.sessionId, row.question, row.answer,
                decode(row.sourcesJson), decode(row.stepsJson), row.retrievalMs, row.totalMs, row.createdAt);
    }

    /** 把字符串列表存为 JSON。 */
    private String encode(List<String> value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("聊天记录序列化失败", e); }
    }

    /** 把数据库中的 JSON 还原为列表。 */
    private List<String> decode(String value) {
        try { return json.readValue(value, new TypeReference<List<String>>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException("聊天记录内容损坏", e); }
    }
}
