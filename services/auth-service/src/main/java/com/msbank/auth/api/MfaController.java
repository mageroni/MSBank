package com.msbank.auth.api;

import com.msbank.auth.api.dto.MfaEnrollResponse;
import com.msbank.auth.application.MfaService;
import com.msbank.auth.config.AuthProperties;
import com.msbank.auth.infrastructure.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** MFA enrollment endpoint. */
@RestController
@RequestMapping("/api/v1/auth/mfa/totp")
public class MfaController {

    private final MfaService mfa;
    private final AuthProperties props;

    public MfaController(MfaService mfa, AuthProperties props) {
        this.mfa = mfa;
        this.props = props;
    }

    @PostMapping("/enroll")
    public MfaEnrollResponse enroll(@AuthenticationPrincipal AuthenticatedUser principal) {
        MfaService.EnrollResult r = mfa.enroll(principal.userId(), props.jwt().issuer());
        return new MfaEnrollResponse(r.secret(), r.otpAuthUri());
    }
}
