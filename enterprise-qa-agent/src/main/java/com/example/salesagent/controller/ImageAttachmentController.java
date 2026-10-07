package com.example.salesagent.controller;

import com.example.salesagent.attachment.ImageAttachmentStore;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("app")
public class ImageAttachmentController {
    private final ImageAttachmentStore images;

    public ImageAttachmentController(ImageAttachmentStore images) {
        this.images = images;
    }

    @PostMapping(value = "/api/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImageAttachmentStore.Uploaded upload(@RequestParam String sessionId,
            @RequestParam("file") MultipartFile file) throws IOException {
        return images.upload(sessionId, file.getBytes());
    }

    @GetMapping("/api/sessions/{sessionId}/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable String sessionId, @PathVariable String imageId) {
        var stored = images.load(sessionId, imageId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(stored.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore()).body(stored.bytes());
    }
}
