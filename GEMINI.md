# GEMINI.md

## Project Overview

This is a URL shortener service built with Java and Spring Boot. It allows users to create short URLs that redirect to long URLs. The service also supports creating URLs in bulk by uploading an Excel file.

The project uses the following technologies:
- **Java 17**
- **Spring Boot 3**
- **Spring Web** for creating REST APIs
- **Spring Data JPA** for database access
- **PostgreSQL** as the database
- **Liquibase** for database schema management
- **Spring Batch** for processing bulk URL creation from Excel files
- **AWS S3** for storing uploaded files
- **Lombok** to reduce boilerplate code
- **MapStruct** for object mapping
- **Maven** as the build tool

## Building and Running

### Prerequisites
- Java 17
- Maven
- Docker (optional, for running PostgreSQL)

### Running the Application
1. **Start the database:**
   You can run a PostgreSQL database using Docker:
   ```bash
   docker run -d --name shorter-url-db -e POSTGRES_DB=shorter_url -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=12345678 -p 5432:5432 postgres
   ```
2. **Run the application:**
   You can run the application using Maven:
   ```bash
   ./mvnw spring-boot:run
   ```
   The application will be available at `http://localhost:8080`.

### API Endpoints

The main API endpoints are defined in `src/main/java/com/hhh/url/shorter_url/controller/UrlController.java`.

- `POST /api/v1/urls`: Creates a new short URL.
- `GET /api/v1/urls/redirect?shortCode={shortCode}`: Redirects a short code to the original URL.
- `GET /api/v1/urls/{id}`: Retrieves a URL by its ID.
- `GET /api/v1/urls`: Retrieves all URLs with pagination.
- `PUT /api/v1/urls/{id}`: Updates a URL.
- `DELETE /api/v1/urls/{id}`: Deletes a URL.

There are also endpoints for bulk operations and file uploads in `BulkController.java` and `ObjectStorageController.java`.

## Development Conventions

### Code Style
- The project uses Google's Java style guide.
- Use Lombok annotations to reduce boilerplate code.
- Use MapStruct for mapping between DTOs and entities.

### Testing
- Unit tests are located in `src/test/java`.
- Write tests for all new features and bug fixes.
- Use JUnit 5 and Mockito for testing.

### Database Migrations
- Database schema changes are managed with Liquibase.
- Migration scripts are located in `src/main/resources/db/changelog`.
- Create a new Liquibase changeset for each database schema change.
