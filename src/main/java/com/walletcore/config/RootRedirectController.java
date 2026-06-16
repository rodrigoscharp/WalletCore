package com.walletcore.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootRedirectController {

    @GetMapping("/")
    public String root() {
        return "redirect:/swagger-ui.html";
    }
}
