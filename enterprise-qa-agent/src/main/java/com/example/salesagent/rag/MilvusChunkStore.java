// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;

// 引入 KnowledgeChunk，用于知识子块正文、来源与稳定标识。
import com.example.salesagent.model.KnowledgeChunk;
// 引入 SearchHit，用于检索命中分块、分数和通道。
import com.example.salesagent.model.SearchHit;
// 引入 Gson，用于将 Java 浮点向量转换成 Milvus JSON 字段。
import com.google.gson.Gson;
// 引入 JsonObject，用于组装 Milvus 单条入库记录。
import com.google.gson.JsonObject;
// 引入 ConnectConfig，用于配置 Milvus 地址、令牌和超时。
import io.milvus.v2.client.ConnectConfig;
// 引入 MilvusClientV2，用于调用 Milvus 集合与向量 API。
import io.milvus.v2.client.MilvusClientV2;
// 引入 DataType，用于声明 Milvus 字段类型。
import io.milvus.v2.common.DataType;
// 引入 IndexParam，用于配置向量索引和余弦度量。
import io.milvus.v2.common.IndexParam;
// 引入 ConsistencyLevel，用于以强一致性读取已发布索引。
import io.milvus.v2.common.ConsistencyLevel;
// 引入 Milvus 集合结构、创建、删除和加载请求。
import io.milvus.v2.service.collection.request.*;
// 引入 FlushReq，用于刷新 Milvus 数据。
import io.milvus.v2.service.utility.request.FlushReq;
// 引入 InsertReq，用于批量插入 Milvus 文档。
import io.milvus.v2.service.vector.request.InsertReq;
// 引入 QueryReq，用于向 Milvus 查询索引记录数。
import io.milvus.v2.service.vector.request.QueryReq;
// 引入 SearchReq，用于构造 Milvus 向量召回请求。
import io.milvus.v2.service.vector.request.SearchReq;
// 引入 FloatVec，用于包装查询浮点向量。
import io.milvus.v2.service.vector.request.data.FloatVec;
// 引入 ArrayList，用于按插入顺序收集可变列表。
import java.util.ArrayList;
// 引入 List，用于有序分块、向量或命中集合。
import java.util.List;
// 引入 Map，用于索引字段、映射或请求参数。
import java.util.Map;
// 引入 Supplier，用于延迟创建连接或执行读锁操作。
import java.util.function.Supplier;

/** Milvus 2.6 SDK 的薄封装；客户端首次使用时才连接。 */
// 将 Milvus SDK 封装为统一可写向量存储，首次使用时才建立客户端。
public final class MilvusChunkStore implements WritableVectorChunkStore, AutoCloseable {
    // Milvus 主键字段名，对应知识子块稳定 ID。
    private static final String ID = "chunk_id";
    // 保存知识正文的字段名。
    private static final String TEXT = "text";
    // 保存引用来源路径的字段名。
    private static final String SOURCE = "source";
    // 保存分块在文件内序号的字段名。
    private static final String ORDINAL = "ordinal";
    // 用于近邻搜索的浮点向量字段名。
    private static final String VECTOR = "vector";
    // 当前应用使用的 Milvus 集合名称。
    private final String collection;
    // 延迟创建客户端的工厂，便于生产配置和测试注入。
    private final Supplier<MilvusClientV2> clientFactory;
    // 将 Java 浮点数组编码为 Milvus 所需的 JSON 数组。
    private final Gson gson = new Gson();
    // volatile 让懒加载客户端对其他调用线程可见。
    private volatile MilvusClientV2 client;

    // 根据服务地址、可选令牌和集合名配置真实 Milvus 存储。
    public MilvusChunkStore(String uri, String token, String collection) {
        // 通过工厂延迟构造客户端，空令牌按 SDK 要求转换为空字符串。
        this(collection, () -> new MilvusClientV2(ConnectConfig.builder().uri(uri).token(token == null ? "" : token)
                // 连接超时和单次 RPC 截止时间均设为 20 秒。
                .connectTimeoutMs(20_000).rpcDeadlineMs(20_000).build()));
    }

