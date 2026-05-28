package com.msbank.auth.unit;

import com.msbank.auth.application.PasswordValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordValidatorTest {

    private final PasswordValidator v = new PasswordValidator();

    @Test
    void rejectsShortPasswords() {
        assertThat(v.validate("Aa1!short")).contains("at least 12");
    }

    @Test
    void rejectsMissingUppercase() {
        assertThat(v.validate("abcdefgh1!aa")).contains("uppercase");
    }

    @Test
    void rejectsMissingLowercase() {
        assertThat(v.validate("ABCDEFGH1!AA")).contains("lowercase");
    }

    @Test
    void rejectsMissingDigit() {
        assertThat(v.validate("Abcdefghij!!")).contains("digit");
    }

    @Test
    void rejectsMissingSymbol() {
        assertThat(v.validate("Abcdefghij12")).contains("symbol");
    }

    @Test
    void acceptsStrongPassword() {
        assertThat(v.isValid("Str0ng-Pa55word!")).isTrue();
    }

    @Test
    void rejectsNull() {
        assertThat(v.validate(null)).isNotNull();
    }
}
