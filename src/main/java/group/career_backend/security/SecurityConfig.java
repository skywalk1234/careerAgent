package group.career_backend.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public KeyPair keyPair(JwtProperties properties) {
        char[] password = properties.getPassword().toCharArray();
        try (InputStream inputStream = properties.getLocation().getInputStream()) {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(inputStream, password);

            Key key = keyStore.getKey(properties.getAlias(), password);
            Certificate certificate = keyStore.getCertificate(properties.getAlias());
            if (!(key instanceof PrivateKey privateKey) || certificate == null) {
                throw new IllegalStateException("JWT 密钥库中未找到有效的 RSA 密钥对");
            }
            PublicKey publicKey = certificate.getPublicKey();
            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            throw new IllegalStateException("加载 JWT 密钥库失败", e);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }
}
