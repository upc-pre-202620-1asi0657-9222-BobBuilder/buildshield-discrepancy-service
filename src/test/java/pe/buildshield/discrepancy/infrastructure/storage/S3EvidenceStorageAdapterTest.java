package pe.buildshield.discrepancy.infrastructure.storage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3EvidenceStorageAdapterTest {

    private static final String KEY = "org/discrepancia/foto.png";

    private final S3Client s3 = mock(S3Client.class);
    private final S3EvidenceStorageAdapter adapter = new S3EvidenceStorageAdapter(s3, "evidencias");

    @Test
    @SuppressWarnings("unchecked")
    void guarda_en_el_bucket_con_la_clave_y_el_tipo_de_contenido() {
        String stored = adapter.store(KEY, "image/png", new byte[] {1, 2});

        ArgumentCaptor<Consumer<PutObjectRequest.Builder>> request = ArgumentCaptor.forClass(Consumer.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        PutObjectRequest.Builder builder = PutObjectRequest.builder();
        request.getValue().accept(builder);
        assertThat(builder.build().bucket()).isEqualTo("evidencias");
        assertThat(builder.build().key()).isEqualTo(KEY);
        assertThat(builder.build().contentType()).isEqualTo("image/png");
        assertThat(stored).isEqualTo(KEY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void lee_el_contenido_del_objeto() {
        when(s3.getObjectAsBytes(any(Consumer.class))).thenReturn(
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[] {7}));

        assertThat(adapter.load(KEY)).hasValue(new byte[] {7});

        ArgumentCaptor<Consumer<GetObjectRequest.Builder>> request = ArgumentCaptor.forClass(Consumer.class);
        verify(s3).getObjectAsBytes(request.capture());
        GetObjectRequest.Builder builder = GetObjectRequest.builder();
        request.getValue().accept(builder);
        assertThat(builder.build().bucket()).isEqualTo("evidencias");
        assertThat(builder.build().key()).isEqualTo(KEY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void un_objeto_que_no_existe_devuelve_vacio() {
        when(s3.getObjectAsBytes(any(Consumer.class))).thenThrow(NoSuchKeyException.builder().build());

        assertThat(adapter.load(KEY)).isEmpty();
    }
}
