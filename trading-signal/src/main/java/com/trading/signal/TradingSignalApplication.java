package com.trading.signal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot 应用入口
 *
 * @EnableScheduling 开启定时任务支持（替代原来的 while + Thread.sleep）
 */
@SpringBootApplication
@EnableScheduling
public class TradingSignalApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingSignalApplication.class, args);
    }
}
