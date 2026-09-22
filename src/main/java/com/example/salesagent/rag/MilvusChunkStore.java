package com.example.salesagent.rag;

import com.example.salesagent.model.KnowledgeChunk;
import com.example.salesagent.model.SearchHit;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Milvus 2.6 SDK 的薄封装；客户端首次使用时才连接。 */
public final class MilvusChunkStore implements WritableVectorChunkStore, AutoCloseable {
    private static final String ID = "chunk_id";
    private static final String TEXT = "text";
    private static final String SOURCE = "source";
    private static final String ORDINAL = "ordinal";
    private static final String VECTOR = "vector";
    private final String collection;
    private final Supplier<MilvusClientV2> clientFactory;
    private final Gson gson = new Gson();
    private volatile MilvusClientV2 client;

    public MilvusChunkStore(String uri, String token, String collection) {
        this(collection, () -> new MilvusClientV2(ConnectConfig.builder().uri(uri).token(token == null ? "" : token)
                .connectTimeoutMs(20_000).rpcDeadlineMs(20_000).build()));
    }

    MilvusChunkStore(String collection, Supplier<MilvusClientV2> clientFactory) {
        this.collection = collection;
        this.clientFactory = clientFactory;
    }

    @Override public synchronized void reset(int dimension) {
        MilvusClientV2 c = client();
        if (c.hasCollection(HasCollectionReq.builder().collectionName(collection).build())) {
            c.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
        }
        var schema = c.createSchema()
                .addField(AddFieldReq.builder().fieldName(ID).dataType(DataType.VarChar).maxLength(64)
                        .isPrimaryKey(true).autoID(false).build())
                .addField(AddFieldReq.builder().fieldName(TEXT).dataType(DataType.VarChar).maxLength(65535).build())
                .addField(AddFieldReq.builder().fieldName(SOURCE).dataType(DataType.VarChar).maxLength(2048).build())
                .addField(AddFieldReq.builder().fieldName(ORDINAL).dataType(DataType.Int32).build())
                .addField(AddFieldReq.builder().fieldName(VECTOR).dataType(DataType.FloatVector).dimension(dimension).build());
        IndexParam vectorIndex = IndexParam.builder().fieldName(VECTOR).indexName("vector_idx")
                .indexType(IndexParam.IndexType.AUTOINDEX).metricType(IndexParam.MetricType.COSINE).build();
        c.createCollection(CreateCollectionReq.builder().collectionName(collection).collectionSchema(schema)
                .indexParam(vectorIndex).enableDynamicField(false).build());
    }

    @Override public synchronized void upsert(List<KnowledgeChunk> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) throw new IllegalArgumentException("分块与向量数量不一致");
        if (chunks.isEmpty()) return;
        List<JsonObject> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            JsonObject row = new JsonObject();
            row.addProperty(ID, chunk.chunkId());
            row.addProperty(TEXT, chunk.text());
            row.addProperty(SOURCE, chunk.source());
            row.addProperty(ORDINAL, chunk.ordinal());
            row.add(VECTOR, gson.toJsonTree(vectors.get(i)));
            rows.add(row);
        }
        client().insert(InsertReq.builder().collectionName(collection).data(rows).build());
    }

    @Override public synchronized void publish() {
        client().flush(FlushReq.builder().collectionNames(List.of(collection)).build());
        client().loadCollection(LoadCollectionReq.builder().collectionName(collection).sync(true).build());
    }

    @Override public synchronized List<SearchHit> search(float[] queryVector, int topK) {
        if (topK <= 0) return List.of();
        var response = client().search(SearchReq.builder().collectionName(collection).annsField(VECTOR)
                .metricType(IndexParam.MetricType.COSINE).data(List.of(new FloatVec(queryVector))).limit(topK)
                .consistencyLevel(ConsistencyLevel.STRONG).outputFields(List.of(TEXT, SOURCE, ORDINAL)).build());
        if (response.getSearchResults().isEmpty()) return List.of();
        List<SearchHit> hits = new ArrayList<>();
        for (var result : response.getSearchResults().getFirst()) {
            Map<String, Object> entity = result.getEntity();
            String id = String.valueOf(result.getId());
            int ordinal = ((Number) entity.get(ORDINAL)).intValue();
            KnowledgeChunk chunk = new KnowledgeChunk(id, String.valueOf(entity.get(TEXT)),
                    String.valueOf(entity.get(SOURCE)), ordinal);
            hits.add(new SearchHit(chunk, result.getScore(), "vector"));
        }
        return List.copyOf(hits);
    }

    @Override public synchronized long count() {
        if (!client().hasCollection(HasCollectionReq.builder().collectionName(collection).build())) return 0;
        var response = client().query(QueryReq.builder().collectionName(collection).filter("")
                .outputFields(List.of("count(*)")).consistencyLevel(ConsistencyLevel.STRONG).build());
        if (response.getQueryResults().isEmpty()) return 0;
        Object value = response.getQueryResults().getFirst().getEntity().get("count(*)");
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private MilvusClientV2 client() {
        MilvusClientV2 current = client;
        if (current == null) client = current = clientFactory.get();
        return current;
    }

    @Override public synchronized void close() {
        if (client != null) client.close();
    }
}
