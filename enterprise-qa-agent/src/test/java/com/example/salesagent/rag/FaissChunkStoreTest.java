// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;
// 引入 HttpServer，用于在本地模拟 FAISS HTTP 服务。
import com.sun.net.httpserver.HttpServer;
// 引入 KnowledgeChunk，用于知识子块正文、来源与稳定标识。
import com.example.salesagent.model.KnowledgeChunk;
// 引入 ObjectMapper，用于JSON 序列化与反序列化。
import com.fasterxml.jackson.databind.ObjectMapper;
// 引入 InetSocketAddress，用于绑定回环地址和随机端口。
import java.net.InetSocketAddress;
// 引入集合、去重、排序和不可变结果构造工具。
import java.util.*;
// 引入 Test，用于标记 JUnit 测试方法。
import org.junit.jupiter.api.Test;
// 引入 JUnit 断言，验证结果、异常及边界行为。
import static org.junit.jupiter.api.Assertions.*;
// 验证 Java FAISS 适配器的 HTTP 请求协议和响应分块映射。
class FaissChunkStoreTest {
    // 在本地模拟 FAISS 服务，覆盖重置、写入、发布、计数和检索的完整调用顺序。
    @Test void mapsRequestsAndRankedResponsesAcrossHttp() throws Exception {
        // 准备 JSON 编解码器和已访问接口路径记录。
        var mapper=new ObjectMapper();var paths=new ArrayList<String>();
        // 只绑定本机回环地址并让系统分配空闲端口，避免端口冲突。
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        // 为所有路径注册统一的 HTTP 测试处理器。
        server.createContext("/",exchange->{
            // 读取实际请求路径并保存，以便稍后验证接口顺序。
            String path=exchange.getRequestURI().getPath();paths.add(path);
            // 默认返回成功响应，用于 reset 与 publish 等无数据接口。
            Object response=Map.of("ok",true);
            // 收到 /upsert 时检查 Java 适配器实际发送的 JSON。
            if(path.equals("/upsert")) {
                // 解析请求并断言 chunks 中的分块 ID 没有在序列化时丢失。
                var request=mapper.readTree(exchange.getRequestBody());assertEquals("a",request.path("chunks").get(0).path("chunkId").asText());
                // 断言 vectors 数组保留配置的二维向量。
                assertEquals(2,request.path("vectors").get(0).size());
            }
            // /count 返回一个已保存分块的数量。
            if(path.equals("/count"))response=Map.of("count",1);
            // /search 返回包含正文、来源、ID 和分数的可还原命中。
            if(path.equals("/search"))response=Map.of("hits",List.of(Map.of("chunk",new KnowledgeChunk("a","证据","a.md",0),"score",.9)));
            // 将响应转为字节、返回 200 状态及长度、写入正文后关闭本次交换。
            byte[] bytes=mapper.writeValueAsBytes(response);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        // 结束路由注册并启动本地 HTTP 服务。
        });server.start();
        // 根据真实随机端口创建 FAISS 存储，并确保适配器最终关闭。
        try(var store=new FaissChunkStore("http://127.0.0.1:"+server.getAddress().getPort())) {
            // 依次重置二维索引、写入一个分块及向量、发布索引。
            store.reset(2);store.upsert(List.of(new KnowledgeChunk("a","证据","a.md",0)),List.of(new float[]{1,0}));store.publish();
            // 断言计数为一，并能把检索响应恢复成 ID 为 a 的知识分块。
            assertEquals(1,store.count());assertEquals("a",store.search(new float[]{1,0},5).getFirst().chunk().chunkId());
            // 断言所有操作调用了约定的服务路径，顺序也完全一致。
            assertEquals(List.of("/reset","/upsert","/publish","/count","/search"),paths);
        // 无论断言结果如何，都立即停止临时 HTTP 服务。
        }finally {server.stop(0);}
    }
}
