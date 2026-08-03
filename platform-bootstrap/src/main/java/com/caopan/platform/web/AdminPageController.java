package com.caopan.platform.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 管理控制台页面入口（GEO-002）。
 */
@Controller
public class AdminPageController {

    /**
     * 打开 /admin 或 /admin/ 时转发到 index.html。
     */
    @GetMapping({"/admin", "/admin/"})
    public String adminIndex() {
        return "forward:/admin/index.html";
    }
}
