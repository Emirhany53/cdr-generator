# 🚀 EMM CDR Generator

EMM CDR Generator, telekomünikasyon sistemlerinde kullanılan ASN.1 tabanlı CDR (Call Detail Record) yapılarına göre test verisi üretmek amacıyla geliştirilmiş bir **Java Spring Boot** uygulamasıdır.

Proje, Ericsson Mediation Manager (EMM) akışlarının test edilmesini kolaylaştırmak amacıyla geliştirilmiştir. Kullanıcı tarafından seçilen ASN.1 yapısına göre hem **Token-Separated (.dat)** hem de **BER (Binary Encoding Rules) (.ber)** formatında çıktı üretebilmektedir.

---

# ✨ Features

- Dynamic ASN.1 parsing from `datastructure.json`
- Support for complex ASN.1 structures (SEQUENCE, CHOICE, primitive types)
- Pipe-separated ASCII (`.dat`) file generation
- BER / TLV encoded binary (`.ber`) file generation
- Automatic mock data generation
- Custom value override support via JSON request body
- Configurable application settings using `application.yml`
- Layered architecture following SOLID principles
- RESTful API with Swagger/OpenAPI documentation

---

# 🛠 Technologies

- Java 21
- Spring Boot
- Spring Web MVC
- Maven
- Jackson
- Swagger / OpenAPI
- Lombok
- SLF4J Logging

---

# 🏗 Architecture

```
                Client
                  │
                  ▼
             REST Controller
                  │
                  ▼
                Service
                  │
     ┌────────────┼─────────────┐
     ▼            ▼             ▼
 ASN.1 Parser  Data Generator  BER Encoder
     │            │             │
     └────────────┼─────────────┘
                  ▼
             File Writer
                  │
        ┌─────────┴─────────┐
        ▼                   ▼
      .dat                .ber
```

---

# 📁 Project Structure

```
src
├── config
├── controller
├── service
├── parser
├── generator
├── encoder
├── writer
├── model
├── util
└── exception
```

---

# 🚀 Getting Started

## Clone the repository

```bash
git clone https://github.com/Emirhany53/cdr-generator.git
cd cdr-generator
```

## Build the project

```bash
mvn clean install
```

## Run the application

```bash
mvn spring-boot:run
```

The application will start on:

```
http://localhost:8080
```

---

# ⚙ Configuration

Application configuration is managed via:

```
src/main/resources/application.yml
```

Configuration includes:

- Datastructure file path
- Default record count
- Maximum record count
- Server configuration

---

# 📡 REST API

## List Available Structures

Returns all parsed ASN.1 structures.

```
GET /api/cdr/structures
```

---

## Generate Token-Separated File (.dat)

Generates a pipe-separated ASCII CDR file.

```
POST /api/cdr/generate?structureName=TokenCodedCDR&recordCount=5
```

---

## Generate BER File (.ber)

Generates a BER encoded binary file.

```
POST /api/cdr/generate-ber
```

Example request body:

```json
{
  "callingNumber": "905321112233",
  "calledNumber": "905554445566"
}
```

---

# 📖 Swagger Documentation

After starting the application, Swagger UI is available at:

```
http://localhost:8080/swagger-ui.html
```

---

# 📂 Example Output

### Token-Separated (.dat)

```
905321112233|905554445566|20260717|SMS|SUCCESS
```

### BER

```
output.ber
```

The generated BER file follows the ASN.1 BER (Basic Encoding Rules) specification using TLV (Tag-Length-Value) encoding.

---

# 📌 Design Principles

This project follows several software engineering principles:

- Layered Architecture
- SOLID Principles
- Separation of Concerns
- Single Responsibility Principle (SRP)
- Configurable Application Design
- Clean Code Practices

---

# 🔮 Future Improvements

- Docker support
- PostgreSQL integration
- Kafka integration
- Additional ASN.1 type support
- Performance optimizations
- Unit & Integration Tests

---

# 👨‍💻 Author

**Emirhan Yıldız**

Software Engineering Student

GitHub: https://github.com/Emirhany53
