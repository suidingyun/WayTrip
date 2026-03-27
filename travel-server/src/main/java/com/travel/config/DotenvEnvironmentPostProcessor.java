package com.travel.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 ConfigData 解析 application.yml 之前加载 {@code .env} / {@code .env.local}（KEY=VALUE，后者覆盖前者），
 * 使 {@code ${DASHSCOPE_API_KEY}} 等占位符可被解析。不会写入或修改磁盘上的任何 env 文件。
 * <p>
 * Spring Boot 自带的 {@code spring.config.import=file:.env} 往往无法按 Properties 解析 .env 扩展名，
 * 从终端执行 {@code mvn spring-boot:run} 时 Key 会丢失。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DotenvEnvironmentPostProcessor.class);

    private static final String PROPERTY_SOURCE_NAME = "waytripDotenv";

    /**
     * 在 {@link com.travel.TravelApplication#main} 中先于 {@code SpringApplication.run} 调用，
     * 确保灵积等组件能通过 {@link System#getProperty} 读到 {@code spring.ai.dashscope.*}（不依赖 EPP 是否被加载）。
     */
    public static void applyDotenvToSystemProperties() {
        Map<String, Object> map = loadDotEnv();
        mirrorDashScopeIntoSpringAiProperties(map);

        String dashKey = firstNonBlank(
                getStr(map, "DASHSCOPE_API_KEY"),
                getStr(map, "AI_DASHSCOPE_API_KEY"),
                getStr(map, "SPRING_AI_DASHSCOPE_API_KEY"));
        if (StringUtils.hasText(dashKey)) {
            // Spring Boot relaxed binding：与操作系统环境变量 SPRING_AI_DASHSCOPE_API_KEY 等价（灵积文档亦提及 AI_DASHSCOPE_API_KEY）
            System.setProperty("SPRING_AI_DASHSCOPE_API_KEY", dashKey.trim());
            System.setProperty("spring.ai.dashscope.api-key", dashKey.trim());
            System.setProperty("spring.ai.dashscope.chat.api-key", dashKey.trim());
            System.setProperty("spring.ai.dashscope.embedding.api-key", dashKey.trim());
        }

        for (Map.Entry<String, Object> e : map.entrySet()) {
            String k = e.getKey();
            if (k == null || k.isEmpty()) {
                continue;
            }
            Object val = e.getValue();
            String v = val == null ? "" : val.toString();
            if (v.isEmpty()) {
                continue;
            }
            if (System.getProperty(k) == null) {
                System.setProperty(k, v);
            }
        }
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> map = loadDotEnv();
        mirrorDashScopeIntoSpringAiProperties(map);
        if (!map.isEmpty()) {
            if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
                environment.getPropertySources().remove(PROPERTY_SOURCE_NAME);
            }
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, map));
        }
    }

    /**
     * 灵积自动配置使用 {@code spring.ai.dashscope} / {@code spring.ai.dashscope.chat} /
     * {@code spring.ai.dashscope.embedding}；代码回退仅读 {@code AI_DASHSCOPE_API_KEY} 环境变量，与 .env 中常见命名不一致。
     * 此处把 Key 写入 Spring 认识的属性名，避免仅靠 ${DASHSCOPE_API_KEY} 占位符解析顺序导致仍为空。
     */
    static void mirrorDashScopeIntoSpringAiProperties(Map<String, Object> map) {
        String v = firstNonBlank(
                getStr(map, "DASHSCOPE_API_KEY"),
                getStr(map, "AI_DASHSCOPE_API_KEY"),
                getStr(map, "SPRING_AI_DASHSCOPE_API_KEY"));
        if (!StringUtils.hasText(v)) {
            return;
        }
        map.putIfAbsent("spring.ai.dashscope.api-key", v);
        map.putIfAbsent("spring.ai.dashscope.chat.api-key", v);
        map.putIfAbsent("spring.ai.dashscope.embedding.api-key", v);
        map.putIfAbsent("AI_DASHSCOPE_API_KEY", v);
    }

    private static String getStr(Map<String, Object> map, String key) {
        Object o = map.get(key);
        return o == null ? null : o.toString();
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String s : vals) {
            if (StringUtils.hasText(s)) {
                return s.trim();
            }
        }
        return null;
    }

    /**
     * 按顺序合并多个文件，同名 KEY 后者覆盖前者。顺序意图：模板 .env 可被本机 .env.local 覆盖（.env.local 已在 .gitignore）。
     * 后端不会写回任何 .env 文件；若密钥「消失」，请检查编辑器未保存、云同步冲突、误用 .env.example 或还原了模板。
     */
    static Map<String, Object> loadDotEnv() {
        Path base = Path.of(System.getProperty("user.dir", "."));
        Path[] files = new Path[] {
                base.resolve(".env"),
                base.resolve("travel-server").resolve(".env"),
                base.resolve(".env.local"),
                base.resolve("travel-server").resolve(".env.local"),
        };
        Map<String, Object> map = new LinkedHashMap<>();
        boolean any = false;
        for (Path p : files) {
            if (Files.isRegularFile(p)) {
                any = true;
                mergeEnvFileIntoMap(map, p);
                log.debug("Merged dotenv from {}", p.toAbsolutePath());
            }
        }
        if (!any) {
            log.warn(
                    "No .env/.env.local found under {} (checked .env, travel-server/.env, .env.local, travel-server/.env.local). "
                            + "Set OS env e.g. SPRING_AI_DASHSCOPE_API_KEY or add travel-server/.env.local.",
                    base.toAbsolutePath());
        }
        return map;
    }

    private static void mergeEnvFileIntoMap(Map<String, Object> map, Path path) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (!lines.isEmpty() && !lines.get(0).isEmpty() && lines.get(0).charAt(0) == '\uFEFF') {
                lines.set(0, lines.get(0).substring(1));
            }
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String val = trimmed.substring(eq + 1).trim();
                if (val.length() >= 2
                        && ((val.startsWith("\"") && val.endsWith("\""))
                                || (val.startsWith("'") && val.endsWith("'")))) {
                    val = val.substring(1, val.length() - 1);
                }
                if (!key.isEmpty()) {
                    map.put(key, val);
                }
            }
        } catch (IOException ignored) {
            // optional file
        }
    }
}
