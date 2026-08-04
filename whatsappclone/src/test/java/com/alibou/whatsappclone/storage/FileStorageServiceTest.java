package com.alibou.whatsappclone.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private StorageProperties properties;

    @InjectMocks
    private FileStorageService fileStorageService;

    @Test
    void upload_rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> fileStorageService.upload(file, "u1", "c1"))
                .isInstanceOf(InvalidMediaException.class)
                .hasMessageContaining("empty");
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void upload_rejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "evil.exe", "application/octet-stream", new byte[]{1});

        assertThatThrownBy(() -> fileStorageService.upload(file, "u1", "c1"))
                .isInstanceOf(InvalidMediaException.class)
                .hasMessageContaining("not allowed");
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void upload_rejectsFileAboveCategoryLimit() {
        byte[] big = new byte[11 * 1024 * 1024]; // 11MB image, limit is 10MB
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);

        assertThatThrownBy(() -> fileStorageService.upload(file, "u1", "c1"))
                .isInstanceOf(InvalidMediaException.class)
                .hasMessageContaining("limit");
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void upload_storesValidFileAndReturnsMetadata() throws Exception {
        when(properties.bucket()).thenReturn("whatsapp-media");
        byte[] bytes = new byte[]{1, 2, 3, 4};
        MockMultipartFile file = new MockMultipartFile("file", "photo.PNG", "image/png", bytes);

        StoredFile stored = fileStorageService.upload(file, "user-1", "conv-1");

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));

        PutObjectRequest request = captor.getValue();
        assertThat(request.bucket()).isEqualTo("whatsapp-media");
        assertThat(request.key()).startsWith("users/user-1/conv-1/").endsWith(".png");
        assertThat(request.contentType()).isEqualTo("image/png");

        assertThat(stored.bucket()).isEqualTo("whatsapp-media");
        assertThat(stored.mimeType()).isEqualTo("image/png");
        assertThat(stored.sizeBytes()).isEqualTo(4);
    }

    @Test
    void presignedGetUrl_returnsGeneratedUrl() throws Exception {
        PresignedGetObjectRequest presigned = org.mockito.Mockito.mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create("http://localhost:9000/whatsapp-media/obj?X-Amz-Signature=abc").toURL());
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        String url = fileStorageService.presignedGetUrl("whatsapp-media", "users/u/obj.png");

        assertThat(url).startsWith("http://localhost:9000/").contains("X-Amz-Signature");
        verify(s3Presigner).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void mediaTypeValidator_detectsRealMimeTypes() {
        assertThat(MediaTypeValidator.mimeTypeFor("clip.mp4")).contains("video/mp4");
        assertThat(MediaTypeValidator.mimeTypeFor("track.MP3")).contains("audio/mpeg");
        assertThat(MediaTypeValidator.mimeTypeFor("noextension")).isEmpty();
        assertThat(MediaTypeValidator.categoryFor("image/jpeg")).contains(MediaTypeValidator.Category.IMAGE);
        assertThat(MediaTypeValidator.categoryFor("application/pdf")).contains(MediaTypeValidator.Category.DOCUMENT);
    }
}
