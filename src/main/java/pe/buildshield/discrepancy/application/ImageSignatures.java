package pe.buildshield.discrepancy.application;

/** Firma de los primeros bytes de cada formato de imagen aceptado como evidencia. */
final class ImageSignatures {

    private ImageSignatures() {
    }

    static boolean matches(String contentType, byte[] content) {
        return switch (contentType) {
            case "image/jpeg" -> startsWith(content, 0, 0xFF, 0xD8, 0xFF);
            case "image/png" -> startsWith(content, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/webp" -> startsWith(content, 0, 'R', 'I', 'F', 'F') && startsWith(content, 8, 'W', 'E', 'B', 'P');
            default -> false;
        };
    }

    private static boolean startsWith(byte[] content, int offset, int... signature) {
        if (content.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((content[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
