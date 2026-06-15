package com.researchgateway.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Forwards client-side (Angular) routes to the SPA entry point so deep links work.
 * Paths containing a dot (static assets) and /api routes are excluded.
 */
@Controller
public class SpaForwardController {

    @RequestMapping(value = {"/", "/{path:[^\\.]*}", "/{path:^(?!api$).*}/{sub:[^\\.]*}"})
    public String forward() {
        return "forward:/index.html";
    }
}
