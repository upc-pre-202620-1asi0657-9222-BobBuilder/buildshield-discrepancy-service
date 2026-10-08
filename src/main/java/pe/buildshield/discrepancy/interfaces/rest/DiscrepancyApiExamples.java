package pe.buildshield.discrepancy.interfaces.rest;

/** Ejemplos de respuesta y de parámetros para la documentación OpenAPI de discrepancy-service. */
final class DiscrepancyApiExamples {

    static final String DISCREPANCY_ID = "4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b";
    static final String EVIDENCE_ID = "7b8c9d0e-1f2a-4b3c-8d4e-5f6a7b8c9d0e";
    static final String WORKSITE_ID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";
    static final String DISPATCH_ID = "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c";

    static final String DISCREPANCY = """
            {
              "id": "4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b",
              "organizationId": "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b",
              "receptionId": "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
              "dispatchId": "6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c",
              "manifestCode": "MAN-20261103-0001",
              "worksiteId": "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
              "warehouseId": "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
              "materialId": "4d5e6f7a-8b9c-4d0e-8f1a-2b3c4d5e6f7a",
              "sku": "CEM-001",
              "materialName": "Cemento Portland tipo I",
              "type": "SHORTAGE",
              "typeLabel": "Faltante",
              "quantity": 3.000,
              "shrinkagePercent": 7.50,
              "tolerancePercent": 2.50,
              "observation": "Despachado 40, recibido 37: merma de 7.5 % sobre una tolerancia de 2.5 %",
              "status": "RESPONDED",
              "statusLabel": "Respondida",
              "openedBy": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f",
              "openedAt": "2026-11-03T16:10:00Z",
              "response": {
                "text": "Se repone mañana con el siguiente despacho",
                "by": "8a9b0c1d-2e3f-4a5b-8c6d-7e8f9a0b1c2d",
                "at": "2026-11-03T17:00:00Z"
              },
              "escalation": null,
              "resolution": null,
              "evidences": [
                {
                  "id": "7b8c9d0e-1f2a-4b3c-8d4e-5f6a7b8c9d0e",
                  "url": "/api/v1/discrepancies/4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b/evidences/7b8c9d0e-1f2a-4b3c-8d4e-5f6a7b8c9d0e/content",
                  "fileName": "bolsas-rotas.jpg",
                  "uploadedBy": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f",
                  "uploadedAt": "2026-11-03T16:12:00Z"
                }
              ]
            }""";

    static final String DISCREPANCIES = "[" + DISCREPANCY + "]";

    static final String EVIDENCE = """
            {
              "id": "7b8c9d0e-1f2a-4b3c-8d4e-5f6a7b8c9d0e",
              "url": "/api/v1/discrepancies/4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b/evidences/7b8c9d0e-1f2a-4b3c-8d4e-5f6a7b8c9d0e/content",
              "fileName": "bolsas-rotas.jpg",
              "uploadedBy": "1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f",
              "uploadedAt": "2026-11-03T16:12:00Z"
            }""";

    private DiscrepancyApiExamples() {
    }
}
