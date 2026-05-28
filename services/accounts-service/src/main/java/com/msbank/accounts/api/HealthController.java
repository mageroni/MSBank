package com.msbank.accounts.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping(path = "/healthz", produces = MediaType.APPLICATION_JSON_VALUE)
    public String healthz() { return "{\"status\":\"ok\"}"; }

    @GetMapping(path = "/readyz", produces = MediaType.APPLICATION_JSON_VALUE)
    public String readyz() { return "{\"status\":\"ready\"}"; }
}