    // 提供包内构造方法，以注入客户端工厂隔离 Milvus 依赖。
    MilvusChunkStore(String collection, Supplier<MilvusClientV2> clientFactory) {
        // 保存目标集合名称。
        this.collection = collection;
        // 保存延迟创建客户端的工厂。
        this.clientFactory = clientFactory;
    }

    // 同步删除旧集合并按指定维度建立全新向量集合。
    @Override public synchronized void reset(int dimension) {
        // 取得当前可复用或新创建的客户端。
        MilvusClientV2 c = client();
        // 确认目标集合是否已经存在。
        if (c.hasCollection(HasCollectionReq.builder().collectionName(collection).build())) {
            // 存在时删除旧集合，使全量重建从空数据开始。
            c.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
        }
        // 开始构建具有固定字段的集合 Schema。
        var schema = c.createSchema()
                // 把 chunk_id 声明为 maxLength=64 的字符串字段。
                .addField(AddFieldReq.builder().fieldName(ID).dataType(DataType.VarChar).maxLength(64)
                        // 使用应用生成的主键，关闭 Milvus 自动 ID。
                        .isPrimaryKey(true).autoID(false).build())
                // 声明 maxLength=65535 的知识正文字符串字段。
                .addField(AddFieldReq.builder().fieldName(TEXT).dataType(DataType.VarChar).maxLength(65535).build())
                // 声明 maxLength=2048 的来源路径字符串字段。
                .addField(AddFieldReq.builder().fieldName(SOURCE).dataType(DataType.VarChar).maxLength(2048).build())
                // 将分块序号声明为 32 位整数字段。
                .addField(AddFieldReq.builder().fieldName(ORDINAL).dataType(DataType.Int32).build())
                // 将查询字段声明为指定维度的 FloatVector。
                .addField(AddFieldReq.builder().fieldName(VECTOR).dataType(DataType.FloatVector).dimension(dimension).build());
        // 为 vector 字段创建名为 vector_idx 的向量索引配置。
        IndexParam vectorIndex = IndexParam.builder().fieldName(VECTOR).indexName("vector_idx")
                // 由 Milvus 自动选择索引实现，并使用余弦相似度度量。
                .indexType(IndexParam.IndexType.AUTOINDEX).metricType(IndexParam.MetricType.COSINE).build();
        // 用固定集合名和已定义 Schema 构造创建请求。
        c.createCollection(CreateCollectionReq.builder().collectionName(collection).collectionSchema(schema)
                // 绑定向量索引并关闭动态字段，避免入库字段漂移。
                .indexParam(vectorIndex).enableDynamicField(false).build());
    }

    // 同步将本批知识子块与对应向量写入集合。
    @Override public synchronized void upsert(List<KnowledgeChunk> chunks, List<float[]> vectors) {
        // 要求知识条目和向量逐条对应，否则拒绝入库。
        if (chunks.size() != vectors.size()) throw new IllegalArgumentException("分块与向量数量不一致");
        // 空分块集合无需调用 Milvus。
        if (chunks.isEmpty()) return;
        // 按本批大小预分配待插入 JSON 记录列表。
        List<JsonObject> rows = new ArrayList<>(chunks.size());
        // 按下标遍历分块和向量配对。
        for (int i = 0; i < chunks.size(); i++) {
            // 取得当前知识子块。
            KnowledgeChunk chunk = chunks.get(i);
            // 创建一条 Milvus 入库记录。
            JsonObject row = new JsonObject();
            // 保存当前知识子块的稳定主键。
            row.addProperty(ID, chunk.chunkId());
            // 保存当前子块原始正文。
            row.addProperty(TEXT, chunk.text());
            // 保存正文的引用来源。
            row.addProperty(SOURCE, chunk.source());
            // 保存分块序号，供检索结果还原。
            row.addProperty(ORDINAL, chunk.ordinal());
            // 将同一下标的 float 数组转换为 JSON 向量字段。
            row.add(VECTOR, gson.toJsonTree(vectors.get(i)));
            // 把完整记录加入批量请求。
            rows.add(row);
        }
        // 向目标集合批量插入已经组装的记录。
        client().insert(InsertReq.builder().collectionName(collection).data(rows).build());
    }

