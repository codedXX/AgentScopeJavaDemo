// 声明所属包，组织 com.example.salesagent.bailian 的类型并避免类名冲突。
package com.example.salesagent.bailian;

// 引入 ApiException，用于读取百炼上游 HTTP 失败状态。
import com.alibaba.dashscope.exception.ApiException;
// 引入 Callable，用于以可抛受检异常的回调封装模型请求。
import java.util.concurrent.Callable;

/** 只对限流/临时服务错误重试，认证与输入错误立即报告；不泄露上游响应中的敏感内容。 */
// 集中处理百炼调用的有限重试、取消和凭证校验。
final class BailianCalls {
    // 静态工具类无需实例，私有构造器限制外部创建。
    private BailianCalls() {}
    // 以泛型回调统一包装不同模型的 SDK 调用，同时保留结果类型。
    static <T> T call(Callable<T> action) {
        // 维护从零开始的尝试次数，由成功返回或异常传播终止循环。
        for (int attempt = 0; ; attempt++) {
            // 线程已取消时直接停止模型调用。
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("请求已取消");
            // 执行本次 SDK 请求，成功即返回模型结果。
            try { return action.call(); }
            // 单独处理包含百炼 HTTP 状态的 API 异常。
            catch (ApiException ex) {
                // 从响应状态提取 HTTP 码；缺失状态时使用 -1 标记。
                int code = ex.getStatus() == null ? -1 : ex.getStatus().getStatusCode();
                // 最多尝试三次；除 429 或服务器错误外，认证和输入类错误立即终止。
                if (attempt >= 2 || (code != 429 && code < 500))
                    // 只报告 HTTP 码及配置排查提示，避免暴露上游响应中的敏感内容。
                    throw new IllegalStateException("百炼请求失败，HTTP 状态 " + code + "，请检查模型权限、地域或服务状态");
                // 按 250ms、500ms 指数退避后重试，降低限流时的连续请求压力。
                try { Thread.sleep(250L << attempt); }
                // 退避等待被取消时恢复线程中断标志并停止重试。
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("请求已取消"); }
            // 其他运行时异常原样传播，保留原有错误类型。
            } catch (RuntimeException ex) { throw ex; }
            // 把回调的其他受检异常包装为连接或配置问题。
            catch (Exception ex) { throw new IllegalStateException("百炼调用失败，请检查配置和连接", ex); }
        }
    }
    // 在调用远程模型之前检查必需的 API Key。
    static void requireKey(String key) {
        // 缺少密钥时给出环境变量配置提示，避免发送无效认证请求。
        if (key == null || key.isBlank()) throw new IllegalStateException("请先配置 DASHSCOPE_API_KEY");
    }
}
