package pe.buildshield.discrepancy.support;

/** Bytes mínimos con la firma de cada formato de imagen. */
public final class TestImages {

    public static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    public static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};
    public static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
    public static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

    private TestImages() {
    }
}
