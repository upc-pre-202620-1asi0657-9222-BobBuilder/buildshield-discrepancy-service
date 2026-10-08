# language: es
Característica: US36 Registro de discrepancia con evidencia
  Como encargado de obra
  quiero que cada diferencia de la recepción quede como discrepancia con evidencia
  para que el almacén la responda y se cierre con una decisión registrada

  Antecedentes:
    Dado que en la organización trabajan "Ana Torres" como "administrador"
    Y que en la organización trabajan "Rosa Quispe" como "encargado de almacén"
    Y que en la organización trabajan "Luis Ramos" como "encargado de obra"
    Y que en la organización trabajan "Pedro Soto" como "encargado de obra"
    Y que "Luis Ramos" está asignado a "Torre Norte"
    Y que "Pedro Soto" está asignado a "Edificio Sur"
    Y que "Rosa Quispe" está asignado a "Almacén Central"
    Y que el catálogo tiene el material "Cemento Portland" con SKU "CEM-001" y tolerancia "2.5" %
    Y que el catálogo tiene el material "Arena gruesa" con SKU "ARE-001" y tolerancia "5" %
    Y que "Luis Ramos" abrió la recepción del despacho "MAN-0001" del "Almacén Central" a "Torre Norte"

  Escenario: Al confirmar la recepción se abre una discrepancia por cada material fuera de tolerancia
    Cuando "Luis Ramos" confirma la recepción de "MAN-0001" con:
      | sku     | material         | despachado | recibido | tolerancia |
      | CEM-001 | Cemento Portland | 40         | 37       | 2.5        |
      | ARE-001 | Arena gruesa     | 10         | 10       | 5          |
    Y "Luis Ramos" consulta las discrepancias
    Entonces ve 1 discrepancia
    Y ve la discrepancia "Faltante" de "CEM-001" por "3" en estado "Abierta"
    Y se publicó el evento "DiscrepancyOpened" de la discrepancia de "CEM-001"

  Escenario: Repetir la confirmación no duplica las discrepancias
    Cuando "Luis Ramos" confirma la recepción de "MAN-0001" con:
      | sku     | material         | despachado | recibido | tolerancia |
      | CEM-001 | Cemento Portland | 40         | 37       | 2.5        |
    Y la confirmación de "MAN-0001" llega otra vez
    Y "Ana Torres" consulta las discrepancias
    Entonces ve 1 discrepancia

  Escenario: El almacén responde y la obra acepta la respuesta
    Cuando "Luis Ramos" confirma la recepción de "MAN-0001" con:
      | sku     | material         | despachado | recibido | tolerancia |
      | CEM-001 | Cemento Portland | 40         | 37       | 2.5        |
    Y "Rosa Quispe" consulta las discrepancias en estado "OPEN"
    Entonces ve la discrepancia "Faltante" de "CEM-001" por "3" en estado "Abierta"
    Cuando "Rosa Quispe" "responde" la discrepancia de "CEM-001" con el texto "Se repone mañana"
    Entonces la discrepancia queda en estado "Respondida"
    Cuando "Luis Ramos" "acepta" la discrepancia de "CEM-001" con el texto ""
    Entonces la discrepancia queda en estado "Aceptada"
    Y se publicó el evento "DiscrepancyAccepted" de la discrepancia de "CEM-001"

  Escenario: La obra escala y el administrador resuelve con una decisión registrada
    Cuando "Luis Ramos" confirma la recepción de "MAN-0001" con:
      | sku     | material         | despachado | recibido | tolerancia |
      | CEM-001 | Cemento Portland | 40         | 37       | 2.5        |
    Y "Ana Torres" consulta las discrepancias
    Entonces ve la discrepancia "Faltante" de "CEM-001" por "3" en estado "Abierta"
    Cuando "Rosa Quispe" "responde" la discrepancia de "CEM-001" con el texto "Se repone mañana"
    Y "Luis Ramos" "escala" la discrepancia de "CEM-001" con el texto "La reposición no llegó"
    Entonces la discrepancia queda en estado "Escalada"
    Cuando "Ana Torres" "resuelve" la discrepancia de "CEM-001" con el texto "Se descuenta al transportista"
    Entonces la discrepancia queda en estado "Resuelta"
    Y la discrepancia tiene la respuesta "Se repone mañana", el escalamiento "La reposición no llegó" y la decisión "Se descuenta al transportista"

  Escenario: La obra registra un daño dentro de la tolerancia con foto de evidencia
    Cuando "Luis Ramos" registra la discrepancia "Daño" de "CEM-001" por "2" en "MAN-0001" con la observación "Dos bolsas rotas en la descarga"
    Entonces la respuesta tiene estado 201
    Cuando "Luis Ramos" repite el registro de la discrepancia de "CEM-001" con la misma clave
    Y "Luis Ramos" sube la foto "bolsas-rotas.jpg" de tipo "image/jpeg" a la discrepancia de "CEM-001"
    Entonces la respuesta tiene estado 201
    Cuando "Luis Ramos" consulta la discrepancia de "CEM-001"
    Entonces la discrepancia tiene 1 evidencia y la foto se puede descargar

  Escenario: Solo se aceptan fotos JPG, PNG o WEBP
    Cuando "Luis Ramos" registra la discrepancia "Daño" de "CEM-001" por "2" en "MAN-0001" con la observación "Dos bolsas rotas en la descarga"
    Y "Luis Ramos" sube la foto "guia.pdf" de tipo "application/pdf" a la discrepancia de "CEM-001"
    Entonces la respuesta tiene estado 400 y código "EVIDENCE_TYPE_NOT_ALLOWED"

  Escenario: Una transición inválida es un conflicto y un rol incorrecto no tiene permiso
    Cuando "Luis Ramos" confirma la recepción de "MAN-0001" con:
      | sku     | material         | despachado | recibido | tolerancia |
      | CEM-001 | Cemento Portland | 40         | 37       | 2.5        |
    Y "Ana Torres" consulta las discrepancias
    Entonces ve la discrepancia "Faltante" de "CEM-001" por "3" en estado "Abierta"
    Cuando "Luis Ramos" "acepta" la discrepancia de "CEM-001" con el texto ""
    Entonces la respuesta tiene estado 409 y código "INVALID_DISCREPANCY_TRANSITION"
    Cuando "Luis Ramos" "resuelve" la discrepancia de "CEM-001" con el texto "No le toca"
    Entonces la respuesta tiene estado 403

  Escenario: Cada encargado solo ve las discrepancias de sus obras o almacenes
    Cuando "Luis Ramos" confirma la recepción de "MAN-0001" con:
      | sku     | material         | despachado | recibido | tolerancia |
      | CEM-001 | Cemento Portland | 40         | 37       | 2.5        |
    Y "Pedro Soto" consulta las discrepancias
    Entonces ve 0 discrepancias
    Cuando "Ana Torres" consulta las discrepancias
    Entonces ve la discrepancia "Faltante" de "CEM-001" por "3" en estado "Abierta"
    Cuando "Pedro Soto" consulta la discrepancia de "CEM-001"
    Entonces la respuesta tiene estado 404
