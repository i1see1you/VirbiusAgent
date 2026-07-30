package io.virbius.control.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class OpsUiController {

    @GetMapping({"/ui", "/ui/"})
    public String console() {
        return "forward:/ui/index.html";
    }

    @GetMapping({"/ops", "/ops-legacy"})
    public String legacyOps() {
        return "redirect:/ops.html";
    }

    @GetMapping({
        "/ui/access-lists",
        "/ui/policies",
        "/ui/request-bindings",
        "/ui/registry",
        "/ui/rules",
        "/ui-hub.html",
        "/access-lists.html",
        "/policies.html"
    })
    public String legacyUi() {
        return "redirect:/ui/";
    }
}
