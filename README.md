# Flicknames Service

Backend service for the Flicknames application built with Spring Boot.

## Technology Stack

- Java 17
- Spring Boot 3.2.1
- Spring Data JPA
- H2 Database (in-memory)
- Maven

## Getting Started

### Prerequisites

- Java 17 or higher
- Maven 3.6+

### Running the Application

```bash
mvn spring-boot:run
```

The application will start on `http://localhost:8080`

### Building the Application

```bash
mvn clean package
```

### Running Tests

```bash
mvn test
```

## API Endpoints

### Health Check
- **GET** `/api/health` - Returns service health status

## H2 Database Console

Access the H2 console at: `http://localhost:8080/h2-console`

- JDBC URL: `jdbc:h2:mem:flicknamesdb`
- Username: `sa`
- Password: (leave empty)

## Project Structure

```
src/
├── main/
│   ├── java/
│   │   └── com/flicknames/service/
│   │       ├── FlicknamesServiceApplication.java
│   │       └── controller/
│   │           └── HealthController.java
│   └── resources/
│       └── application.properties
└── test/
    └── java/
        └── com/flicknames/service/
```
