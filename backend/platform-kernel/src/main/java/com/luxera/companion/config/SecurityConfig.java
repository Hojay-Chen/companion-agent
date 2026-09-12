package com.luxera.companion.config;

import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf().disable()
                .cors().and()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .authorizeRequests(auth -> auth
                        .antMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        .antMatchers("/api/health").permitAll()
                        .antMatchers("/error").permitAll()
                        // V10 §63: Simulator WebSocket 走自己的设备令牌鉴权(AUTH 帧), 不吃平台 JWT
                        .antMatchers(com.luxera.companion.contracts.dhcp.DhcpConstants.WS_PATH).permitAll()
                        // LAP §MCP: MCP 客户端**没有 JWT** —— 它是外部 Agent, 手里只有
                        // X-Mcp-Principal + 服务密钥, 由 McpPrincipalResolver 自己验。放在这里
                        // 不是为了放行, 而是因为 JWT 这一层表达不了 MCP 的身份: 留住 anyRequest()
                        // 的结果是每个 MCP 请求都在过滤器上变成 403, 连 initialize 都到不了控制器。
                        //
                        // permitAll 不等于敞开: McpController 第一步就是身份解析, 而服务密钥
                        // (app.lap.mcp.service-key) 为空时 resolver 拒绝一切请求 —— 没配密钥的部署上,
                        // /mcp 仍然是一个 403 的死端点, 只是回的是 JSON-RPC 形状的 403。
                        .antMatchers("/mcp").permitAll()
                        // LAP §Developer API: 开发者面与 MCP 同一个道理 —— "上架/写 manifest" 的身份
                        // 是**服务密钥**(APPLICATION/SYSTEM), JWT 这一层表达不了它。留在 anyRequest()
                        // 后面的话, 平台自己的调用会在过滤器上变成 403, 连控制器都到不了,
                        // 于是这两个端点看起来"已经实现"却谁都调不通。
                        //
                        // 只放行这两个具体方法与路径, 不是整个 /api/v1/applications/** —— 发现面
                        // (GET capabilities / applications / actions) 仍然在 anyRequest() 后面,
                        // 真人 JWT 照旧。鉴权本身在控制器第一步: 解析链认不出身份就 401, 认出来是
                        // 真人则被 ApplicationLifecycleService 以 LIFECYCLE_FORBIDDEN 拒成 403。
                        .antMatchers(HttpMethod.PATCH, "/api/v1/applications/*/status").permitAll()
                        .antMatchers(HttpMethod.PUT, "/api/v1/applications/*/versions/*/manifest").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
