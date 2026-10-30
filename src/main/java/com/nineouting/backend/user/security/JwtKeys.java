package com.nineouting.backend.user.security;

import com.nineouting.backend.user.config.UserServiceProperties;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * access 토큰 서명용 RSA 키 쌍. 개인키는 환경 변수(JWT_PRIVATE_KEY, PKCS#8 PEM)로 받고 공개키는 개인키에서 계산한다.
 */
@Slf4j
@Getter
public class JwtKeys {

    private static final String ALGORITHM = "RSA";
    private static final int EPHEMERAL_KEY_SIZE = 2048;

    private final String keyId;
    private final RSAPrivateCrtKey privateKey;
    private final RSAPublicKey publicKey;

    private JwtKeys(String keyId, RSAPrivateCrtKey privateKey) throws GeneralSecurityException {
        this.keyId = keyId;
        this.privateKey = privateKey;
        this.publicKey = (RSAPublicKey) KeyFactory.getInstance(ALGORITHM)
                .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
    }

    public static JwtKeys from(UserServiceProperties.Jwt properties) {
        try {
            String pem = properties.privateKey();
            if (pem != null && !pem.isBlank()) {
                return new JwtKeys(properties.keyId(), parsePkcs8(pem));
            }
            if (!properties.allowEphemeralKey()) {
                throw new IllegalStateException("JWT_PRIVATE_KEY가 설정되지 않았습니다");
            }
            log.warn("JWT 개인키가 없어 임시 키를 만듭니다. 재시작하면 기존 토큰은 모두 무효가 됩니다 (로컬·테스트 전용)");
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            generator.initialize(EPHEMERAL_KEY_SIZE);
            return new JwtKeys(properties.keyId(), (RSAPrivateCrtKey) generator.generateKeyPair().getPrivate());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JWT 키를 읽을 수 없습니다", e);
        }
    }

    private static RSAPrivateCrtKey parsePkcs8(String pem) throws GeneralSecurityException {
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return (RSAPrivateCrtKey) KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
