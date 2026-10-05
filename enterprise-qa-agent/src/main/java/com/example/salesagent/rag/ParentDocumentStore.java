// 声明所属包，组织 com.example.salesagent.rag 的类型并避免类名冲突。
package com.example.salesagent.rag;
// 引入 KnowledgeChunk，用于知识子块正文、来源与稳定标识。
import com.example.salesagent.model.KnowledgeChunk;
// 引入 ObjectMapper，用于JSON 序列化与反序列化。
import com.fasterxml.jackson.databind.ObjectMapper;
// 引入路径、文件读写及原子替换操作。
import java.nio.file.*;
// 引入集合、去重、排序和不可变结果构造工具。
import java.util.*;
// 维护父文档及子块映射，并通过 JSON 快照跨进程恢复。
public final class ParentDocumentStore {
    // 不可变父文档记录包含父块 ID、引用来源和完整正文。
    public record Parent(String id, String source, String text) {}
    // 持久化快照同时保存父块内容与子块到父块的 ID 映射。
    public record Snapshot(Map<String, Parent> parents, Map<String, String> children) {}
    // 父子映射 JSON 清单的磁盘位置。
    private final Path file;
    // 对父文档快照进行 JSON 编解码。
    private final ObjectMapper mapper = new ObjectMapper();
    // 按插入顺序保存父块，多个子块可复用同一父块记录。
    private final Map<String, Parent> parents = new LinkedHashMap<>();
    // 保存 childId 到 parentId 的映射，支持从命中子块追溯上下文。
    private final Map<String, String> children = new LinkedHashMap<>();
    // 创建父文档存储，并尝试加载已有映射清单。
    public ParentDocumentStore(Path file) {
        // 保存当前清单文件路径。
        this.file = file;
        // 已有普通文件时才尝试恢复，首次创建可以没有清单。
        if (Files.isRegularFile(file)) {
            // 把 JSON 文件还原为父子映射快照。
            try { var data = mapper.readValue(file.toFile(), Snapshot.class);
                // 分别恢复父块内容与子块关联关系。
                parents.putAll(data.parents()); children.putAll(data.children());
            // 损坏清单明确报错，避免静默使用不完整映射。
            } catch (Exception e) { throw new IllegalStateException("父文档清单损坏，请修复或删除后重建", e); }
        }
    }
    // 同步清空父块与子块映射，供全量重建使用。
    public synchronized void reset() { parents.clear(); children.clear(); }
    // 同步返回子块映射数量，与实际索引记录数核对。
    public synchronized int childCount() { return children.size(); }
    // 同步登记一个子块所属的父文档。
    public synchronized void add(String childId, Parent parent) {
        // 保存或更新父块内容，并记录当前 childId 对应的 parentId。
        parents.put(parent.id(), parent); children.put(childId, parent.id());
    }
    // 同步把召回子块扩展为包含完整父块正文的证据。
    public synchronized KnowledgeChunk expand(KnowledgeChunk child) {
        // 先由 childId 查 parentId，再取得父块内容。
        var parent = parents.get(children.get(child.chunkId()));
        // 没有映射时保留原子块；有映射时保留子块 ID 与序号并替换为父块正文及来源。
        return parent == null ? child : new KnowledgeChunk(child.chunkId(), parent.text(), parent.source(), child.ordinal());
    }
    // 同步把当前完整父子映射发布到磁盘。
    public synchronized void publish() {
        // 把目录创建、JSON 写入与文件替换纳入统一异常处理。
        try {
            // 确保父文档清单所在的绝对父目录存在。
            Files.createDirectories(file.toAbsolutePath().getParent());
            // 创建与目标清单同目录的临时文件路径。
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            // 先完整写入两个映射组成的 Snapshot。
            mapper.writeValue(tmp.toFile(), new Snapshot(parents, children));
            // 优先采用原子替换，让读取方只看到完整旧文件或新文件。
            try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            // 文件系统不支持原子移动时回退到普通替换移动。
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        // 将目录、写入或替换失败统一报告为父文档保存失败。
        } catch (Exception e) { throw new IllegalStateException("保存父文档失败", e); }
    }
}
