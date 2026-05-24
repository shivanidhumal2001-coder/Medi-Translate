# MediTranslate

MediTranslate is a Spring Boot + MySQL medical report assistant that follows the 3-module flow from the provided project diagram:

1. `MediTranslate Core`
2. `MediVerify`
3. `MediBot`

It supports guest or logged-in usage, report upload or pasted text, OCR/document extraction, plain-language explanation, verification layers, a trust score, symptom linking, medication reminder generation, PDF export, and browser-based text-to-speech.

## Tech Stack

- Java 17
- Spring Boot 3.3.5
- Thymeleaf
- Spring Security
- Spring Data JPA
- MySQL
- Apache Tika
- Tess4J
- OpenPDF

## Modules

### Module 1: MediTranslate Core

- Guest and registered user access
- Login and registration
- Paste text or upload image/document report
- OCR and document text extraction
- Plain-language explanation
- Multilingual summary support
- Urgency flag
- PDF export
- Browser text-to-speech
- Save report history for logged-in users

### Module 2: MediVerify

- Layer 1: printed lab range detection
- Layer 2: WHO/ICMR-style fallback reference ranges
- Layer 3: descriptive text verification
- Side-by-side comparison table
- Trust score generation

### Module 3: MediBot

- Context-aware follow-up Q&A
- Symptom checker linked to abnormal findings
- Medication reminder schedule generator
- Chat history persistence for logged-in users

## Database Configuration

The project is already configured with the provided MySQL connection:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/SpringDB?useSSL=false&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=shivani@2025
```

Make sure the `SpringDB` database exists in local MySQL before starting the app.

## Run

```bash
mvn spring-boot:run
```

Then open:

```text
http://localhost:8080
```

## Notes

- OCR for image uploads uses Tesseract data from:

```text
/usr/share/tesseract-ocr/5/tessdata
```

- Claude integration is prepared but disabled by default. The project uses a local fallback summary engine until a Claude API key is configured in `application.properties`.
- Uploaded files are stored in the local `uploads/` directory.
- Hibernate is set to `update`, so tables will be created automatically on first run.

## Structure

```text
src/main/java/com/meditranslate
  config
  controller
  dto
  entity
  repository
  security
  service
  service/impl

src/main/resources
  templates
  static/css
  static/js
```

## Build Check

The project was verified with:

```bash
mvn test
```
