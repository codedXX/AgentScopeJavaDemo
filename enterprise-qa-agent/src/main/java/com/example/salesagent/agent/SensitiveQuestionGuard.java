// 敏感问题检查器放在 agent 包，供入口和计划步骤共用。
package com.example.salesagent.agent;
// Pattern 预编译正则表达式，避免每次请求重复编译规则。
import java.util.regex.Pattern;
// 用固定规则对显式敏感请求进行拦截。
public final class SensitiveQuestionGuard {
    // 匹配读取、导出等动作附近的凭证或个人敏感字段；(?i) 忽略英文大小写。
    private static final Pattern SECRETS=Pattern.compile("(?i)(泄露|导出|显示|告诉|读取|获取|列出|打印|查看).{0,30}(密钥|密码|身份证|银行卡|个人手机号|api.?key|token|secret)");
    // 匹配绕过权限、忽略系统规则、破坏数据和指定疾病治愈承诺等请求。
    private static final Pattern DANGEROUS=Pattern.compile("(绕过|关闭).{0,12}(权限|鉴权|审计)|忽略.{0,12}(系统规则|系统指令)|删除.{0,10}(数据库|所有数据)|治愈.{0,8}(癌症|糖尿病)");
    // 返回拒绝理由；没有命中规则时返回 null，允许继续正常处理。
    public String rejection(String message) {
        // 请求凭证或个人敏感数据时给出固定拒绝文本。
        if(SECRETS.matcher(message).find()) return "该请求涉及凭证或个人敏感数据，无法提供；请通过授权的业务流程处理。";
        // 命中破坏性、越权或不实医疗承诺规则时拒绝执行。
        if(DANGEROUS.matcher(message).find()) return "无法执行绕过权限、破坏数据或未经证实的医疗承诺；可以查询有据可查的业务资料。";
        // 普通知识与业务问题继续交给意图识别和检索处理。
        return null;
    }
}
