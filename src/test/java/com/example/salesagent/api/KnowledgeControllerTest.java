package com.example.salesagent.api;

import com.example.salesagent.rag.KnowledgeIngestionService;
import com.example.salesagent.rag.RebuildStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/** 验证上传接口的成功响应和错误提示。 */
class KnowledgeControllerTest {
    /** 文件上传成功后返回索引状态。 */
    @Test
    void multipartUploadReturnsIndexedStatus() throws Exception {
        KnowledgeIngestionService ingestion = mock(KnowledgeIngestionService.class);
        byte[] content = "产品资料".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(ingestion.upload("product.md", content)).thenReturn(new RebuildStatus(true, 2, null, "重建完成"));
        org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(ingestion))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(multipart("/api/knowledge/upload").file(new MockMultipartFile("file", "product.md", "text/plain", content)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.chunkCount").value(2));
        verify(ingestion).upload("product.md", content);
        mvc.perform(multipart("/api/knowledge/upload")).andExpect(status().isBadRequest());
    }

    /** 无效文件返回易读的错误信息。 */
    @Test
    void invalidFileReturnsReadableError() throws Exception {
        KnowledgeIngestionService ingestion = mock(KnowledgeIngestionService.class);
        when(ingestion.upload(anyString(), any())).thenThrow(new IllegalArgumentException("文件必须使用 UTF-8 编码"));
        org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(ingestion))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(multipart("/api/knowledge/upload").file(new MockMultipartFile("file", "bad.txt", "text/plain", new byte[]{(byte) 0xff})))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("文件必须使用 UTF-8 编码"));
    }

    private static KnowledgeController controller(KnowledgeIngestionService ingestion) {
        KnowledgeController controller = new KnowledgeController();
        ReflectionTestUtils.setField(controller, "ingestion", ingestion);
        return controller;
    }
}
