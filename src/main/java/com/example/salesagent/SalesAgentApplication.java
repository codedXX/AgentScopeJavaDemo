package com.example.salesagent;

import com.example.salesagent.config.DemoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 同一个应用通过 app / mcp-server 两个 profile 启动不同职责。 */
@SpringBootApplication
@EnableConfigurationProperties(DemoProperties.class)
public class SalesAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(SalesAgentApplication.class, args);
    }
}
