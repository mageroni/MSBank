package com.msbank.auth.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.net.URI;

/** RFC 7807 Problem Details payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
        URI type,
        String title,
        int status,
        String detail,
        String instance,
        String traceId
) {}
