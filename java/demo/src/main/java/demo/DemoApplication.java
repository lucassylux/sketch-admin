package demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例应用：只依赖 starter，不写任何管理后台代码——
 * 启动即有 /api/auth/**、/api/dicts、/api/users、/api/audit、/api/settings/** 全套端点。
 * 业务代码照常加自己的 @RestController。
 */
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
