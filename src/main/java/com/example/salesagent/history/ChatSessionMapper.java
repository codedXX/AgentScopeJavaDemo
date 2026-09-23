package com.example.salesagent.history;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 通过 MyBatis-Plus 读写会话表。 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionRow> {}
