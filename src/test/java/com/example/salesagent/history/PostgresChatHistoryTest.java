package com.example.salesagent.history;

import com.example.salesagent.model.ChatResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CHAT_TEST_PG_URL", matches = ".+")
@SpringBootTest(classes = PostgresChatHistoryTest.TestApp.class, properties = "spring.profiles.active=app")
class PostgresChatHistoryTest {
    @SpringBootConfiguration @EnableAutoConfiguration
    @MapperScan("com.example.salesagent.history")
    @Import(PgChatHistoryStore.class)
    static class TestApp {}

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("CHAT_TEST_PG_URL"));
        properties.add("spring.datasource.username", () -> System.getenv("CHAT_TEST_PG_USER"));
        properties.add("spring.datasource.password", () -> System.getenv("CHAT_TEST_PG_PASSWORD"));
    }

    @Autowired PgChatHistoryStore store;

    @Test void migratesPostgresAndPersistsCompleteTurn() {
        String id = java.util.UUID.randomUUID().toString();
        store.append(id, "PostgreSQL 问题", new ChatResponse(id, "持久化回答",
                List.of("products.md"), List.of("检索完成"), 5, 11));
        assertTrue(store.exists(id));
        assertEquals("持久化回答", store.turns(id).getFirst().getAnswer());
        assertEquals(List.of("products.md"), store.recentTurns(id, 10).getFirst().getSources());
        store.append(id, "PostgreSQL 问题", new ChatResponse(id, "重复回答", List.of(), List.of(), 0, 1));
        assertEquals(2, store.turns(id).size());
    }
}
