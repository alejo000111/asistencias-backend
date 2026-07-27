# Asistencias – Documentación

Esta carpeta contiene la documentación actualizada del proyecto.

## HELP.md

El archivo [HELP.md](../HELP.md) ha sido actualizado con información sobre:
- Formato de columnas en Excel (Date y Currency en COP sin decimales).
- Verificación de coincidencias insensibles a tildes.
- Funcionalidad **Deshacer Importación** completa usando `ParentSnapshot`.
- Reglas estrictas del `DataSeeder` (solo crea datos cuando la BD está vacía y no elimina registros).
- Aplicación de formatos tanto a registros activos como inactivos.
- Coincidencia de texto para grupos, sedes y nombres.

## Verificación

1. Inicia la aplicación (`AsistenciaApplication`).
2. Descarga la plantilla Excel y verifica los estilos de celda.
3. Sube un archivo de prueba y usa **Deshacer Importación** para confirmar la reversión.
4. Asegúrate de que `DataSeeder` no borra datos al reiniciar.

---

*Esta documentación se mantiene sincronizada con los cambios de código.*
