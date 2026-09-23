package com.example.salesagent.api;

import com.example.salesagent.SalesAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SalesAgentApplication.class, properties = {
        "spring.profiles.active=app",
        "spring.datasource.url=jdbc:h2:mem:chat_boot;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "demo.rag.index-dir=./target/chat-boot-lucene"
})
/** 验证应用启动时数据库迁移和历史接口可用。 */
@AutoConfigureMockMvc
class ChatHistoryBootTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    /** 应用启动后完成迁移并开放历史接口。 */
    @Test void startsAppWithMigratedHistoryTablesAndMappedEndpoint() throws Exception {
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM chat_session", Long.class));
        mvc.perform(get("/api/sessions")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }
}
