package com.msbank.auth.application;

import com.msbank.auth.domain.MfaSecret;
import com.msbank.auth.domain.User;
import com.msbank.auth.infrastructure.jpa.MfaSecretRepository;
import com.msbank.auth.infrastructure.jpa.UserRepository;
import com.msbank.auth.infrastructure.outbox.OutboxWriter;
import dev.samstevens.totp.code.*;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** TOTP enrollment + verification using dev.samstevens.totp. */
@Service
public class MfaService {

    private final UserRepository userRepo;
    private final MfaSecretRepository mfaRepo;
    private final OutboxWriter outbox;
    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final TimeProvider time = new SystemTimeProvider();
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator();

    public MfaService(UserRepository userRepo, MfaSecretRepository mfaRepo, OutboxWriter outbox) {
        this.userRepo = userRepo;
        this.mfaRepo = mfaRepo;
        this.outbox = outbox;
    }

    public record EnrollResult(String secret, String otpAuthUri) {}

    @Transactional
    public EnrollResult enroll(UUID userId, String issuer) {
        User user = userRepo.findById(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
        String secret = secretGenerator.generate();

        MfaSecret mfa = mfaRepo.findByUserId(userId).orElseGet(MfaSecret::new);
        mfa.setUserId(userId);
        mfa.setSecretBase32(secret);
        mfa.setConfirmed(true); // simplified: enrolling enables MFA immediately
        mfaRepo.save(mfa);

        user.setMfaEnabled(true);
        userRepo.save(user);

        QrData data = new QrData.Builder()
                .label(user.getEmail())
                .secret(secret)
                .issuer(issuer)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();

        outbox.write("user", user.getId(), "UserMfaEnabled", Map.of(
                "userId", user.getId().toString(),
                "email", user.getEmail(),
                "firstName", user.getFirstName(),
                "lastName", user.getLastName()
        ));

        return new EnrollResult(secret, data.getUri());
    }

    /** @return true if {@code code} is currently valid for the user's secret. */
    public boolean verify(UUID userId, String code) {
        if (code == null || code.isBlank()) return false;
        return mfaRepo.findByUserId(userId)
                .map(secret -> {
                    DefaultCodeVerifier v = new DefaultCodeVerifier(codeGenerator, time);
                    v.setAllowedTimePeriodDiscrepancy(1);
                    return v.isValidCode(secret.getSecretBase32(), code);
                })
                .orElse(false);
    }
}
