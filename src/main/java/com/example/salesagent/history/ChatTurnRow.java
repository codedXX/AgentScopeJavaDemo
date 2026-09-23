package com.example.salesagent.history;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** 问答轮次表的一行数据；列表字段在库中保存为 JSON。 */
@TableName("chat_turn")
public class ChatTurnRow {
    @TableId(type = IdType.AUTO) public Long id;
    public String sessionId;
    public String question;
    public String answer;
    public String sourcesJson;
    public String stepsJson;
    public Long retrievalMs;
    public Long totalMs;
    public Instant createdAt;
}
