package io.virbius.control.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class OpsUiController {

    @GetMapping({"/ui", "/ui/", "/ops"})
    public String console() {
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
        return "redirect:/ops.html";
    }
}
