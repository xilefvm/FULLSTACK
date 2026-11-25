# Prueba de rendimiento y recuperación

La prueba usa Docker Desktop y el generador incluido en el proyecto. El CSV contiene 250.000 filas, 18.638.911 bytes y un 10 % de filas inválidas. La columna de identificadores exige unicidad. Las descargas opcionales de filas estaban desactivadas.

## API separada y dos workers

El generador identificó al worker ocupado por su conexión a PostgreSQL, lo detuvo durante la primera escritura de incidencias y dejó que la otra réplica terminara la ejecución. La API respondió a la comprobación de salud durante la recuperación y atendió las consultas de estado. Los informes de las dos ejecuciones tuvieron el mismo SHA-256.

| Medida | Ejecución normal | Tras detener un worker |
| --- | ---: | ---: |
| Tiempo desde el inicio de la subida hasta observar el resultado | 25,742 s | 28,242 s |
| Espera desde la aceptación hasta el inicio del último intento | 0,430 s | 1,478 s |
| Duración del último intento en el worker | 0,538 s | 0,548 s |
| Filas por segundo en el último intento | 464.684 | 456.204 |
| Mayor uso de heap de la API observado por muestreo | 172,6 MiB | 73,8 MiB |

El tiempo de subida depende del entorno local. El heap corresponde al proceso HTTP. Consulta el [procedimiento de reproducción](../README.md#salud-y-prueba-de-carga).

## Medición anterior con la API y el procesamiento en el mismo proceso

| Medida | Ejecución normal | Tras interrumpir el proceso |
| --- | ---: | ---: |
| Tiempo desde el inicio de la subida hasta observar el resultado | 7,827 s | 12,617 s |
| Espera desde la aceptación hasta el inicio del último intento | 0,487 s | 4,884 s |
| Duración del último intento en el servidor | 0,574 s | 0,696 s |
| Filas por segundo en el último intento | 435.540 | 359.195 |
| Mayor uso de heap observado por muestreo | 181,7 MiB | 147,4 MiB |

La prueba detuvo el contenedor tras confirmar que la primera escritura de incidencias estaba en curso. Después del reinicio, las dos ejecuciones devolvieron 250.000 filas procesadas y 25.000 inválidas. Los informes tuvieron el mismo SHA-256.

`ddeaa21edb191446ecad1f12ede900cb519248313e7f397673fa3ab1ca5a2a3b`

Son cifras de ejecuciones locales. El muestreo de Actuator puede omitir picos entre lecturas.
