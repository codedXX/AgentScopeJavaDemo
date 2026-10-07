package com.example.salesagent.controller;

import com.example.salesagent.attachment.ImageAttachmentStore;
import com.example.salesagent.agent.SalesAssistant;
import com.example.salesagent.model.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ImageAttachmentControllerTest {
    @TempDir Path temp;

    @Test void uploadsActualImageAndIsolatesSessionsAfterRestart() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ImageAttachmentController(new ImageAttachmentStore(temp)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] bytes = output.toByteArray();
        var result = mvc.perform(multipart("/api/images")
                .file(new MockMultipartFile("file", "fake-name.txt", "text/plain", bytes)).param("sessionId", "one"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contentType").value("image/png"))
                .andReturn();
        var attachment = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        String id = attachment.path("id").asText();
        // 保存后重新构建存储，证明预览不依赖进程内的临时文件。
        var restarted = MockMvcBuilders.standaloneSetup(new ImageAttachmentController(new ImageAttachmentStore(temp)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        restarted.perform(get(attachment.path("url").asText())).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(content().bytes(bytes))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        restarted.perform(get("/api/sessions/two/images/" + id)).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/images")
                .file(new MockMultipartFile("file", bytes)).param("sessionId", "../outside")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/images").file(new MockMultipartFile("file", bytes)))
                .andExpect(status().isBadRequest());
    }

    @Test void rejectsSpoofedCorruptOversizedAndUnsupportedImages() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ImageAttachmentController(new ImageAttachmentStore(temp)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var gif = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "gif", gif);
        for (byte[] bytes : List.of("<script>bad</script>".getBytes(), new byte[0],
                new byte[ImageAttachmentStore.MAX_BYTES + 1], gif.toByteArray(), new byte[]{(byte) 0xff, (byte) 0xd8, 0})) {
            mvc.perform(multipart("/api/images")
                    .file(new MockMultipartFile("file", "fake.png", "image/png", bytes)).param("sessionId", "one"))
                    .andExpect(status().isBadRequest());
        }
        assertFalse(java.nio.file.Files.exists(temp.resolve("one")));
    }

    @Test void chatAcceptsImagesOnlyAndLegacyTextButRejectsEmptyOrInvalidAttachments() throws Exception {
        var assistant = mock(SalesAssistant.class);
        when(assistant.chat(any())).thenReturn(new ChatResponse("one", "ok", List.of(), List.of(), 0, 0));
        var mvc = MockMvcBuilders.standaloneSetup(new ChatController(assistant))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String imageId = UUID.randomUUID().toString();
        var mapper = new ObjectMapper();
        for (var body : List.of(Map.of("message", "你好"), Map.of("sessionId", "one", "imageIds", List.of(imageId))))
            mvc.perform(post("/api/chat").contentType("application/json").content(mapper.writeValueAsBytes(body)))
                    .andExpect(status().isOk());
        for (String body : List.of("{}", "{\"message\":\" \"}", "{\"message\":\"a\",\"imageIds\":[null]}",
                mapper.writeValueAsString(Map.of("imageIds", List.of(imageId))),
                mapper.writeValueAsString(Map.of("sessionId", "one", "imageIds", Collections.nCopies(5, imageId))),
                "{\"sessionId\":\"one\",\"imageIds\":[\"../bad\"]}"))
            mvc.perform(post("/api/chat").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verify(assistant, times(2)).chat(any());
    }
}
