package com.msbank.auth.infrastructure.security;

import com.msbank.auth.config.AuthProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads (or generates on first boot) the RSA keypair used to sign JWTs.
 * Keys are persisted in PEM form at the paths configured via {@code JWT_*_KEY_PATH}.
 */
@Component
public class RsaKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(RsaKeyProvider.class);

    private final AuthProperties props;
    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;

    public RsaKeyProvider(AuthProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() throws Exception {
        Path privPath = Path.of(props.jwt().privateKeyPath());
        Path pubPath = Path.of(props.jwt().publicKeyPath());

        if (Files.exists(privPath) && Files.exists(pubPath)) {
            log.info("Loading RSA keypair from {}", privPath);
            this.privateKey = readPrivate(privPath);
            this.publicKey = readPublic(pubPath);
        } else {
            log.warn("RSA keypair not found at {} — generating new 2048-bit RSA keypair", privPath);
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            this.privateKey = (RSAPrivateKey) pair.getPrivate();
            this.publicKey = (RSAPublicKey) pair.getPublic();
            writePem(privPath, "PRIVATE KEY", privateKey.getEncoded());
            writePem(pubPath, "PUBLIC KEY", publicKey.getEncoded());
        }
    }

    public RSAPrivateKey privateKey() { return privateKey; }
    public RSAPublicKey publicKey() { return publicKey; }
    public String keyId() { return props.jwt().keyId(); }

    private static RSAPrivateKey readPrivate(Path path) throws Exception {
        byte[] der = pemToDer(Files.readString(path));
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static RSAPublicKey readPublic(Path path) throws Exception {
        byte[] der = pemToDer(Files.readString(path));
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static byte[] pemToDer(String pem) {
        String body = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static void writePem(Path path, String label, byte[] der) throws IOException {
        Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        String pem = "-----BEGIN " + label + "-----\n" + b64 + "\n-----END " + label + "-----\n";
        Files.writeString(path, pem);
        try {
            Files.setPosixFilePermissions(path,
                    java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX FS (e.g. Windows) — skip
        }
    }
}
