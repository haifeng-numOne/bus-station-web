package com.shuzhi.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Map;

/**
 * @author zgk
 * @description
 * @date 2019-07-08 10:32
 */
@Controller
@RequestMapping("")
public class test {

    @RequestMapping(value = "index")
    public String index(Map<String, Object> map) {
        return "index";
    }

    @RequestMapping(value = "index2")
    public String index2(Map<String, Object> map) {
        return "index2";
    }

    @RequestMapping(value = "led")
    public String led(Map<String, Object> map) {
        return "led";
    }
}

