// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;
// 引入知识分块、命中证据与检索结果等业务数据类型。
import com.example.salesagent.model.*;
// 引入 JDBC 连接、预编译参数绑定和数据库异常类型。
import java.sql.*;
// 引入集合、去重、排序和不可变结果构造工具。
import java.util.*;
/** 与 Milvus/FAISS 共用 child chunk 格式；参数绑定，固定表名。 */
// 通过 JDBC 实现 PGVector 向量存储，使用固定表结构和预编译参数绑定。
public final class PgVectorChunkStore implements WritableVectorChunkStore, AutoCloseable {
    // 数据库连接地址、用户名和密码，仅用于创建 JDBC 连接。
    private final String url, user, password;
    // 保存 PostgreSQL 连接配置，构造时暂不访问数据库。
    public PgVectorChunkStore(String url, String user, String password) { this.url=url; this.user=user; this.password=password; }
    // 用配置创建短生命周期连接，由各方法的 try-with-resources 关闭。
    private Connection connect() throws SQLException { return DriverManager.getConnection(url,user,password); }
    // 同步全量重建 pgvector 表及可用的向量索引。
    @Override public synchronized void reset(int dimension) {
        // 维度只允许 1 至 16000 的整数，防止无效 vector 类型声明。
        if (dimension < 1 || dimension > 16000) throw new IllegalArgumentException("向量维度不合法");
        // 同时自动管理数据库连接和 DDL 语句句柄。
        try (var c=connect(); var s=c.createStatement()) {
            // 关闭自动提交，让本次重建 DDL 在一个事务中提交。
            c.setAutoCommit(false);
            // 确保数据库安装 vector 扩展，已存在时不重复创建。
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            // 删除旧知识表，准备重建完整索引。
            s.execute("DROP TABLE IF EXISTS enterprise_chunks");
            // 建立固定字段知识表，主键是 64 字符子块 ID，向量列维度使用已校验整数。
            s.execute("CREATE TABLE enterprise_chunks (chunk_id varchar(64) PRIMARY KEY,text text NOT NULL,source text NOT NULL,ordinal int NOT NULL,embedding vector("+dimension+"))");
            // 标准 vector 类型维度不超过 2000 时建立余弦 HNSW 索引；更高维度保留精确扫描。
            if (dimension <= 2000) s.execute("CREATE INDEX ON enterprise_chunks USING hnsw (embedding vector_cosine_ops)");
            // 成功执行全部 DDL 后提交事务。
            c.commit();
        // 数据库初始化失败时报告 PGVector 状态异常。
        } catch (SQLException e) { throw new IllegalStateException("PGVector 初始化失败",e); }
    }
    // 把 float 数组转换为 pgvector 接受的方括号向量文本。
    static String vectorLiteral(float[] values) {
        // 准备保存各维度的十进制字符串。
        var parts=new ArrayList<String>();
        // 逐维拒绝 NaN 和无穷值，再使用 Float.toString 保留有效浮点表示。
        for(float value:values) { if(!Float.isFinite(value)) throw new IllegalArgumentException("向量包含无效值"); parts.add(Float.toString(value)); }
        // 以逗号连接各维度，并加上 pgvector 文本格式的方括号。
        return "["+String.join(",",parts)+"]";
    }
    // 同步批量新增或覆盖知识分块及其对应向量。
    @Override public synchronized void upsert(List<KnowledgeChunk> chunks,List<float[]> vectors) {
        // 要求分块和向量条数一致，避免错配正文和语义。
        if(chunks.size()!=vectors.size()) throw new IllegalArgumentException("分块数量不匹配");
        // 准备带参数的 INSERT；主键冲突时覆盖正文、来源、序号与向量。
        try(var c=connect(); var p=c.prepareStatement("INSERT INTO enterprise_chunks VALUES(?,?,?,?,?::vector) ON CONFLICT(chunk_id) DO UPDATE SET text=EXCLUDED.text,source=EXCLUDED.source,ordinal=EXCLUDED.ordinal,embedding=EXCLUDED.embedding")) {
            // 关闭自动提交，使整个批次在同一事务内写入。
            c.setAutoCommit(false);
            // 逐条取出知识分块，绑定稳定主键与正文参数。
            for(int i=0;i<chunks.size();i++) { var chunk=chunks.get(i); p.setString(1,chunk.chunkId()); p.setString(2,chunk.text());
                // 继续绑定来源、序号和向量文本，再加入 JDBC 批次。
                p.setString(3,chunk.source()); p.setInt(4,chunk.ordinal()); p.setString(5,vectorLiteral(vectors.get(i))); p.addBatch(); }
            // 执行全部参数化写入并提交事务。
            p.executeBatch(); c.commit();
        // 入库失败时向上层报告，连接关闭会结束未提交事务。
        } catch(SQLException e) { throw new IllegalStateException("PGVector 入库失败",e); }
    }
    // PGVector 数据在事务提交后即可查询，因此 publish 无需额外动作。
    @Override public void publish() {}
    // 按余弦距离检索最相关的 topK 个知识子块。
    @Override public List<SearchHit> search(float[] vector,int topK) {
        // 非正 TopK 不执行数据库查询。
        if(topK<=0) return List.of();
        // 预编译检索 SQL：用 1 减余弦距离计算相关性，并按距离升序排名。
        try(var c=connect();var p=c.prepareStatement("SELECT chunk_id,text,source,ordinal,1-(embedding <=> ?::vector) AS score FROM enterprise_chunks ORDER BY embedding <=> ?::vector LIMIT ?")) {
            // 分别绑定分数计算与排序的查询向量，再绑定最大命中条数。
            p.setString(1,vectorLiteral(vector));p.setString(2,vectorLiteral(vector));p.setInt(3,topK);
            // 准备按数据库返回排名收集结果。
            var result=new ArrayList<SearchHit>();
            // 自动关闭结果集，逐行还原知识分块及 score，并标记 pgvector 通道。
            try(var r=p.executeQuery()) { while(r.next()) result.add(new SearchHit(new KnowledgeChunk(r.getString(1),r.getString(2),r.getString(3),r.getInt(4)),r.getDouble(5),"pgvector")); }
            // 返回按余弦相似度排名的统一命中列表。
            return result;
        // 查询数据库失败时保留原异常并报告 PGVector 检索失败。
        } catch(SQLException e) { throw new IllegalStateException("PGVector 检索失败",e); }
    }
    // 查询当前知识表记录数，供全量建库后的一致性验证使用。
    @Override public long count() {
        // 自动关闭连接、语句和结果集，读取 count(*) 的第一列。
        try(var c=connect();var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM enterprise_chunks")) {r.next();return r.getLong(1);}
        // SQLSTATE 42P01 表示表尚未创建，返回零；其他数据库错误向上抛出。
        catch(SQLException e) {if("42P01".equals(e.getSQLState())) return 0;throw new IllegalStateException("PGVector 计数失败",e);}
    }
    // 每次操作自行关闭 JDBC 资源，没有长期客户端需要释放。
    @Override public void close() {}
}
