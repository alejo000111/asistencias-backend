# Etapa de construcción
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -Dmaven.test.skip=true

# Etapa de ejecución
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# No correr como root dentro del contenedor (defensa en profundidad: si algún día se
# encuentra una vulnerabilidad de ejecución remota, el proceso no tiene privilegios de root).
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/target/*.jar app.jar
RUN chown app:app app.jar
USER app

EXPOSE 8080

# Permite a Docker/Compose saber si el backend está realmente listo para recibir tráfico,
# en vez de asumir que "el contenedor arrancó" == "la app respondió su primer request".
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:8080/api/public/precios || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
