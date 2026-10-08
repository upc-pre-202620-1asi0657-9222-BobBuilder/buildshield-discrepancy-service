/**
 * Microservicio Gestión de Discrepancias (contexto core): discrepancias de la recepción con evidencia, respuesta del
 * almacén, aceptación o escalamiento y resolución. Publica en {@code buildshield.discrepancy} y consume
 * {@code buildshield.organization} y {@code buildshield.ordering}.
 *
 * <p>Capas: {@code interfaces} (REST) → {@code application} (casos de uso) → {@code domain} (Java puro);
 * {@code infrastructure} implementa los puertos (JPA, JDBC, Kafka, almacenamiento de evidencias). {@code shared} es
 * el kernel compartido copiado de buildshield-iam-service.
 */
package pe.buildshield.discrepancy;
