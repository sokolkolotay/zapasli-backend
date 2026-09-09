# Zapasli Backend

Backend для Zapasli — приложения совместного учёта домашних продуктов.

Текущая версия — engineering foundation:

- Kotlin/JVM и Ktor;
- PostgreSQL и версионируемые Flyway-миграции;
- liveness/readiness/version endpoints;
- единый OpenAPI 3.1 документ и Swagger UI;
- локальный Docker Compose без публичного доступа к БД;
- unit, HTTP и PostgreSQL integration tests;
- CI для сборки, тестов и production container.

## Требования

- JDK 17 или новее;
- Docker Engine / Docker Desktop — для полного локального окружения и
  integration tests;
- PowerShell на Windows либо совместимый shell на Linux/macOS.

Gradle отдельно устанавливать не нужно: используется Gradle Wrapper.
