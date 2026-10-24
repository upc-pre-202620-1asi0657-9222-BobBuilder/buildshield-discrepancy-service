package pe.buildshield.discrepancy.architecture.fixtures.gamma.interfaces;

import pe.buildshield.discrepancy.architecture.fixtures.gamma.domain.GammaRepository;

/** Clase de ejemplo: un controlador que usa un repositorio sin pasar por la aplicación (violación). */
public class GammaController {

    private final GammaRepository repository = new GammaRepository();

    public String get() {
        return repository.find();
    }
}
