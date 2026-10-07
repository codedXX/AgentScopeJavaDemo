// 会话文件存储属于 agent 包，服务于会话恢复与历史压缩。
package com.example.salesagent.agent;
// ObjectMapper 负责把会话 record 与 JSON 文件互相转换。
import com.fasterxml.jackson.databind.ObjectMapper;
// 引入 Path、Files、原子移动及文件复制选项。
import java.nio.file.*;
// UTF-8 编码确保同一会话 ID 的哈希跨平台一致。
import java.nio.charset.StandardCharsets;
// MessageDigest 提供 SHA-256 会话文件名哈希。
import java.security.MessageDigest;
// 使用 List、HexFormat 等集合与十六进制格式工具。
import java.util.*;
/** 每个会话独立文件，原子替换；ID 只用哈希作文件名。 */
// 定义无子类扩展的本地会话持久化组件。
public final class PersistentConversationStore {
    // 用不可变记录保存一条历史消息。
    public record Turn(
            // 消息角色为 user 或 assistant。
            String role,
            // 保存原始问题或最终回答的文本。
            String text,
            List<String> imageIds) {
        public Turn {
            imageIds = imageIds == null ? List.of() : List.copyOf(imageIds);
        }
        public Turn(String role, String text) { this(role, text, List.of()); }
    }
    // 一个会话文件包含压缩摘要和近期完整轮次。
    public record Conversation(
            // 已压缩的早期历史摘要。
            String summary,
            // 尚未压缩的有序历史消息。
            List<Turn> turns) {}
    // 保存会话 JSON 文件的目录。
    private final Path dir;
    // 每个存储实例使用一个 JSON 映射器读写会话记录。
    private final ObjectMapper mapper=new ObjectMapper();
    // 由配置或测试注入会话存储目录。
    public PersistentConversationStore(Path dir) {
        // 保存目录引用，直到首次保存时再创建实际目录。
        this.dir=dir;
    }
    // 将外部会话 ID 转为固定目录下的安全文件路径。
    private Path path(String id) {
        // 将哈希计算失败统一包装为运行时异常。
        try {
            // UTF-8 编码后计算 SHA-256，再以十六进制字符串作文件名，阻止路径穿越。
            return dir.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)))+".json");
        // 把文件、序列化或哈希异常转为调用方可统一处理的运行时异常。
        } catch(Exception e) {
            // 将哈希或路径构造失败包装为统一运行时异常。
            throw new IllegalStateException(e);
        }
    }
    // 加载指定会话，未保存过的会话返回空摘要与空历史。
    public Conversation load(String id) {
        // 使用 ID 哈希定位会话 JSON 文件。
        Path file=path(id);
        // 文件不存在表示新会话，无需访问模型或创建文件。
        if(!Files.exists(file)) return new Conversation("",List.of());
        // 在受控异常边界内读取并解析会话 JSON。
        try {
            // 将文件中的 JSON 反序列化为 Conversation record。
            return mapper.readValue(file.toFile(),Conversation.class);
        // 把文件、序列化或哈希异常转为调用方可统一处理的运行时异常。
        } catch(Exception e) {
            // 读取或 JSON 解析失败时保留原因，并告知调用方恢复失败。
            throw new IllegalStateException("会话记录无法读取",e);
        }
    }
    // 持久化会话，先写临时文件再替换，减少部分写入损坏原记录的风险。
    public void save(String id,Conversation conversation) {
        // 保护完整保存过程，任何文件操作失败都向调用方报告。
        try {
            // 首次保存时创建会话目录及缺失的父目录。
            Files.createDirectories(dir);
            // 计算目标 JSON 文件路径。
            Path file=path(id);
            // 在同目录创建临时文件，使原子移动处于同一文件系统。
            Path tmp=Files.createTempFile(dir,"session-",".tmp");
            // 临时文件写入后必须进入 finally 清理路径。
            try {
                // 先完整序列化到临时文件，暂不覆盖当前会话文件。
                mapper.writeValue(tmp.toFile(),conversation);
                // 优先尝试原子替换，针对不支持的文件系统提供回退。
                try {
                    // 原子替换目标文件，读者只会看到完整旧版或完整新版。
                    Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                // 识别当前文件系统缺少原子移动能力，改用普通替换。
                } catch(AtomicMoveNotSupportedException e) {
                    // 文件系统不支持原子移动时使用普通替换继续完成保存。
                    Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);
                }
            // 临时文件不论保存成功或失败都要尝试清理。
            } finally {
                // 成功移动或发生异常后，都尝试清理剩余临时文件。
                Files.deleteIfExists(tmp);
            }
        // 把文件、序列化或哈希异常转为调用方可统一处理的运行时异常。
        } catch(Exception e) {
            // 保存过程的任一失败都传递给上层，避免误报持久化成功。
            throw new IllegalStateException("会话保存失败",e);
        }
    }
}
