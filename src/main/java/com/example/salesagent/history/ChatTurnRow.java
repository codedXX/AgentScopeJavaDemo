package com.example.salesagent.history;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

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
