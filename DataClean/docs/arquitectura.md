# Arquitectura de DataClean

DataClean se despliega en Docker Compose como dos procesos Spring Boot con el mismo código. La API sirve Angular y los endpoints HTTP. Cada worker, sin servidor web, reclama ejecuciones y procesa archivos. PostgreSQL almacena las definiciones, la cola, las incidencias y los metadatos de Spring Batch.

| Paquete | Responsabilidad |
| --- | --- |
| `api` | Validar solicitudes HTTP, registrar versiones, aceptar cargas, consultar estados y transmitir informes. |
| `frontend` | Compilar el panel Angular y servirlo desde Spring Boot. El navegador usa la misma API que la colección de Postman. |
| `schema` | Definir las reglas admitidas y almacenar versiones inmutables en JSONB. |
| `engine` | Convertir CSV, JSONL y XLSX en registros y aplicar el mismo motor de reglas. Recibe un destino para las incidencias y desconoce HTTP, Batch y PostgreSQL. |
| `run` | Guardar ejecuciones, coordinar el trabajo y ejecutar la validación con Spring Batch. |
| `db.migration` | Instalar tablas de aplicación y metadatos de Batch mediante Flyway. |

## Ciclo de ejecución

~~~mermaid
sequenceDiagram
    participant C as Cliente
    participant A as API
    participant P as PostgreSQL
    participant W as Planificador
    participant B as Spring Batch
    C->>A: POST archivo, formato y versión del esquema
    A->>P: Guardar bytes y estado QUEUED
    A-->>C: HTTP 202 y Location
    W->>P: Buscar pendientes y adquirir bloqueo de ejecución
    W->>P: Cambiar estado a RUNNING
    W->>B: Lanzar intento
    B->>P: Guardar incidencias y resultado en una transacción
    C->>A: GET estado
    A->>P: Leer recuentos y estado
    C->>A: GET issues.csv
    A->>P: Leer incidencias ordenadas con cursor
    A-->>C: CSV
~~~

La clave foránea vincula cada ejecución a la versión exacta del esquema. Dos escrituras concurrentes sobre la misma versión se coordinan mediante la clave primaria. Cuando la definición ya existe, la API compara ambas definiciones para decidir entre devolver la versión existente o devolver HTTP 409.

El planificador consulta ejecuciones pendientes en orden de llegada. Un bloqueo consultivo de PostgreSQL ligado a una transacción independiente protege cada ejecución entre instancias. Mientras sostiene el bloqueo, el planificador comprueba nuevamente que el estado admite procesamiento y lanza el job de Spring Batch. Si el proceso cae, la pérdida de la conexión libera el bloqueo. Otro planificador puede retomar el registro `RUNNING` con un nuevo intento desde el principio.

El paso bloquea además la fila de la ejecución mientras escribe sus resultados. Después valida los registros y escribe las incidencias por grupos de 500 en una misma transacción. El estado `COMPLETED`, los recuentos y las incidencias se confirman juntos. Si falla la lectura del archivo, se revierten las incidencias y se guarda `FAILED` en una operación posterior. En ambos estados terminales se descarta la carga original. Un fallo al lanzar el job deja la ejecución disponible para el siguiente ciclo del planificador.

Cada ejecución almacena el formato y, para Excel, el nombre opcional de la hoja. La migración V3 asigna `CSV` a las ejecuciones existentes, incluidas las pendientes. El lector CSV recorre registros y JSONL lee una línea cada vez. Para XLSX se comprueba el tamaño expandido antes de abrir el libro en memoria con Apache POI. Los tres lectores alimentan el mismo estado de validación, que mantiene los contadores y controla la unicidad por columna.

La exportación abre una transacción de solo lectura y recorre la clave primaria del informe por ejecución, fila e índice. El cursor lee grupos de 500 incidencias y el CSV se escribe directamente en la respuesta. El informe contiene códigos y nombres de columna del esquema, sin valores originales.

El motor calcula sugerencias mientras tiene acceso a los registros. Guarda únicamente los candidatos, la acción de revisión y la referencia a una fila anterior cuando procede. Los candidatos pueden derivarse de parte del dato de entrada, por ejemplo al convertir un decimal o una fecha. Se consultan con la vista previa.

La conservación de filas es optativa. Los resultados válidos y rechazados se escriben con las incidencias en la misma transacción y se exportan como JSONL. Tras siete días, las descargas caducan y una tarea elimina las filas conservadas. El historial y el informe de incidencias siguen disponibles.

La corrección asistida reconstruye un JSONL de las filas conservadas en el orden de entrada, aplica solo las ediciones confirmadas sobre filas rechazadas y encola una ejecución vinculada con el esquema de origen. La publicación de ambos informes es independiente. Una restricción única sobre la ejecución de origen y el hash de las ediciones ordenadas evita encolar un duplicado si el cliente reintenta la misma confirmación.

El historial consulta los metadatos por fecha de alta en orden descendente, con un filtro opcional por estado. Dos índices de PostgreSQL cubren la consulta general y la filtrada. La vista previa de una ejecución completada usa el mismo orden que la exportación y lee únicamente las primeras incidencias solicitadas.

## Decisiones y límites

- Guardar la carga y el estado inicial en la misma escritura evita perder trabajo aceptado entre el almacenamiento y el encolado. Cada carga se materializa en memoria al recibirla y al iniciar la validación, con un máximo de 20 MiB.
- Cada instancia procesa una ejecución a la vez. Una ejecución necesita una conexión para el bloqueo y conexiones adicionales para el job. Conserva un margen en el pool para consultas HTTP y descargas.
- El motor mantiene en memoria el número de la primera fila para cada valor de una columna con unicidad activada. Su tamaño crece con los valores distintos del archivo y se descarta al terminar el intento. Las reglas se guardan en el JSONB de cada versión.
- La transacción por archivo permite reiniciar desde el principio y publicar el resultado de forma atómica. Su duración y el volumen de escrituras crecen con la cantidad de incidencias.
- El alcance es una demo local para un único entorno de confianza. Compose publica el puerto en loopback. Las ejecuciones terminales y los metadatos de Batch permanecen en la base de datos.
- Flyway aplica los cambios antes de servir solicitudes. La migración de metadatos instala el esquema PostgreSQL de Spring Batch de la versión actual. Antes de subir de versión de Batch, corresponde incorporar sus scripts de actualización como migraciones explícitas.