    // 同步发布向量数据，使重建结果可被后续检索读取。
    @Override public synchronized void publish() {
        // 请求刷新目标集合，将插入数据持久化。
        client().flush(FlushReq.builder().collectionNames(List.of(collection)).build());
        // 同步加载集合，等待向量索引可用后才返回。
        client().loadCollection(LoadCollectionReq.builder().collectionName(collection).sync(true).build());
    }

    // 同步执行单查询向量的 TopK 近邻检索。
    @Override public synchronized List<SearchHit> search(float[] queryVector, int topK) {
        // 无效 TopK 返回空列表，避免不必要的远程请求。
        if (topK <= 0) return List.of();
        // 指定目标集合及向量字段，构造检索请求。
        var response = client().search(SearchReq.builder().collectionName(collection).annsField(VECTOR)
                // 使用余弦度量，包装一个查询向量并限制命中数量。
                .metricType(IndexParam.MetricType.COSINE).data(List.of(new FloatVec(queryVector))).limit(topK)
                // 要求强一致读取，同时返回正文、来源和序号元数据。
                .consistencyLevel(ConsistencyLevel.STRONG).outputFields(List.of(TEXT, SOURCE, ORDINAL)).build());
        // 没有查询结果时返回空列表。
        if (response.getSearchResults().isEmpty()) return List.of();
        // 按返回排名累积统一的命中对象。
        List<SearchHit> hits = new ArrayList<>();
        // 处理第一个也是唯一一个查询向量对应的结果集合。
        for (var result : response.getSearchResults().getFirst()) {
            // 取出当前命中的已返回字段映射。
            Map<String, Object> entity = result.getEntity();
            // 将 SDK 返回的主键统一转换为字符串。
            String id = String.valueOf(result.getId());
            // 将序号元数据转换为 Java int。
            int ordinal = ((Number) entity.get(ORDINAL)).intValue();
            // 根据主键与原正文还原业务分块。
            KnowledgeChunk chunk = new KnowledgeChunk(id, String.valueOf(entity.get(TEXT)),
                    // 为分块补齐来源和序号。
                    String.valueOf(entity.get(SOURCE)), ordinal);
            // 保留 Milvus 相关性分数，并标记向量召回通道。
            hits.add(new SearchHit(chunk, result.getScore(), "vector"));
        }
        // 以不可修改快照返回有序命中列表。
        return List.copyOf(hits);
    }

    // 同步查询集合实际记录数，供建库发布验证使用。
    @Override public synchronized long count() {
        // 集合不存在时视为零记录，支持首次启动。
        if (!client().hasCollection(HasCollectionReq.builder().collectionName(collection).build())) return 0;
        // 用空过滤条件查询全体记录的聚合计数。
        var response = client().query(QueryReq.builder().collectionName(collection).filter("")
                // 只返回 count(*) 字段，并使用强一致性保障计数准确。
                .outputFields(List.of("count(*)")).consistencyLevel(ConsistencyLevel.STRONG).build());
        // 无聚合响应时按零记录处理。
        if (response.getQueryResults().isEmpty()) return 0;
        // 读取聚合结果中的计数字段。
        Object value = response.getQueryResults().getFirst().getEntity().get("count(*)");
        // 兼容 SDK 返回 Number 或文本形式的数量。
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    // 获取懒加载的 Milvus 客户端。
    private MilvusClientV2 client() {
        // 先读取已缓存客户端引用，避免重复字段读取。
        MilvusClientV2 current = client;
        // 客户端尚未创建时调用工厂，并同时缓存和保存局部引用。
        if (current == null) client = current = clientFactory.get();
        // 返回本次操作可复用的客户端。
        return current;
    }

    // 同步释放 Milvus 客户端连接资源。
    @Override public synchronized void close() {
        // 只关闭实际创建过的客户端，不因关闭操作触发新连接。
        if (client != null) client.close();
    }
}
