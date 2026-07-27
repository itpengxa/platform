package com.caopan.platform.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 提供 /api 下的便捷页面入口。
 */
@Controller
public class ApiPageController {

    /**
     * 直接打开 /api 或 /api/ 时，转发到验证页。
     *
     * @return forward 到静态页面
     */
    @GetMapping({"/api", "/api/"})
    public String apiIndex() {
        return "forward:/geo-validator.html";
    }

    /**
     * 便捷打开验证页。
     *
     * @return forward 到验证页
     */
    @GetMapping("/api/validator")
    public String validator() {
        return "forward:/geo-validator.html";
    }

    /**
     * 便捷打开选择器页。
     *
     * @return redirect 到选择器页
     */
    @GetMapping("/api/picker")
    public String picker() {
        return "redirect:/api/geo-picker.html?clientCode=demo&clientName=Demo&lang=en";
    }
}
