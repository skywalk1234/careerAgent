package group.career_backend.security;

import cn.hutool.jwt.JWT;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.InputStream;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtToolTest {

    private static final String KEYSTORE_PASSWORD = System.getenv()
            .getOrDefault("JWT_KEYSTORE_PASSWORD", "hmall123");

    @Test
    void tokenKeepsRs256UserClaimAndOneDayExpiry() throws Exception {
        KeyPair keyPair = loadKeyPair();
        JwtTool jwtTool = new JwtTool(keyPair);
        long before = System.currentTimeMillis();

        String token = jwtTool.createToken(123L, Duration.ofDays(1));
        JWT jwt = JWT.of(token);

        assertThat(jwt.getHeader("alg")).isEqualTo("RS256");
        assertThat(jwt.getPayload("user").toString()).isEqualTo("123");
        assertThat(jwtTool.parseToken(token)).isEqualTo(123L);

        Date expiresAt = jwt.getPayload().getClaimsJson().getDate("exp", null);
        assertThat(expiresAt).isNotNull();
        assertThat(expiresAt.getTime() - before)
                .isBetween(Duration.ofDays(1).minusSeconds(5).toMillis(),
                        Duration.ofDays(1).plusSeconds(5).toMillis());
    }

    @Test
    void interceptorExtractsUserIdFromBearerToken() throws Exception {
        JwtTool jwtTool = new JwtTool(loadKeyPair());
        JwtUserInterceptor interceptor = new JwtUserInterceptor(jwtTool);
        String token = jwtTool.createToken(111L, Duration.ofDays(1));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/users/me/profile/parse-jobs");
        request.addHeader("Authorization", "Bearer " + token);

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        assertThat(UserContext.getUserId(request)).isEqualTo(111L);
        assertThat(UserContext.isAuthenticated(request)).isTrue();
    }

    @Test
    void interceptorUsesFallbackUserWhenTokenIsMissing() throws Exception {
        JwtUserInterceptor interceptor = new JwtUserInterceptor(new JwtTool(loadKeyPair()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/users/me/profile/parse-jobs");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(allowed).isTrue();
        assertThat(UserContext.getUserId(request)).isEqualTo(23L);
        assertThat(UserContext.isAuthenticated(request)).isFalse();
    }

    private KeyPair loadKeyPair() throws Exception {
        char[] password = KEYSTORE_PASSWORD.toCharArray();
        try (InputStream inputStream = new ClassPathResource("hmall.jks").getInputStream()) {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(inputStream, password);
            Key key = keyStore.getKey("hmall", password);
            return new KeyPair(keyStore.getCertificate("hmall").getPublicKey(), (PrivateKey) key);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }
}
