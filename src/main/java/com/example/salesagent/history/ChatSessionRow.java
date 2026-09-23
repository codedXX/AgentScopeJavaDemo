package com.example.salesagent.history;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** 会话表的一行数据，对应数据库字段。 */
@TableName("chat_session")
public class ChatSessionRow {
    @TableId(type = IdType.INPUT) public String id;
    public String title;
    public Instant createdAt;
    public Instant updatedAt;
}
