package pe.buildshield.discrepancy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Gestión de Discrepancias de BuildShield (microservicio, puerto 8085, base discrepancy_db). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BuildshieldDiscrepancyApplication {

    public static void main(String[] args) {
        SpringApplication.run(BuildshieldDiscrepancyApplication.class, args);
    }
}
