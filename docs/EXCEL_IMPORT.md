# Excel Importación – Guía Técnica

Esta guía describe **todos los aspectos relacionados con la importación de archivos Excel** en el proyecto Asistencias, tanto para usuarios finales como para desarrolladores backend.

---

## 📂 Archivo de plantilla
- La plantilla oficial se genera en la aplicación mediante la opción **Descargar Plantilla**.
- Columnas obligatorias:
  1. **Nombre Completo** – Texto.
  2. **Fecha Nacimiento** – Tipo `Date` con formato `yyyy‑MM‑dd` (celda configurada como **Date** en Excel).
  3. **Débito / Abono** – Tipo `Currency` configurado en **COP** sin decimales (`$#,##0`).
  4. **Grupo**, **Sede**, **Estado** – Texto.
- Los formatos `Date` y `Currency` se aplican a **registros activos e inactivos**.

---

## ⚙️ Lógica de importación (backend)
- **Clase principal:** `ExcelImportService.java`.
- **Procesos clave:**
  - Lectura del archivo con Apache POI.
  - **Normalización de texto:** Se usan `java.text.Normalizer` para eliminar diacríticos, permitiendo coincidencias insensibles a tildes (p.ej., `iniciacion` == `iniciación`).
  - **Coincidencia de grupos, sedes y nombres:** Comparación case‑insensitive y diacritic‑insensitive contra la base de datos.
  - **Validación de formato:** Si una celda de fecha no coincide con `Date` o una moneda no con `Currency`, se rechaza la fila y se registra un error.

---

## 🔄 Deshacer Importación
- Cada importación genera un registro `ImportBatchLog` que almacena:
  - **newEntities** – IDs de entidades creadas.
  - **parentSnapshots** – Captura del estado previo de cada `Parent` (acudiente) que fue modificado.
- Al ejecutar **↩️ Deshacer Importación**:
  1. Se eliminan las entidades creadas.
  2. Se restauran los `Parent` a partir de sus `ParentSnapshot`, devolviendo nombres y teléfonos originales.
- Esta lógica garantiza que *todos* los cambios (incluso en registros preexistentes) desaparezcan como si nunca se hubiera subido el Excel.

---

## 📋 Validación y manejo de errores
| Escenario | Acción | Resultado |
|-----------|--------|-----------|
| Columna `Date` con formato incorrecto | Se marca la fila como inválida | Mensaje `Formato de fecha no válido` y la fila se ignora |
| Moneda sin formato `Currency` | Se marca la fila como inválida | Mensaje `Formato de moneda no válido` |
| Texto con tildes que no coincide con entidad existente | Se normaliza y compara | Si coincide, se **actualiza** la entidad; de lo contrario se **crea** una nueva |
| Archivo totalmente corrupto | No se crea `ImportBatchLog` | Se muestra error al usuario y no se realiza ninguna operación de escritura |

---

## 🚀 Pasos de verificación post‑cambio
1. **Reinicia** la aplicación (`AsistenciaApplication`).
2. Desde la UI, descarga la plantilla y verifica que las columnas **Fecha Nacimiento** y los campos monetarios muestren los estilos `Date` y `Currency` respectivamente.
3. Sube un archivo de prueba con datos correctos y comprueba que los grupos y sedes se asignen automáticamente.
4. Introduce datos intencionalmente erróneos (p. ej., formato de fecha `dd/MM/yyyy`).
5. Usa **Deshacer Importación** y revisa que los nombres y teléfonos de los acudientes vuelvan a su estado original.

---

## 📚 Referencias internas
- `ExcelImportService.java` – Lógica de importación y snapshots.
- `ParentSnapshot.java` – Entidad embebida para almacenar el estado previo de un acudiente.
- `ImportBatchLog.java` – Registro de cada batch de importación.
- `DataSeeder.java` – No afecta a la importación; solo crea datos iniciales cuando la BD está vacía.

---

*Esta guía debe mantenerse sincronizada con cualquier cambio futuro en la lógica de importación o en los formatos de celda.*
