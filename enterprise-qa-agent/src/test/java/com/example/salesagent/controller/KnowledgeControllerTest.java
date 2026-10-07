// 将知识库上传 HTTP 行为测试放入 API 测试包。
package com.example.salesagent.controller;

// 导入知识摄取服务，为控制器注入 Mockito 替身。
import com.example.salesagent.rag.KnowledgeIngestionService;
// 导入重建状态对象，用作上传成功的模拟返回值。
import com.example.salesagent.rag.RebuildStatus;
// 导入 JUnit 测试标记。
import org.junit.jupiter.api.Test;
// 导入内存 multipart 文件，构造无需真实浏览器的上传请求。
import org.springframework.mock.web.MockMultipartFile;
// 导入 MockMvc 构建器，仅启动控制器请求处理链。
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
// 导入 Mockito 的 mock、when、verify 等测试辅助函数。
import static org.mockito.Mockito.*;
// 导入 multipart 等 HTTP 请求构造函数。
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
// 导入状态码和 JSON 字段断言。
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 验证上传接口成功响应、必需文件参数以及可读校验错误。
class KnowledgeControllerTest {
    // 标记正常上传和缺少文件的 HTTP 测试。
    @Test
    // MockMvc 调用可能抛出异常，JUnit 会将未预期异常判为测试失败。
    void multipartUploadReturnsIndexedStatus() throws Exception {
        // 不打开真实索引，使用服务替身隔离控制器行为。
        var ingestion = mock(KnowledgeIngestionService.class);
        // 使用真实 PDF 字节，验证 multipart 原样交给服务层。
        byte[] content = com.example.salesagent.rag.PdfTestDocuments.text("Product details");
        // 上传指定文件时返回两个分块且就绪的重建状态。
        when(ingestion.upload("product.pdf", content)).thenReturn(new RebuildStatus(true, 2, null, "重建完成"));
        // 仅注册知识控制器，避免启动模型、向量数据库或整个 Spring 应用。
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(ingestion))
                // 加入真实异常处理器，覆盖参数错误到 HTTP 响应的映射。
                .setControllerAdvice(new ApiExceptionHandler()).build();
        // 发送名为 file 的 multipart 文档，文件名和字节与替身期望一致。
        mvc.perform(multipart("/api/knowledge/upload").file(new MockMultipartFile("file", "product.pdf", "application/pdf", content)))
                // 验证上传成功返回 200，并正确序列化知识库就绪标志。
                .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true))
                // 验证重建状态中的分块数没有被控制器丢失。
                .andExpect(jsonPath("$.chunkCount").value(2));
        // 验证控制器把文件名与原始字节交给了摄取服务。
        verify(ingestion).upload("product.pdf", content);
        // 缺少 file 表单部分时应返回 400，不能当作成功上传。
        mvc.perform(multipart("/api/knowledge/upload")).andExpect(status().isBadRequest());
    }

    // 标记服务层校验异常应保留具体可读原因的测试。
    @Test
    // 请求执行异常由测试方法抛给 JUnit。
    void invalidFileReturnsReadableError() throws Exception {
        // 使用服务替身，专门模拟 UTF-8 校验失败。
        var ingestion = mock(KnowledgeIngestionService.class);
        // 任意上传都抛出固定编码错误，检查控制器的异常转换路径。
        when(ingestion.upload(anyString(), any())).thenThrow(new IllegalArgumentException("Markdown 文件必须使用 UTF-8 编码"));
        // 构建只包含知识控制器的 MVC 测试环境。
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(ingestion))
                // 使用实际全局异常处理器输出 error 字段。
                .setControllerAdvice(new ApiExceptionHandler()).build();
        // 上传含非法 UTF-8 字节的文本文件，走服务层拒绝分支。
        mvc.perform(multipart("/api/knowledge/upload").file(new MockMultipartFile("file", "bad.md", "text/plain", new byte[]{(byte) 0xff})))
                // 检查 400 状态和具体编码说明，避免只返回泛化的参数错误。
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Markdown 文件必须使用 UTF-8 编码"));
    }
}
