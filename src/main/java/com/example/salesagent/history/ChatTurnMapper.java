package com.example.salesagent.history;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 通过 MyBatis-Plus 读写问答轮次表。 */
@Mapper
public interface ChatTurnMapper extends BaseMapper<ChatTurnRow> {}
