package com.example.controller.web;

import com.example.controller.service.ListService;
import com.example.controller.web.dto.ObjectSummary;
import com.example.controller.web.dto.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/objects")
public class ObjectListController {

    private final ListService listService;

    public ObjectListController(ListService listService) {
        this.listService = listService;
    }

    @GetMapping
    public PageResponse<ObjectSummary> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return listService.list(page, size);
    }
}
