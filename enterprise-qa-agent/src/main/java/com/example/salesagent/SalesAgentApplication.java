// 声明应用根包，Spring 默认从这里向下扫描组件。
package com.example.salesagent;

// 导入 demo 配置记录，使入口可以注册其属性绑定。
import com.example.salesagent.config.DemoProperties;
// 导入 Spring Boot 启动器，负责创建应用上下文与嵌入式服务器。
import org.springframework.boot.SpringApplication;
// 导入自动配置与组件扫描组合注解。
import org.springframework.boot.autoconfigure.SpringBootApplication;
// 导入配置属性注册注解，将 record 绑定为 Spring Bean。
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 同一个应用通过 app / mcp-server 两个 profile 启动不同职责。 */
// 启用 Spring Boot 自动配置和根包下的组件扫描。
@SpringBootApplication
// 同时注册 demo 与 enterprise 两组配置属性，供后续 Bean 工厂注入。
@EnableConfigurationProperties({DemoProperties.class, com.example.salesagent.config.EnterpriseProperties.class})
// 定义整个企业问答服务的 Java 启动入口。
public class SalesAgentApplication {
    // JVM 从 main 进入，将命令行参数交给 Spring Boot 处理。
    public static void main(String[] args) {
        // 启动应用，按当前 profile 创建问答服务或 MCP 服务所需组件。
        SpringApplication.run(SalesAgentApplication.class, args);
    }
}
