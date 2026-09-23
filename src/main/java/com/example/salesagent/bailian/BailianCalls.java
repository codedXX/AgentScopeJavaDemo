package com.example.salesagent.bailian;

import com.alibaba.dashscope.exception.ApiException;
import java.util.concurrent.Callable;

/** 只对限流/临时服务错误重试，认证与输入错误立即报告；不泄露上游响应中的敏感内容。 */
final class BailianCalls {
    private BailianCalls() {}
    static <T> T call(Callable<T> action) {
        for (int attempt = 0; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("请求已取消");
            try { return action.call(); }
            catch (ApiException ex) {
                int code = ex.getStatus() == null ? -1 : ex.getStatus().getStatusCode();
                if (attempt >= 2 || (code != 429 && code < 500))
                    throw new IllegalStateException("百炼请求失败，HTTP 状态 " + code + "，请检查模型权限、地域或服务状态");
                try { Thread.sleep(250L << attempt); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("请求已取消"); }
            } catch (RuntimeException ex) { throw ex; }
            catch (Exception ex) { throw new IllegalStateException("百炼调用失败，请检查配置和连接", ex); }
        }
    }
    /** 启动调用前检查是否配置了密钥。 */
    static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalStateException("请先配置 DASHSCOPE_API_KEY");
    }
}
