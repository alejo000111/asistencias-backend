# Asistencias - Guía Rápida Actualizada

## ☕ Requisito de Versión de Java (Java 21 LTS)

Este proyecto está **estrictamente configurado para usar Java 21 (LTS)** tanto para desarrollo local como para despliegue en Docker.
- **Maven Enforcer Plugin**: Si intentas compilar o ejecutar pruebas con una versión diferente de JDK (por ejemplo, Java 25 o Java 17), la construcción de Maven fallará inmediatamente.
- **Docker**: El archivo `Dockerfile` utiliza las imágenes oficiales `eclipse-temurin:21` asegurando consistencia total con el entorno de desarrollo.

## 📥 Importación de Excel

- **Formato de columnas**:
  - `Date` → Formato de Fecha nativo (`yyyy‑MM‑dd`).
  - `Currency` → Formato de moneda en COP sin decimales (`$#,##0`).
- El importador ahora **verifica coincidencias insensibles a tildes** (p.ej., `iniciacion` == `iniciación`).
- Si el archivo contiene datos erróneos, la funcionalidad **Deshacer Importación** revertirá **todas** las modificaciones, incluyendo cambios en nombres y teléfonos de acudientes existentes, gracias a la captura de `ParentSnapshot`.

## 🗂️ DataSeeder

- El `DataSeeder` **solo crea** datos cuando la base de datos está vacía (`parentRepository.count() == 0`).
- **No realiza** ninguna operación de borrado (`delete`, `deleteAll`).
- Para limpiar datos, el administrador debe hacerlo manualmente desde la base de datos.

## 📊 Formatos en Excel (Exportar y Plantilla)

- **Fecha de Nacimiento** → estilo `Date` (`yyyy‑MM‑dd`).
- **Campos monetarios** (`Debe`, `Abono`, etc.) → estilo `Currency` configurado en COP sin decimales.
- Los formatos se aplican tanto a registros **activos** como **inactivos**.

## 🔗 Coincidencia de Texto

- Grupos, sedes y nombres se comparan **ignorando mayúsculas/minúsculas y tildes** para evitar duplicados inesperados durante la importación.

## 🚀 Pasos de Verificación

1. Inicia la aplicación (`AsistenciaApplication`).
2. Descarga la plantilla desde la UI y verifica los estilos de celda en Excel.
3. Sube un archivo de prueba y luego usa **Deshacer Importación** para confirmar la reversión completa.
4. Revisa que `DataSeeder` no elimine datos al reiniciar la aplicación.

---

*Esta guía ha sido actualizada para reflejar los últimos cambios de funcionalidad y formato.*

### Reference Documentation
For further reference, please consider the following sections:

* [Official Apache Maven documentation](https://maven.apache.org/guides/index.html)
* [Spring Boot Maven Plugin Reference Guide](https://docs.spring.io/spring-boot/4.0.3/maven-plugin)
* [Create an OCI image](https://docs.spring.io/spring-boot/4.0.3/maven-plugin/build-image.html)
* [Spring Web](https://docs.spring.io/spring-boot/4.0.3/reference/web/servlet.html)
* [Spring Data JPA](https://docs.spring.io/spring-boot/4.0.3/reference/data/sql.html#data.sql.jpa-and-spring-data)

### Guides
The following guides illustrate how to use some features concretely:

* [Building a RESTful Web Service](https://spring.io/guides/gs/rest-service/)
* [Serving Web Content with Spring MVC](https://spring.io/guides/gs/serving-web-content/)
* [Building REST services with Spring](https://spring.io/guides/tutorials/rest/)
* [Accessing Data with JPA](https://spring.io/guides/gs/accessing-data-jpa/)
* [Accessing data with MySQL](https://spring.io/guides/gs/accessing-data-mysql/)

### Maven Parent overrides

Due to Maven's design, elements are inherited from the parent POM to the project POM.
While most of the inheritance is fine, it also inherits unwanted elements like `<license>` and `<developers>` from the parent.
To prevent this, the project POM contains empty overrides for these elements.
If you manually switch to a different parent and actually want the inheritance, you need to remove those overrides.

