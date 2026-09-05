package com.alibou.whatsappclone.status;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/status")
@RequiredArgsConstructor
@Tag(name = "Status")
public class StatusController {

    private final StatusService statusService;

    @PostMapping
    public ResponseEntity<StatusResponse> postText(@Valid @RequestBody PostTextStatusRequest request,
                                                   Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(statusService.postText(authentication.getName(), request.content()));
    }

    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StatusResponse> postMedia(@RequestParam("file") MultipartFile file,
                                                    Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(statusService.postMedia(authentication.getName(), file));
    }

    @GetMapping("/feed")
    public ResponseEntity<Map<String, List<StatusResponse>>> getFeed(Authentication authentication) {
        return ResponseEntity.ok(statusService.getFeed(authentication.getName()));
    }

    @DeleteMapping("/{status-id}")
    public ResponseEntity<Void> deleteStatus(@PathVariable("status-id") Long statusId,
                                             Authentication authentication) {
        statusService.deleteStatus(authentication.getName(), statusId);
        return ResponseEntity.noContent().build();
    }
}
