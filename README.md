# Zapasli Backend

Backend для Zapasli — приложения совместного учёта домашних продуктов.

Текущая версия — backend foundation с первым auth vertical slice:

- Kotlin/JVM и Ktor;
- PostgreSQL и версионируемые Flyway-миграции;
- регистрация, вход, выход и защищённый профиль пользователя;
- Argon2id, короткоживущие JWT и ротация refresh token с reuse detection;
- отдельные rate limits для публичных auth endpoints;
- liveness/readiness/version endpoints;
- единый OpenAPI 3.1 документ и Swagger UI;
- локальный Docker Compose без публичного доступа к БД;
- unit, HTTP и PostgreSQL integration tests;
- CI для сборки, тестов и production container;
- Caddy routes для API и отдельного контейнера продуктового сайта.

## Требования

- JDK 17 или новее;
- Docker Engine / Docker Desktop — для полного локального окружения и
  integration tests;
- PowerShell на Windows либо совместимый shell на Linux/macOS.

Gradle отдельно устанавливать не нужно: используется Gradle Wrapper.
