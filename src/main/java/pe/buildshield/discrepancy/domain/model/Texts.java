package pe.buildshield.discrepancy.domain.model;

import pe.buildshield.discrepancy.shared.error.ErrorDetail;
import pe.buildshield.discrepancy.shared.error.ValidationException;

import java.util.List;

/** Validación de los textos libres de la discrepancia: de 3 a 500 caracteres sin contar espacios de los extremos. */
public final class Texts {

    public static final int MIN = 3;
    public static final int MAX = 500;

    private Texts() {
    }

    public static String require(String value, String field) {
        String text = value == null ? "" : value.trim();
        if (text.length() < MIN || text.length() > MAX) {
            throw new ValidationException("INVALID_TEXT", "El campo " + field + " debe tener de 3 a 500 caracteres",
                    List.of(new ErrorDetail(field, "de 3 a 500 caracteres")));
        }
        return text;
    }
}
