package com.example.controller.web;

import com.example.controller.domain.StoredObject;
import com.example.controller.service.DownloadService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/objects")
public class ObjectDownloadController {

    private final DownloadService downloadService;

    public ObjectDownloadController(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String id) {
        StoredObject object = downloadService.getCompleteObjectOrThrow(id);
        downloadService.preflightCheck(id);

        StreamingResponseBody body = outputStream -> downloadService.streamObject(object.getId(), outputStream);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(object.getContentType()))
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(object.getSize()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + object.getName() + "\"")
                .body(body);
    }
}
