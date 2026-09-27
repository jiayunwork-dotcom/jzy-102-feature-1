package com.geoviz.terrain.api;

import com.geoviz.terrain.erosion.ErosionSimulator;
import com.geoviz.terrain.noise.NoiseGenerator;
import com.geoviz.terrain.steady.SteadyStateDetector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 装配各算法模块为 Spring Bean，并放开 /api 的跨域（便于本地开发直连）。
 */
@Configuration
public class BackendConfig {

    @Bean
    public NoiseGenerator noiseGenerator() {
        return new NoiseGenerator();
    }

    @Bean
    public ErosionSimulator erosionSimulator() {
        return new ErosionSimulator();
    }

    /**
     * 河网稳态判定阈值（相邻两帧归一化汇流场的总变差距离，低于它即视为稳态）。
     * 可经 terrain.steady-threshold 覆盖。
     */
    @Bean
    public SteadyStateDetector steadyStateDetector(
            @Value("${terrain.steady-threshold:0.02}") double steadyThreshold) {
        return new SteadyStateDetector(steadyThreshold);
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("*")
                        .allowedMethods("GET", "POST");
            }
        };
    }
}
