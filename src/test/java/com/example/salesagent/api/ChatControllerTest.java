package com.example.salesagent.api;

import com.example.salesagent.agent.SalesAssistant;
import com.example.salesagent.history.ChatHistoryStore;
import com.example.salesagent.history.ChatSession;
import com.example.salesagent.history.ChatTurn;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerTest {
    @Test void listsSessionsAndLoadsSavedTurns() throws Exception {
        SalesAssistant assistant = mock(SalesAssistant.class);
        ChatHistoryStore history = mock(ChatHistoryStore.class);
        Instant now = Instant.parse("2026-09-23T00:00:00Z");
        when(history.listSessions()).thenReturn(List.of(new ChatSession("abc", "产品问题", now, now)));
        when(history.exists("abc")).thenReturn(true);
        when(history.turns("abc")).thenReturn(List.of(new ChatTurn(1, "abc", "问题", "回答",
                List.of("products.md"), List.of("检索"), 7, 12, now)));
        ChatController controller = new ChatController();
        ReflectionTestUtils.setField(controller, "assistant", assistant);
        ReflectionTestUtils.setField(controller, "history", history);
        org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(get("/api/sessions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("abc"));
        mvc.perform(get("/api/sessions/abc/turns"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].question").value("问题"))
                .andExpect(jsonPath("$[0].sources[0]").value("products.md"));
        mvc.perform(get("/api/sessions/missing/turns")).andExpect(status().isNotFound());
    }
}
