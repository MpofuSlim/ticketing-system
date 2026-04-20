package com.innbucks.eventservice.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Slf4j
public class H2ConsoleRedirectController {

    @GetMapping("/h2-console")
    public String redirectToTrailingSlash() {
        log.debug("Redirecting /h2-console to /h2-console/");
        return "redirect:/h2-console/";
    }
}

