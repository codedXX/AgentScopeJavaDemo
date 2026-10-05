// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;
// 引入知识分块、命中证据与检索结果等业务数据类型。
import com.example.salesagent.model.*;
// 引入 ObjectMapper，用于JSON 序列化与反序列化。
import com.fasterxml.jackson.databind.ObjectMapper;
// 引入 URI，用于创建 HTTP 请求目标地址。
import java.net.URI;
// 引入 JDK HTTP 客户端、请求与响应类型。
import java.net.http.*;
// 引入 Duration，用于配置连接、读写和整体请求超时。
import java.time.Duration;
// 引入集合、去重、排序和不可变结果构造工具。
import java.util.*;
/** Java 调用本机 Python FAISS 服务，实际使用 IndexFlatIP，并持久化到磁盘。 */
// 通过 HTTP 调用本机 FAISS 服务，统一实现向量存储接口并支持资源关闭。
public final class FaissChunkStore implements WritableVectorChunkStore, AutoCloseable {
    // FAISS Python 服务基础地址，所有接口路径均在该地址下拼接。
    private final String url;
    // JSON 编解码器，负责请求对象与响应分块转换。
    private final ObjectMapper mapper=new ObjectMapper();
    // 复用 HTTP 客户端，并将建立连接的超时限制为 5 秒。
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    // 保存调用方配置的 FAISS 服务地址。
    public FaissChunkStore(String url) {this.url=url;}
    // 统一发送 GET 或 JSON POST 请求，返回已解析的响应 JSON。
    private com.fasterxml.jackson.databind.JsonNode request(String path,Object body) {
        // 在一个异常处理范围内执行序列化、发送和响应解析。
        try {
            // 组合目标 URI，并将单次 HTTP 请求超时限制为 25 秒。
            var b=HttpRequest.newBuilder(URI.create(url+path)).timeout(Duration.ofSeconds(25));
            // 没有正文时使用 GET；有正文时序列化为 JSON 并使用 POST。
            if(body==null) b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            // 同步发送请求，并把响应正文读取为字符串。
            var response=client.send(b.build(),HttpResponse.BodyHandlers.ofString());
            // 只接受服务约定的 200 状态，其他状态报告为 FAISS 调用失败。
            if(response.statusCode()!=200) throw new IllegalStateException("FAISS 服务返回 HTTP "+response.statusCode());
            // 解析 JSON 响应，供计数和命中映射复用。
            return mapper.readTree(response.body());
        // 被中断时恢复线程中断标志，再抛出请求取消异常。
        } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException("FAISS 请求取消",e);}
        // 网络或响应读取失败时报告 FAISS 服务不可用。
        catch(java.io.IOException e) {throw new IllegalStateException("FAISS 服务不可用",e);}
    }
    // 调用 /reset 按指定维度重建空索引。
    @Override public void reset(int dimension) {request("/reset",Map.of("dimension",dimension));}
    // 将知识分块与对应向量批量发送到服务。
    @Override public void upsert(List<KnowledgeChunk> chunks,List<float[]> vectors) {
        // 要求分块数与向量数相等，确保按下标一一对应。
        if(chunks.size()!=vectors.size()) throw new IllegalArgumentException("分块数量不匹配");
        // 使用固定 chunks 和 vectors 字段调用 /upsert。
        request("/upsert",Map.of("chunks",chunks,"vectors",vectors));
    }
    // 调用 /publish 将服务端索引发布并持久化。
    @Override public void publish() {request("/publish",Map.of());}
    // 通过 GET /count 读取服务端记录数量。
    @Override public long count() {return request("/count",null).path("count").asLong();}
    // 根据查询向量请求最多 topK 个相似子块。
    @Override public List<SearchHit> search(float[] vector,int topK) {
        // 非正 TopK 直接返回空列表；否则准备按服务排名收集命中。
        if(topK<=0) return List.of();var result=new ArrayList<SearchHit>();
        // 调用 /search 后遍历 hits 数组中的每一条命中。
        for(var hit:request("/search",Map.of("vector",vector,"topK",topK)).path("hits")) {
            // 把 chunk JSON 还原为知识分块，提取分数并标记 faiss 通道。
            try {result.add(new SearchHit(mapper.treeToValue(hit.path("chunk"),KnowledgeChunk.class),hit.path("score").asDouble(),"faiss"));}
            // JSON 字段与约定不一致时报告响应格式错误。
            catch(Exception e) {throw new IllegalStateException("FAISS 结果格式错误",e);}
        }
        // 按服务端返回的相似度顺序返回命中列表。
        return result;
    }
    // 关闭复用的 JDK HTTP 客户端，释放连接资源。
    @Override public void close() {client.close();}
}
