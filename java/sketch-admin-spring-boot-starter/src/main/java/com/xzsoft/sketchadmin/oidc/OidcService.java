package com.xzsoft.sketchadmin.oidc;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.jwk.JWKSet;
import com.xzsoft.sketchadmin.store.AdminStore;

import jakarta.servlet.http.HttpServletRequest;

/**
 * OIDC SSO（授权码 + PKCE S256），语义与 go-admin/oidc.go 一致：
 * 未配置休眠；state 挂起 10 分钟；白名单制；账号按 sub 绑定。
 * 换码走标准 OAuth2 表单 POST + JSON 解析；ID token 用 JWKS 验签。
 */
public class OidcService {

    /** SSO 交换被拒（白名单外等）——上层 403 */
    public static class NotAllowedException extends RuntimeException {
        public NotAllowedException(String msg) { super(msg); }
    }

    public record SsoIdentity(String username, String subject) {}

    private record Pending(String verifier, Instant expires) {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AdminStore store;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    // 发现文档缓存（按配置值失效）
    private volatile String cachedKey = "";
    private volatile Map<String, Object> discovery;

    public OidcService(AdminStore store) { this.store = store; }

    // ---------- 配置口径 ----------

    private String issuer() { return store.settingGet("oidc_issuer").trim(); }
    private String clientId() { return store.settingGet("oidc_client_id").trim(); }
    private String clientSecret() { return store.settingGet("oidc_client_secret").trim(); }

    public boolean enabled() {
        return !issuer().isEmpty() && !clientId().isEmpty()
                && !store.settingGet("oidc_allowed_users").trim().isEmpty();
    }

    // ---------- 发现 ----------

    private synchronized Map<String, Object> discovery() throws IOException, InterruptedException {
        String key = issuer() + "|" + clientId() + "|" + clientSecret();
        if (discovery != null && key.equals(cachedKey)) return discovery;
        String url = issuer().replaceAll("/+$", "") + "/.well-known/openid-configuration";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("发现端点 " + resp.statusCode());
        discovery = JSON.readValue(resp.body(), new TypeReference<Map<String, Object>>() {});
        cachedKey = key;
        return discovery;
    }

    public String endSessionEndpoint() throws Exception {
        return String.valueOf(discovery().getOrDefault("end_session_endpoint", ""));
    }

    // ---------- 发起 ----------

    public String authorizeUrl(String prompt, HttpServletRequest req) throws Exception {
        if (issuer().isEmpty() || clientId().isEmpty()) throw new IllegalStateException("OIDC 未配置");
        String authorize = String.valueOf(discovery().get("authorization_endpoint"));
        String verifier = randomToken(48);
        String state = randomToken(32);
        pending.put(state, new Pending(verifier, Instant.now().plus(STATE_TTL)));

        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId());
        params.put("redirect_uri", redirectUri(req));
        params.put("scope", "openid profile email");
        params.put("state", state);
        params.put("code_challenge", s256(verifier));
        params.put("code_challenge_method", "S256");
        if (prompt != null && (prompt.equals("login") || prompt.equals("consent") || prompt.equals("select_account"))) {
            params.put("prompt", prompt);
        }
        String qs = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return authorize + (authorize.contains("?") ? "&" : "?") + qs;
    }

    private String redirectUri(HttpServletRequest req) {
        String base = store.settingGet("oidc_redirect_base").trim();
        if (!base.isEmpty()) return base.replaceAll("/+$", "") + "/oidc/callback";
        String scheme = req.getHeader("X-Forwarded-Proto");
        if (scheme == null) scheme = req.getScheme();
        return scheme + "://" + req.getServerName()
                + (req.getServerPort() == 80 || req.getServerPort() == 443 ? "" : ":" + req.getServerPort())
                + "/oidc/callback";
    }

    // ---------- 回调 ----------

    public SsoIdentity exchange(String code, String state, HttpServletRequest req) throws Exception {
        Pending p = state == null ? null : pending.remove(state);
        if (p == null || Instant.now().isAfter(p.expires())) {
            throw new IllegalArgumentException("登录状态已过期，请重新发起 SSO 登录");
        }
        Map<String, Object> disc = discovery();

        // 换 token：标准 OAuth2 表单 POST（PKCE code_verifier 必带；机密客户端带 Basic Auth）
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code == null ? "" : code);
        form.put("redirect_uri", redirectUri(req));
        form.put("client_id", clientId());
        form.put("code_verifier", p.verifier());
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(String.valueOf(disc.get("token_endpoint"))))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (!clientSecret().isEmpty()) {
            String basic = Base64.getEncoder().encodeToString(
                    (clientId() + ":" + clientSecret()).getBytes(StandardCharsets.UTF_8));
            rb.header("Authorization", "Basic " + basic);
        }
        HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IllegalArgumentException("SSO 授权码交换失败");
        Map<String, Object> token = JSON.readValue(resp.body(), new TypeReference<Map<String, Object>>() {});
        String idTokenStr = token.get("id_token") == null ? null : token.get("id_token").toString();
        if (idTokenStr == null) throw new IllegalArgumentException("Provider 未返回 id_token");

        // ID token：JWKS 验签 + iss/aud/exp 校验
        SignedJWT jwt = SignedJWT.parse(idTokenStr);
        JWKSet jwkSet = JWKSet.load(URI.create(String.valueOf(disc.get("jwks_uri"))).toURL());
        var jwk = jwkSet.getKeyByKeyId(jwt.getHeader().getKeyID());
        if (jwk == null) throw new IllegalArgumentException("ID token 签名密钥未找到");
        JWSVerifier verifier;
        if (jwk.toRSAKey() != null) {
            RSAPublicKey pub = jwk.toRSAKey().toRSAPublicKey();
            verifier = pub == null ? null : new RSASSAVerifier(pub);
        } else {
            ECPublicKey pub = jwk.toECKey().toECPublicKey();
            verifier = pub == null ? null : new ECDSAVerifier(pub);
        }
        if (verifier == null || !jwt.verify(verifier)) throw new IllegalArgumentException("ID token 校验失败");

        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        if (!issuer().equals(claims.getIssuer())) throw new IllegalArgumentException("ID token issuer 不匹配");
        if (claims.getExpirationTime() == null || Instant.now().isAfter(claims.getExpirationTime().toInstant())) {
            throw new IllegalArgumentException("ID token 已过期");
        }
        if (claims.getAudience().isEmpty() || !clientId().equals(claims.getAudience().get(0))) {
            throw new IllegalArgumentException("ID token audience 不匹配");
        }
        String sub = claims.getSubject();
        String preferred = stringClaim(claims, "preferred_username");
        String email = stringClaim(claims, "email");
        String identity = preferred != null ? preferred : (email != null ? email : sub);
        if (identity == null || sub == null) throw new IllegalArgumentException("Provider 未返回可用身份标识");
        if (!allowed(identity)) throw new NotAllowedException("该 SSO 账号未在白名单中");
        return new SsoIdentity(identity, sub);
    }

    private static String stringClaim(JWTClaimsSet claims, String key) {
        try {
            String v = claims.getStringClaim(key);
            return v == null || v.isBlank() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 白名单 ----------

    private boolean allowed(String identity) {
        String raw = store.settingGet("oidc_allowed_users");
        for (String item : raw.split("[,;\\s\\n\\t\\r]+")) {
            if (item.trim().equalsIgnoreCase(identity)) return true;
        }
        return false;
    }

    // ---------- 助手 ----------

    private static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String s256(String verifier) {
        try {
            byte[] sum = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sum);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
